package uzelok.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import uzelok.domain.model.PendingAction;
import uzelok.domain.model.PendingActionType;
import uzelok.domain.port.PendingActionRepository;

@Repository
public class JdbcPendingActionRepository implements PendingActionRepository {

    private static final String INSERT_ACTION = """
            INSERT INTO pending_action (token, job_id, tenant_id, action, chat_id, message_id,
                                        expires_at, used_at)
            VALUES (:token, :jobId, :tenantId, :action, :chatId, :messageId, :expiresAt, :usedAt)
            RETURNING token, job_id, tenant_id, action, chat_id, message_id, expires_at, used_at
            """;

    private static final String CONSUME = """
            UPDATE pending_action
            SET used_at = :now
            WHERE token = :token AND chat_id = :chatId
              AND used_at IS NULL AND expires_at > :now
            RETURNING token, job_id, tenant_id, action, chat_id, message_id, expires_at, used_at
            """;

    private static final String FIND_BY_TOKEN = """
            SELECT token, job_id, tenant_id, action, chat_id, message_id, expires_at, used_at
            FROM pending_action
            WHERE token = :token
            """;

    private static final String INVALIDATE_FOR_JOB = """
            UPDATE pending_action
            SET used_at = now()
            WHERE job_id = :jobId AND used_at IS NULL
            """;

    private static final RowMapper<PendingAction> ROW_MAPPER = (rs, rowNum) -> new PendingAction(
            rs.getString("token"),
            rs.getObject("job_id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            PendingActionType.valueOf(rs.getString("action")),
            rs.getLong("chat_id"),
            rs.getObject("message_id", Long.class),
            toInstant(rs.getObject("expires_at", OffsetDateTime.class)),
            toInstant(rs.getObject("used_at", OffsetDateTime.class)));

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcPendingActionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public PendingAction create(PendingAction action) {
        return jdbc.queryForObject(INSERT_ACTION, new MapSqlParameterSource()
                .addValue("token", action.token())
                .addValue("jobId", action.jobId())
                .addValue("tenantId", action.tenantId())
                .addValue("action", action.type().name())
                .addValue("chatId", action.chatId())
                .addValue("messageId", action.messageId())
                .addValue("expiresAt", toOffsetDateTime(action.expiresAt()))
                .addValue("usedAt", toOffsetDateTime(action.usedAt())), ROW_MAPPER);
    }

    @Override
    @Transactional
    public Optional<PendingAction> consume(String token, long chatId, Instant now) {
        List<PendingAction> consumed = jdbc.query(CONSUME, new MapSqlParameterSource()
                .addValue("token", token)
                .addValue("chatId", chatId)
                .addValue("now", toOffsetDateTime(now)), ROW_MAPPER);
        return consumed.stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PendingAction> findByToken(String token) {
        List<PendingAction> found = jdbc.query(
                FIND_BY_TOKEN,
                new MapSqlParameterSource("token", token),
                ROW_MAPPER);
        return found.stream().findFirst();
    }

    @Override
    @Transactional
    public void invalidateForJob(UUID jobId) {
        jdbc.update(INVALIDATE_FOR_JOB, new MapSqlParameterSource("jobId", jobId));
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime toOffsetDateTime(Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
