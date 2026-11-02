package uzelok.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
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
import uzelok.domain.model.PendingAction;
import uzelok.domain.model.PendingActionType;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "UZELOK_TEST_DB_URL", matches = ".+")
class PendingActionRepositoryIT {

    private static final String SCHEMA = "uzelok_action_it_"
            + UUID.randomUUID().toString().replace("-", "");

    private String url;
    private String user;
    private String password;
    private JdbcTemplate baseJdbc;
    private JdbcTemplate jdbc;

    private UUID tenantId;
    private UUID jobId;

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
    }

    @BeforeEach
    void setUpJob() {
        tenantId = newTenant();
        UUID knowledgeBaseId = newKnowledgeBase(tenantId);
        UUID sourceItemId = newSourceItem(tenantId, knowledgeBaseId, 700L);
        jobId = newJob(tenantId, knowledgeBaseId, sourceItemId);
    }

    @AfterAll
    void cleanup() {
        if (baseJdbc != null) {
            baseJdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    @Test
    void createdTokenCanBeConsumedOnlyOnce() {
        JdbcPendingActionRepository repository = new JdbcPendingActionRepository(named());
        Instant now = Instant.parse("2026-05-01T12:00:00Z");
        repository.create(action("token-1", jobId, 700L, now.plusSeconds(3600)));

        assertThat(repository.consume("token-1", 700L, now)).isPresent();
        assertThat(repository.consume("token-1", 700L, now)).isEmpty();
        assertThat(repository.findByToken("token-1").orElseThrow().usedAt()).isNotNull();
    }

    @Test
    void expiredTokenCannotBeConsumed() {
        JdbcPendingActionRepository repository = new JdbcPendingActionRepository(named());
        Instant now = Instant.parse("2026-05-01T12:00:00Z");
        repository.create(action("token-2", jobId, 700L, now.minusSeconds(1)));

        assertThat(repository.consume("token-2", 700L, now)).isEmpty();
    }

    @Test
    void tokenOfAnotherChatCannotBeConsumed() {
        JdbcPendingActionRepository repository = new JdbcPendingActionRepository(named());
        Instant now = Instant.parse("2026-05-01T12:00:00Z");
        repository.create(action("token-3", jobId, 700L, now.plusSeconds(3600)));

        assertThat(repository.consume("token-3", 701L, now)).isEmpty();
    }

    private PendingAction action(String token, UUID targetJob, long chatId, Instant expiresAt) {
        return new PendingAction(token, targetJob, tenantId, PendingActionType.SAVE, chatId, null,
                expiresAt, null);
    }

    private NamedParameterJdbcTemplate named() {
        return new NamedParameterJdbcTemplate(jdbc.getDataSource());
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
