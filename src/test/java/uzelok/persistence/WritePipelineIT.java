package uzelok.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
import uzelok.config.JacksonJsonCodec;
import uzelok.domain.model.ArtifactKind;
import uzelok.domain.model.JobArtifact;
import uzelok.domain.model.JobStatus;
import uzelok.domain.model.KnowledgeBase;
import uzelok.domain.model.KnowledgeBaseKind;
import uzelok.domain.model.NoteDraft;
import uzelok.domain.model.NoteType;
import uzelok.domain.model.ProcessingJob;
import uzelok.domain.model.Taxonomy;
import uzelok.domain.port.KnowledgeStore;
import uzelok.domain.port.PreparedNote;
import uzelok.domain.port.WrittenNote;
import uzelok.pipeline.WriteStep;
import uzelok.support.FixedKnowledgeBaseRepository;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "UZELOK_TEST_DB_URL", matches = ".+")
class WritePipelineIT {

    private static final String SCHEMA = "uzelok_write_it_"
            + UUID.randomUUID().toString().replace("-", "");

    private String url;
    private String user;
    private String password;
    private JdbcTemplate baseJdbc;
    private JdbcTemplate jdbc;
    private NamedParameterJdbcTemplate named;
    private JacksonJsonCodec codec;

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
        codec = new JacksonJsonCodec(JsonMapper.builder().build());
    }

    @AfterAll
    void cleanup() {
        if (baseJdbc != null) {
            baseJdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    @Test
    void awaitingConfirmBecomesDoneWithNoteAndVersion() {
        Fixture fixture = fixture();

        assertThat(fixture.jobs.startWriting(fixture.job.id(), false)).isTrue();
        ProcessingJob writing = fixture.jobs.find(fixture.job.id()).orElseThrow();
        assertThat(writing.status()).isEqualTo(JobStatus.WRITING);

        fixture.write.run(writing);
        assertThat(fixture.jobs.transition(writing.id(), JobStatus.WRITING, JobStatus.DONE)).isTrue();

        assertThat(fixture.jobs.find(fixture.job.id()).orElseThrow().status())
                .isEqualTo(JobStatus.DONE);
        assertThat(count("note", fixture.tenantId)).isEqualTo(1);
        assertThat(count("note_version", fixture.tenantId)).isEqualTo(1);
    }

    @Test
    void repeatedWriteOfSameContentDoesNotCreateSecondVersion() {
        Fixture fixture = fixture();
        fixture.jobs.startWriting(fixture.job.id(), false);
        ProcessingJob writing = fixture.jobs.find(fixture.job.id()).orElseThrow();
        fixture.write.run(writing);

        fixture.store.created = false;
        fixture.write.run(writing);

        assertThat(count("note", fixture.tenantId)).isEqualTo(1);
        assertThat(count("note_version", fixture.tenantId)).isEqualTo(1);
    }

    private Fixture fixture() {
        UUID tenantId = newTenant();
        UUID knowledgeBaseId = newKnowledgeBase(tenantId);
        UUID sourceItemId = newSourceItem(tenantId, knowledgeBaseId, 700L);
        UUID jobId = newJob(tenantId, knowledgeBaseId, sourceItemId);
        KnowledgeBase base = new KnowledgeBase(knowledgeBaseId,
                KnowledgeBaseKind.EXTERNAL_OBSIDIAN, "/vault",
                "40_Knowledge/_meta/taxonomy.yaml", "40_Knowledge/_Inbox");
        JdbcArtifactRepository artifacts = new JdbcArtifactRepository(named);
        NoteDraft draft = new NoteDraft("Заметка", NoteType.concept, "Graphs", "summary",
                "## v1", 0.9);
        artifacts.save(new JobArtifact(UUID.randomUUID(), jobId, tenantId,
                ArtifactKind.DRAFT_JSON, 1, "sha", null, codec.write(draft), null, null,
                Instant.now()));
        FakeStore store = new FakeStore();
        JdbcJobRepository jobs = new JdbcJobRepository(named);
        WriteStep write = new WriteStep(artifacts, new FixedKnowledgeBaseRepository(base), store,
                new JdbcNoteRepository(named),
                new JdbcAuditRepository(named, JsonMapper.builder().build()), codec);
        return new Fixture(tenantId, jobs.find(jobId).orElseThrow(), jobs, write, store);
    }

    private int count(String table, UUID tenantId) {
        Integer value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE tenant_id = ?", Integer.class, tenantId);
        return value == null ? 0 : value;
    }

    private record Fixture(UUID tenantId, ProcessingJob job, JdbcJobRepository jobs,
                           WriteStep write, FakeStore store) {
    }

    private static final class FakeStore implements KnowledgeStore {

        private boolean created = true;
        private String markdown = "## v1";
        private String hash = "hash-1";

        @Override
        public PreparedNote prepare(KnowledgeBase base, String jobRef, NoteDraft draft,
                                    boolean forceInbox) {
            UUID noteId = UUID.nameUUIDFromBytes(("note:" + jobRef).getBytes());
            return new PreparedNote(noteId, "40_Knowledge/Algorithms/Graphs/Test.md", "Test.md",
                    markdown, hash, List.of());
        }

        @Override
        public WrittenNote write(KnowledgeBase base, PreparedNote note) {
            return new WrittenNote(note.noteId(), note.relPath(), note.contentHash(), created);
        }

        @Override
        public Set<String> existingNoteNames(KnowledgeBase base) {
            return Set.of();
        }

        @Override
        public Taxonomy taxonomy(KnowledgeBase base) {
            return new Taxonomy(2, List.of());
        }
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

    private UUID newKnowledgeBase(UUID tenant) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO knowledge_base (id, tenant_id, kind, root_path) "
                + "VALUES (?, ?, 'EXTERNAL_OBSIDIAN', '/tmp/vault')", id, tenant);
        return id;
    }

    private UUID newSourceItem(UUID tenant, UUID knowledgeBaseId, long chatId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO source_item (id, tenant_id, knowledge_base_id, chat_id, "
                        + "telegram_message_id, kind, text_body) VALUES (?, ?, ?, ?, ?, 'TEXT', ?)",
                id, tenant, knowledgeBaseId, chatId,
                Math.abs(UUID.randomUUID().getMostSignificantBits()), "текст");
        return id;
    }

    private UUID newJob(UUID tenant, UUID knowledgeBaseId, UUID sourceItemId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO processing_job (id, tenant_id, source_item_id, "
                        + "knowledge_base_id, status, attempt_count, next_run_at) "
                        + "VALUES (?, ?, ?, ?, 'AWAITING_CONFIRM', 0, now())",
                id, tenant, sourceItemId, knowledgeBaseId);
        return id;
    }

    private static String environment(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
