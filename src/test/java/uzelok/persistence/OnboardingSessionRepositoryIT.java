package uzelok.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import uzelok.domain.model.KnowledgeBase;
import uzelok.domain.model.KnowledgeBaseKind;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "UZELOK_TEST_DB_URL", matches = ".+")
class OnboardingSessionRepositoryIT {

    private static final String SCHEMA = "uzelok_onb_" + UUID.randomUUID().toString().replace("-", "");
    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");

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
    void awaitPathIsVisibleUntilConsumed() {
        UUID tenantId = newTenant();
        long chatId = newChat();
        JdbcOnboardingSessionRepository sessions = new JdbcOnboardingSessionRepository(named);

        sessions.awaitPath(tenantId, chatId, NOW.plusSeconds(900));

        assertThat(sessions.isAwaitingPath(chatId, NOW)).isTrue();
        assertThat(sessions.consumeAwaitPath(chatId, NOW)).contains(tenantId);
        assertThat(sessions.consumeAwaitPath(chatId, NOW)).isEmpty();
        assertThat(sessions.isAwaitingPath(chatId, NOW)).isFalse();
    }

    @Test
    void expiredSessionIsInvisibleAndNotConsumed() {
        UUID tenantId = newTenant();
        long chatId = newChat();
        JdbcOnboardingSessionRepository sessions = new JdbcOnboardingSessionRepository(named);

        sessions.awaitPath(tenantId, chatId, NOW.minusSeconds(1));

        assertThat(sessions.isAwaitingPath(chatId, NOW)).isFalse();
        assertThat(sessions.consumeAwaitPath(chatId, NOW)).isEmpty();
    }

    @Test
    void clearRemovesSession() {
        UUID tenantId = newTenant();
        long chatId = newChat();
        JdbcOnboardingSessionRepository sessions = new JdbcOnboardingSessionRepository(named);
        sessions.awaitPath(tenantId, chatId, NOW.plusSeconds(900));

        sessions.clear(chatId);

        assertThat(sessions.isAwaitingPath(chatId, NOW)).isFalse();
    }

    @Test
    void awaitPathRemovesExpiredRowAndCreatesNewForChat() {
        UUID expiredTenant = newTenant();
        UUID freshTenant = newTenant();
        long chatId = newChat();
        JdbcOnboardingSessionRepository sessions = new JdbcOnboardingSessionRepository(named);

        jdbc.update("INSERT INTO onboarding_session (chat_id, tenant_id, step, expires_at) "
                        + "VALUES (?, ?, 'AWAITING_PATH', ?)",
                chatId, expiredTenant, OffsetDateTime.now(ZoneOffset.UTC).minusHours(1));

        sessions.awaitPath(freshTenant, chatId, Instant.now().plusSeconds(900));

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM onboarding_session WHERE chat_id = ?",
                Integer.class, chatId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT tenant_id FROM onboarding_session WHERE chat_id = ?",
                UUID.class, chatId)).isEqualTo(freshTenant);
    }

    @Test
    void knowledgeBaseSaveKeepsExistingRowForTenant() {
        UUID tenantId = newTenant();
        JdbcKnowledgeBaseRepository bases = new JdbcKnowledgeBaseRepository(named);
        UUID firstId = UUID.randomUUID();

        KnowledgeBase first = bases.save(tenantId, new KnowledgeBase(firstId,
                KnowledgeBaseKind.EXTERNAL_OBSIDIAN, "/tmp/vault-one",
                "40_Knowledge/_meta/taxonomy.yaml", "40_Knowledge/_Inbox"));
        KnowledgeBase second = bases.save(tenantId, new KnowledgeBase(UUID.randomUUID(),
                KnowledgeBaseKind.INTERNAL_DIR, "/tmp/vault-two",
                "40_Knowledge/_meta/taxonomy.yaml", "40_Knowledge/_Inbox"));

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.kind()).isEqualTo(KnowledgeBaseKind.EXTERNAL_OBSIDIAN);
        assertThat(second.rootPath()).isEqualTo("/tmp/vault-one");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM knowledge_base WHERE tenant_id = ?",
                Integer.class, tenantId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT root_path FROM knowledge_base WHERE tenant_id = ?",
                String.class, tenantId)).isEqualTo("/tmp/vault-one");
        assertThat(bases.findByRootPath("/tmp/vault-one")).isPresent();
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

    private long newChat() {
        return Math.abs(UUID.randomUUID().getMostSignificantBits());
    }

    private static String environment(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
