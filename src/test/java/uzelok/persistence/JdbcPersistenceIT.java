package uzelok.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;
import uzelok.domain.model.AuditRecord;
import uzelok.domain.model.ChatAccess;
import uzelok.domain.model.JobStatus;
import uzelok.domain.model.NoteRecord;
import uzelok.domain.model.NoteType;
import uzelok.domain.model.ProcessingJob;
import uzelok.domain.model.SourceAttachment;
import uzelok.domain.model.SourceItem;
import uzelok.domain.model.SourceKind;
import uzelok.domain.port.JobCreation;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "UZELOK_TEST_DB_URL", matches = ".+")
class JdbcPersistenceIT {

    private static final String SCHEMA = "uzelok_it_" + UUID.randomUUID().toString().replace("-", "");

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

    @AfterAll
    void cleanup() {
        if (baseJdbc != null) {
            baseJdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    @Test
    void accessRepositoryReturnsAllowedAndUnknownChats() {
        UUID tenantId = newTenant();
        UUID userId = newUser(tenantId, 555L);
        long chatId = newChat(tenantId, userId, true);

        JdbcAccessRepository repository = new JdbcAccessRepository(named);
        ChatAccess access = repository.findByChatId(chatId).orElseThrow();

        assertThat(access.tenantId()).isEqualTo(tenantId);
        assertThat(access.appUserId()).isEqualTo(userId);
        assertThat(access.telegramUserId()).isEqualTo(555L);
        assertThat(access.allowed()).isTrue();
        assertThat(repository.findByChatId(chatId + 999L)).isEmpty();
    }

    @Test
    void updateRegistrationIsDeduplicatedByUpdateId() {
        UUID tenantId = newTenant();
        UUID userId = newUser(tenantId, 600L);
        long chatId = newChat(tenantId, userId, true);
        JdbcTelegramUpdateRepository repository = new JdbcTelegramUpdateRepository(named);

        assertThat(repository.register(10_001, chatId, "message")).isTrue();
        assertThat(repository.register(10_001, chatId, "message")).isFalse();
        assertThat(repository.register(10_002, chatId, "callback_query")).isTrue();
    }

    @Test
    void sourceItemIsEnsuredByMessageId() {
        UUID tenantId = newTenant();
        UUID knowledgeBaseId = newKnowledgeBase(tenantId);
        JdbcSourceItemRepository repository = new JdbcSourceItemRepository(named);
        SourceItem first = repository.ensure(candidate(tenantId, knowledgeBaseId, 700L, 900L,
                SourceKind.TEXT, null, "первый")).item();
        SourceItem second = repository.ensure(candidate(tenantId, knowledgeBaseId, 700L, 900L,
                SourceKind.TEXT, null, "второй")).item();

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.textBody()).isEqualTo("первый");
        assertThat(repository.findByChatAndMessage(700L, 900L)).isPresent();
    }

    @Test
    void sourceItemIsEnsuredByMediaGroup() {
        UUID tenantId = newTenant();
        UUID knowledgeBaseId = newKnowledgeBase(tenantId);
        JdbcSourceItemRepository repository = new JdbcSourceItemRepository(named);
        SourceItem first = repository.ensure(candidate(tenantId, knowledgeBaseId, 710L, 910L,
                SourceKind.PHOTO, "group-1", null)).item();
        SourceItem second = repository.ensure(candidate(tenantId, knowledgeBaseId, 710L, 911L,
                SourceKind.PHOTO, "group-1", null)).item();

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.telegramMessageId()).isEqualTo(910L);
    }

    @Test
    void attachmentPositionsAreAllocatedAtomically() throws Exception {
        UUID tenantId = newTenant();
        UUID knowledgeBaseId = newKnowledgeBase(tenantId);
        SourceItem item = new JdbcSourceItemRepository(named)
                .ensure(candidate(tenantId, knowledgeBaseId, 720L, 920L,
                        SourceKind.PHOTO, null, null)).item();
        JdbcAttachmentRepository repository = new JdbcAttachmentRepository(named);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<SourceAttachment> first = executor.submit(
                    () -> appendAfter(start, repository, item, tenantId, "file-1"));
            Future<SourceAttachment> second = executor.submit(
                    () -> appendAfter(start, repository, item, tenantId, "file-2"));
            start.countDown();

            Set<Integer> positions = new HashSet<>(List.of(
                    first.get(10, TimeUnit.SECONDS).position(),
                    second.get(10, TimeUnit.SECONDS).position()));
            assertThat(positions).containsExactlyInAnyOrder(0, 1);
        } finally {
            executor.shutdownNow();
        }
        assertThat(repository.countBySourceItem(item.id())).isEqualTo(2);
    }

    @Test
    void noteUpsertIsIdempotentWhenWritingStepIsRetried() {
        UUID tenantId = newTenant();
        UUID knowledgeBaseId = newKnowledgeBase(tenantId);
        SourceItem item = new JdbcSourceItemRepository(named)
                .ensure(candidate(tenantId, knowledgeBaseId, 740L, 940L,
                        SourceKind.TEXT, null, "текст")).item();
        UUID jobId = new JdbcJobRepository(named)
                .createIfAbsent(jobCandidate(tenantId, knowledgeBaseId, item.id())).job().id();
        UUID noteId = UUID.randomUUID();
        JdbcNoteRepository repository = new JdbcNoteRepository(named);
        NoteRecord record = new NoteRecord(noteId, tenantId, knowledgeBaseId, jobId,
                NoteType.source_note, "Заголовок", "Architecture", "Modular_Monolith",
                "Zametka.md", "40_Knowledge/Architecture/Modular_Monolith/Zametka.md", "hash-1");

        UUID first = repository.upsert(record);
        UUID second = repository.upsert(new NoteRecord(noteId, tenantId, knowledgeBaseId,
                record.jobId(), NoteType.source_note, "Заголовок", "Architecture", "Modular_Monolith",
                "Zametka.md", "40_Knowledge/Architecture/Modular_Monolith/Zametka.md", "hash-2"));

        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM note WHERE tenant_id = ?",
                Integer.class, tenantId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT content_hash FROM note WHERE id = ?",
                String.class, first)).isEqualTo("hash-2");
    }

    @Test
    void jobIsCreatedOnlyOnceAndPostponedOnlyInReceived() {
        UUID tenantId = newTenant();
        UUID knowledgeBaseId = newKnowledgeBase(tenantId);
        SourceItem item = new JdbcSourceItemRepository(named)
                .ensure(candidate(tenantId, knowledgeBaseId, 730L, 930L,
                        SourceKind.TEXT, null, "текст")).item();
        JdbcJobRepository repository = new JdbcJobRepository(named);

        ProcessingJob candidate = jobCandidate(tenantId, knowledgeBaseId, item.id());
        JobCreation first = repository.createIfAbsent(candidate);
        JobCreation second = repository.createIfAbsent(jobCandidate(tenantId, knowledgeBaseId, item.id()));

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.job().id()).isEqualTo(first.job().id());
        assertThat(first.job().status()).isEqualTo(JobStatus.RECEIVED);

        assertThat(repository.countForChatSince(730L, Instant.parse("2026-01-01T00:00:00Z")))
                .isEqualTo(1);
        assertThat(repository.attachStatusMessage(first.job().id(), 777L)).isTrue();
        assertThat(repository.findBySourceItemId(item.id()).orElseThrow().statusMessageId())
                .isEqualTo(777L);

        Instant nextRun = Instant.parse("2026-02-01T00:00:00Z");
        assertThat(repository.postpone(first.job().id(), nextRun)).isTrue();
        assertThat(repository.findBySourceItemId(item.id()).orElseThrow().nextRunAt())
                .isEqualTo(nextRun);

        jdbc.update("UPDATE processing_job SET status = 'DONE' WHERE id = ?", first.job().id());
        assertThat(repository.postpone(first.job().id(), nextRun.plusSeconds(60))).isFalse();
    }

    @Test
    void auditDetailIsStoredAsJsonb() {
        UUID tenantId = newTenant();
        UUID entityId = UUID.randomUUID();
        JdbcAuditRepository repository = new JdbcAuditRepository(named, JsonMapper.builder().build());

        repository.record(new AuditRecord(tenantId, 740L, "test_action", "entity", entityId,
                Map.of("reason", "проверка", "count", 2)));
        repository.record(new AuditRecord(tenantId, 740L, "test_null_detail", null, null, null));

        String reason = jdbc.queryForObject(
                "SELECT detail->>'reason' FROM audit_event WHERE action = 'test_action'",
                String.class);
        String count = jdbc.queryForObject(
                "SELECT detail->>'count' FROM audit_event WHERE action = 'test_action'",
                String.class);
        Integer nullDetailCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE action = 'test_null_detail' AND detail IS NULL",
                Integer.class);

        assertThat(reason).isEqualTo("проверка");
        assertThat(count).isEqualTo("2");
        assertThat(nullDetailCount).isEqualTo(1);
    }

    private static SourceAttachment appendAfter(CountDownLatch start, JdbcAttachmentRepository repository,
                                                SourceItem item, UUID tenantId, String fileId)
            throws InterruptedException {
        start.await(5, TimeUnit.SECONDS);
        return repository.append(item.id(), tenantId, fileId, "/tmp/" + fileId,
                "image/jpeg", 3L, "sha-" + fileId);
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

    private UUID newUser(UUID tenantId, long telegramUserId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (id, tenant_id, telegram_user_id, display_name) "
                + "VALUES (?, ?, ?, ?)", id, tenantId, telegramUserId, "User");
        return id;
    }

    private long newChat(UUID tenantId, UUID userId, boolean allowed) {
        long chatId = Math.abs(UUID.randomUUID().getMostSignificantBits());
        jdbc.update("INSERT INTO telegram_account (chat_id, user_id, tenant_id, allowed) "
                + "VALUES (?, ?, ?, ?)", chatId, userId, tenantId, allowed);
        return chatId;
    }

    private UUID newKnowledgeBase(UUID tenantId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO knowledge_base (id, tenant_id, kind, root_path) "
                + "VALUES (?, ?, 'EXTERNAL_OBSIDIAN', '/tmp/vault')", id, tenantId);
        return id;
    }

    private static SourceItem candidate(UUID tenantId, UUID knowledgeBaseId, long chatId, long messageId,
                                        SourceKind kind, String mediaGroupId, String text) {
        return new SourceItem(
                UUID.randomUUID(),
                tenantId,
                knowledgeBaseId,
                chatId,
                messageId,
                kind,
                mediaGroupId,
                text,
                Instant.parse("2026-01-15T10:00:00Z"));
    }

    private static ProcessingJob jobCandidate(UUID tenantId, UUID knowledgeBaseId, UUID sourceItemId) {
        Instant now = Instant.parse("2026-01-15T10:00:00Z");
        return new ProcessingJob(
                UUID.randomUUID(),
                tenantId,
                sourceItemId,
                knowledgeBaseId,
                JobStatus.RECEIVED,
                null,
                0,
                now,
                null,
                null,
                null,
                null,
                null,
                now,
                now,
                0,
                false);
    }

    private static String environment(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
