package uzelok.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import uzelok.domain.port.OnboardingSessionRepository;

@Repository
public class JdbcOnboardingSessionRepository implements OnboardingSessionRepository {

    private static final String DELETE_EXPIRED = """
            DELETE FROM onboarding_session WHERE expires_at <= :now
            """;

    private static final String AWAIT_PATH = """
            INSERT INTO onboarding_session (chat_id, tenant_id, step, expires_at)
            VALUES (:chatId, :tenantId, 'AWAITING_PATH', :expiresAt)
            ON CONFLICT (chat_id) DO UPDATE
            SET tenant_id = EXCLUDED.tenant_id,
                step = EXCLUDED.step,
                expires_at = EXCLUDED.expires_at
            """;

    private static final String CONSUME_AWAIT_PATH = """
            DELETE FROM onboarding_session
            WHERE chat_id = :chatId AND expires_at > :now
            RETURNING tenant_id
            """;

    private static final String IS_AWAITING_PATH = """
            SELECT 1 FROM onboarding_session
            WHERE chat_id = :chatId AND expires_at > :now
            """;

    private static final String CLEAR = """
            DELETE FROM onboarding_session WHERE chat_id = :chatId
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcOnboardingSessionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void awaitPath(UUID tenantId, long chatId, Instant expiresAt) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update(DELETE_EXPIRED, new MapSqlParameterSource("now", now));
        jdbc.update(AWAIT_PATH, new MapSqlParameterSource()
                .addValue("chatId", chatId)
                .addValue("tenantId", tenantId)
                .addValue("expiresAt", toOffsetDateTime(expiresAt)));
    }

    @Override
    @Transactional
    public Optional<UUID> consumeAwaitPath(long chatId, Instant now) {
        List<UUID> consumed = jdbc.queryForList(
                CONSUME_AWAIT_PATH,
                new MapSqlParameterSource()
                        .addValue("chatId", chatId)
                        .addValue("now", toOffsetDateTime(now)),
                UUID.class);
        return consumed.stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isAwaitingPath(long chatId, Instant now) {
        List<Integer> found = jdbc.query(
                IS_AWAITING_PATH,
                new MapSqlParameterSource()
                        .addValue("chatId", chatId)
                        .addValue("now", toOffsetDateTime(now)),
                (rs, rowNum) -> rs.getInt(1));
        return !found.isEmpty();
    }

    @Override
    @Transactional
    public void clear(long chatId) {
        jdbc.update(CLEAR, new MapSqlParameterSource("chatId", chatId));
    }

    private static OffsetDateTime toOffsetDateTime(Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
