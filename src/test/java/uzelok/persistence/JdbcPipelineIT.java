package uzelok.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import uzelok.domain.model.ArtifactKind;
import uzelok.domain.model.JobArtifact;
import uzelok.domain.model.JobStatus;
import uzelok.domain.model.LlmCallRecord;
import uzelok.domain.model.ProcessingJob;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "UZELOK_TEST_DB_URL", matches = ".+")
class JdbcPipelineIT {

    private static final String SCHEMA = "uzelok_pipe_it_"
            + UUID.randomUUID().toString().replace("-", "");

    private String url;
    private String user;
    private String password;
    private JdbcTemplate baseJdbc;
    private JdbcTemplate jdbc;
    private NamedParameterJdbcTemplate named;

    @BeforeAll
    void migrate() {
        url = System.getenv("UZELOK_TEST_DB_URL");
        user = environment("UZELOK_TEST_DB_USER", "postgres");
        password = environment("UZELOK_TEST_DB_PASSWORD", "postgres");
        baseJdbc = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        baseJdbc.execute("CREATE SCHEMA " + SCHEMA);

        DataSource dataSource = new DriverManagerDataSource(schemaUrl(), user, password);
        Flyway.configure()
                .dataSource(dataSource)
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(dataSource);
        named = new NamedParameterJdbcTemplate(dataSource);
    }

    /**
     * Схема общая на весь класс, а claimNext забирает любую готовую задачу — без изоляции тест
     * конкурентного захвата видел бы job'ы, созданные предыдущими тестами. Поэтому всё, что осталось
     * в рабочих статусах от прошлых проверок, переводим в DONE: строки не удаляем, они нужны другим тестам.
     */
    @BeforeEach
    void closePreviousJobs() {
        jdbc.update("UPDATE processing_job SET status = 'DONE', lease_until = NULL "
                + "WHERE status IN ('RECEIVED','EXTRACTING','DRAFTING','WRITING')");
    }

    @AfterAll
    void cleanup() {
        if (baseJdbc != null) {
            baseJdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    @Test
    void claimNextUsesSkipLockedForConcurrentWorkers() throws Exception {
        UUID tenantId = newTenant();
        UUID knowledgeBaseId = newKnowledgeBase(tenantId);
        UUID first = newJob(tenantId, knowledgeBaseId, "RECEIVED");
        UUID second = newJob(tenantId, knowledgeBaseId, "RECEIVED");
        JdbcJobRepository repository = new JdbcJobRepository(named);
        // задачи созданы с next_run_at = now() - 1 минута, поэтому опорное время берём реальное,
        // а не фиксированное: иначе фильтр claimNext не находит ни одной строки
        Instant now = Instant.now();
        Instant lease = now.plusSeconds(600);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<ProcessingJob> left = executor.submit(() -> claimAfter(start, repository, now, lease));
            Future<ProcessingJob> right = executor.submit(() -> claimAfter(start, repository, now, lease));
            start.countDown();
            Set<UUID> claimed = new HashSet<>();
            claimed.add(left.get(10, TimeUnit.SECONDS).id());
            claimed.add(right.get(10, TimeUnit.SECONDS).id());
            assertThat(claimed).containsExactlyInAnyOrder(first, second);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void transitionRejectsWrongFromState() {
        UUID tenantId = newTenant();
        UUID knowledgeBaseId = newKnowledgeBase(tenantId);
        UUID jobId = newJob(tenantId, knowledgeBaseId, "RECEIVED");
        JdbcJobRepository repository = new JdbcJobRepository(named);

        assertThat(repository.transition(jobId, JobStatus.EXTRACTING, JobStatus.DRAFTING)).isFalse();
        assertThat(repository.transition(jobId, JobStatus.RECEIVED, JobStatus.EXTRACTING)).isTrue();
        assertThat(statusOf(jobId)).isEqualTo("EXTRACTING");
    }

    @Test
    void scheduleRetryAndMarkFailedUpdateJob() {
        UUID tenantId = newTenant();
        UUID knowledgeBaseId = newKnowledgeBase(tenantId);
        UUID retryJob = newJob(tenantId, knowledgeBaseId, "EXTRACTING");
        JdbcJobRepository repository = new JdbcJobRepository(named);
        Instant next = Instant.parse("2026-05-01T13:00:00Z");

        assertThat(repository.scheduleRetry(retryJob, next, "EXTRACTING", "llm_call_failed"))
                .isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT attempt_count FROM processing_job WHERE id = ?", Integer.class, retryJob))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT failed_stage FROM processing_job WHERE id = ?", String.class, retryJob))
                .isEqualTo("EXTRACTING");
        assertThat(jdbc.queryForObject(
                "SELECT error_code FROM processing_job WHERE id = ?", String.class, retryJob))
                .isEqualTo("llm_call_failed");

        assertThat(repository.markFailed(retryJob, "DRAFTING", "contract_violation", "плохой ответ"))
                .isTrue();
        assertThat(statusOf(retryJob)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                "SELECT error_message FROM processing_job WHERE id = ?", String.class, retryJob))
                .isEqualTo("плохой ответ");
    }

    @Test
    void llmCallRepositoryDeduplicatesByRequestKeyAndSumsSpent() {
        UUID tenantId = newTenant();
        UUID jobId = newJob(tenantId, newKnowledgeBase(tenantId), "EXTRACTING");
        JdbcLlmCallRepository repository = new JdbcLlmCallRepository(named);
        LlmCallRecord call = new LlmCallRecord(tenantId, jobId, "EXTRACT", "p", "m", "m",
                "uzelok.EXTRACT", null, "v1", "key-1", 10, 20, 0, 0, 0,
                new BigDecimal("0.001500"), 100, true, "{\"ok\":true}");

        repository.record(call);
        repository.record(new LlmCallRecord(tenantId, jobId, "EXTRACT", "p", "m", "m",
                "uzelok.EXTRACT", null, "v1", "key-1", 1, 1, 0, 0, 0,
                new BigDecimal("9.000000"), 1, false, null));

        assertThat(repository.findSuccessfulResponse("key-1")).contains("{\"ok\":true}");
        assertThat(repository.spentSince(tenantId, Instant.now().minusSeconds(60)))
                .isEqualByComparingTo(new BigDecimal("0.001500"));
        assertThat(repository.spentSince(UUID.randomUUID(), Instant.now().minusSeconds(60)))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void failedRecordDoesNotBlockLaterSuccess() {
        UUID tenantId = newTenant();
        UUID jobId = newJob(tenantId, newKnowledgeBase(tenantId), "EXTRACTING");
        JdbcLlmCallRepository repository = new JdbcLlmCallRepository(named);
        String requestKey = "retry-key";
        repository.record(new LlmCallRecord(tenantId, jobId, "EXTRACT", "p", "m", "m",
                "uzelok.EXTRACT", null, "v1", requestKey, 0, 0, 0, 0, 0,
                BigDecimal.ZERO, 10, false, null));

        repository.record(new LlmCallRecord(tenantId, jobId, "EXTRACT", "p", "m", "m",
                "uzelok.EXTRACT", null, "v1", requestKey, 10, 20, 0, 0, 0,
                new BigDecimal("0.001500"), 100, true, "{\"ok\":true}"));

        assertThat(repository.findSuccessfulResponse(requestKey)).contains("{\"ok\":true}");
        assertThat(repository.spentSince(tenantId, Instant.now().minusSeconds(60)))
                .isEqualByComparingTo(new BigDecimal("0.001500"));
    }

    @Test
    void artifactRepositoryUpsertsByVersionAndReturnsLatest() {
        UUID tenantId = newTenant();
        UUID jobId = newJob(tenantId, newKnowledgeBase(tenantId), "EXTRACTING");
        JdbcArtifactRepository repository = new JdbcArtifactRepository(named);

        repository.save(artifact(jobId, tenantId, 1, "first"));
        repository.save(artifact(jobId, tenantId, 1, "second"));
        repository.save(artifact(jobId, tenantId, 2, "third"));

        assertThat(repository.findLatest(jobId, ArtifactKind.EXTRACTION).orElseThrow().inlineText())
                .isEqualTo("third");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM job_artifact WHERE job_id = ? AND kind = 'EXTRACTION'",
                Integer.class, jobId)).isEqualTo(2);
    }

    private static ProcessingJob claimAfter(CountDownLatch start, JdbcJobRepository repository,
                                            Instant now, Instant lease) throws InterruptedException {
        start.await(5, TimeUnit.SECONDS);
        return repository.claimNext(now, lease).orElseThrow();
    }

    private static JobArtifact artifact(UUID jobId, UUID tenantId, int version, String text) {
        return new JobArtifact(UUID.randomUUID(), jobId, tenantId, ArtifactKind.EXTRACTION,
                version, "sha-" + text, null, text, null, null, Instant.now());
    }

    private UUID newJob(UUID tenantId, UUID knowledgeBaseId, String status) {
        UUID id = UUID.randomUUID();
        UUID sourceItemId = newSourceItem(tenantId, knowledgeBaseId);
        jdbc.update("INSERT INTO processing_job (id, tenant_id, source_item_id, "
                        + "knowledge_base_id, status, attempt_count, next_run_at) "
                        + "VALUES (?, ?, ?, ?, ?, 0, now() - interval '1 minute')",
                id, tenantId, sourceItemId, knowledgeBaseId, status);
        return id;
    }

    private UUID newSourceItem(UUID tenantId, UUID knowledgeBaseId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO source_item (id, tenant_id, knowledge_base_id, chat_id, "
                        + "telegram_message_id, kind, text_body) VALUES (?, ?, ?, ?, ?, 'TEXT', ?)",
                id, tenantId, knowledgeBaseId, Math.abs(UUID.randomUUID().getLeastSignificantBits()),
                Math.abs(UUID.randomUUID().getMostSignificantBits()), "текст");
        return id;
    }

    private String statusOf(UUID jobId) {
        return jdbc.queryForObject("SELECT status FROM processing_job WHERE id = ?",
                String.class, jobId);
    }

    private String schemaUrl() {
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA;
    }

    private UUID newTenant() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tenant (id, slug, name) VALUES (?, ?, ?)",
                id, "tenant-" + id, "Tenant " + id);
        return id;
    }

    private UUID newKnowledgeBase(UUID tenantId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO knowledge_base (id, tenant_id, kind, root_path) "
                + "VALUES (?, ?, 'EXTERNAL_OBSIDIAN', '/tmp/vault')", id, tenantId);
        return id;
    }

    private static String environment(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
