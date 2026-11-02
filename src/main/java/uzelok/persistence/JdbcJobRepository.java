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
import uzelok.domain.model.JobStatus;
import uzelok.domain.model.ProcessingJob;
import uzelok.domain.port.JobCreation;
import uzelok.domain.port.JobRepository;

@Repository
public class JdbcJobRepository implements JobRepository {

    private static final String SELECT_COLUMNS = """
            SELECT id, tenant_id, source_item_id, knowledge_base_id, status, failed_stage,
                   attempt_count, next_run_at, lease_until, status_message_id, error_code,
                   error_message, confirmed_at, created_at, updated_at
            FROM processing_job
            """;

    private static final String SELECT_BY_SOURCE_ITEM = SELECT_COLUMNS
            + " WHERE source_item_id = :sourceItemId";

    private static final String INSERT_JOB = """
            INSERT INTO processing_job (id, tenant_id, source_item_id, knowledge_base_id, status,
                                        failed_stage, attempt_count, next_run_at, lease_until,
                                        status_message_id, error_code, error_message, confirmed_at,
                                        created_at, updated_at)
            VALUES (:id, :tenantId, :sourceItemId, :knowledgeBaseId, 'RECEIVED',
                    :failedStage, :attemptCount, :nextRunAt, :leaseUntil,
                    :statusMessageId, :errorCode, :errorMessage, :confirmedAt,
                    :createdAt, :updatedAt)
            ON CONFLICT (source_item_id) DO NOTHING
            RETURNING id, tenant_id, source_item_id, knowledge_base_id, status, failed_stage,
                      attempt_count, next_run_at, lease_until, status_message_id, error_code,
                      error_message, confirmed_at, created_at, updated_at
            """;

    private static final String ATTACH_STATUS_MESSAGE = """
            UPDATE processing_job SET status_message_id = :messageId, updated_at = now()
            WHERE id = :jobId
            """;

    private static final String POSTPONE = """
            UPDATE processing_job SET next_run_at = :nextRunAt, updated_at = now()
            WHERE id = :jobId AND status = 'RECEIVED'
            """;

    private static final String COUNT_FOR_CHAT_SINCE = """
            SELECT COUNT(*)
            FROM processing_job j
            JOIN source_item s ON s.id = j.source_item_id
            WHERE s.chat_id = :chatId AND s.created_at >= :since
            """;

    private static final String CLAIM_JOB = """
            UPDATE processing_job
            SET lease_until = :leaseUntil, updated_at = now()
            WHERE id = (
                SELECT id
                FROM processing_job
                WHERE status IN ('RECEIVED','EXTRACTING','DRAFTING','WRITING')
                  AND next_run_at <= :now
                  AND (lease_until IS NULL OR lease_until < :now)
                ORDER BY next_run_at
                LIMIT 1
                FOR UPDATE SKIP LOCKED
            )
            RETURNING id, tenant_id, source_item_id, knowledge_base_id, status, failed_stage,
                      attempt_count, next_run_at, lease_until, status_message_id, error_code,
                      error_message, confirmed_at, created_at, updated_at
            """;

    private static final String RECLAIM_EXPIRED = """
            UPDATE processing_job
            SET attempt_count = attempt_count + 1, lease_until = NULL, updated_at = now()
            WHERE lease_until IS NOT NULL AND lease_until < :now
            """;

    private static final String TRANSITION = """
            UPDATE processing_job
            SET status = :to, lease_until = NULL, updated_at = now()
            WHERE id = :jobId AND status = :from
            """;

    private static final String SCHEDULE_RETRY = """
            UPDATE processing_job
            SET attempt_count = attempt_count + 1, next_run_at = :nextRunAt,
                failed_stage = :stage, error_code = :errorCode, lease_until = NULL,
                updated_at = now()
            WHERE id = :jobId
            """;

    private static final String MARK_FAILED = """
            UPDATE processing_job
            SET status = 'FAILED', failed_stage = :stage, error_code = :errorCode,
                error_message = :errorMessage, lease_until = NULL, updated_at = now()
            WHERE id = :jobId
            """;

    private static final RowMapper<ProcessingJob> ROW_MAPPER = (rs, rowNum) -> new ProcessingJob(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("source_item_id", UUID.class),
            rs.getObject("knowledge_base_id", UUID.class),
            JobStatus.valueOf(rs.getString("status")),
            rs.getString("failed_stage"),
            rs.getInt("attempt_count"),
            toInstant(rs.getObject("next_run_at", OffsetDateTime.class)),
            toInstant(rs.getObject("lease_until", OffsetDateTime.class)),
            nullableLong(rs.getObject("status_message_id", Long.class)),
            rs.getString("error_code"),
            rs.getString("error_message"),
            toInstant(rs.getObject("confirmed_at", OffsetDateTime.class)),
            toInstant(rs.getObject("created_at", OffsetDateTime.class)),
            toInstant(rs.getObject("updated_at", OffsetDateTime.class)));

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcJobRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProcessingJob> findBySourceItemId(UUID sourceItemId) {
        List<ProcessingJob> found = jdbc.query(
                SELECT_BY_SOURCE_ITEM,
                new MapSqlParameterSource("sourceItemId", sourceItemId),
                ROW_MAPPER);
        return found.stream().findFirst();
    }

    @Override
    @Transactional
    public JobCreation createIfAbsent(ProcessingJob candidate) {
        List<ProcessingJob> created = jdbc.query(INSERT_JOB, insertParameters(candidate), ROW_MAPPER);
        if (!created.isEmpty()) {
            return new JobCreation(created.get(0), true);
        }
        ProcessingJob existing = findBySourceItemId(candidate.sourceItemId())
                .orElseThrow(() -> new IllegalStateException(
                        "не удалось прочитать processing_job после вставки"));
        return new JobCreation(existing, false);
    }

    @Override
    @Transactional
    public boolean attachStatusMessage(UUID jobId, long messageId) {
        int updated = jdbc.update(
                ATTACH_STATUS_MESSAGE,
                new MapSqlParameterSource()
                        .addValue("jobId", jobId)
                        .addValue("messageId", messageId));
        return updated == 1;
    }

    @Override
    @Transactional
    public boolean postpone(UUID jobId, Instant nextRunAt) {
        int updated = jdbc.update(
                POSTPONE,
                new MapSqlParameterSource()
                        .addValue("jobId", jobId)
                        .addValue("nextRunAt", toOffsetDateTime(nextRunAt)));
        return updated == 1;
    }

    @Override
    @Transactional(readOnly = true)
    public int countForChatSince(long chatId, Instant since) {
        Integer count = jdbc.queryForObject(
                COUNT_FOR_CHAT_SINCE,
                new MapSqlParameterSource()
                        .addValue("chatId", chatId)
                        .addValue("since", toOffsetDateTime(since)),
                Integer.class);
        return count == null ? 0 : count;
    }

    @Override
    @Transactional
    public Optional<ProcessingJob> claimNext(Instant now, Instant leaseUntil) {
        List<ProcessingJob> claimed = jdbc.query(
                CLAIM_JOB,
                new MapSqlParameterSource()
                        .addValue("now", toOffsetDateTime(now))
                        .addValue("leaseUntil", toOffsetDateTime(leaseUntil)),
                ROW_MAPPER);
        return claimed.stream().findFirst();
    }

    @Override
    @Transactional
    public int reclaimExpiredLeases(Instant now) {
        return jdbc.update(
                RECLAIM_EXPIRED,
                new MapSqlParameterSource("now", toOffsetDateTime(now)));
    }

    @Override
    @Transactional
    public boolean transition(UUID jobId, JobStatus from, JobStatus to) {
        int updated = jdbc.update(
                TRANSITION,
                new MapSqlParameterSource()
                        .addValue("jobId", jobId)
                        .addValue("from", from.name())
                        .addValue("to", to.name()));
        return updated == 1;
    }

    @Override
    @Transactional
    public boolean scheduleRetry(UUID jobId, Instant nextRunAt, String stage, String errorCode) {
        int updated = jdbc.update(
                SCHEDULE_RETRY,
                new MapSqlParameterSource()
                        .addValue("jobId", jobId)
                        .addValue("nextRunAt", toOffsetDateTime(nextRunAt))
                        .addValue("stage", stage)
                        .addValue("errorCode", errorCode));
        return updated == 1;
    }

    @Override
    @Transactional
    public boolean markFailed(UUID jobId, String stage, String errorCode, String errorMessage) {
        int updated = jdbc.update(
                MARK_FAILED,
                new MapSqlParameterSource()
                        .addValue("jobId", jobId)
                        .addValue("stage", stage)
                        .addValue("errorCode", errorCode)
                        .addValue("errorMessage", errorMessage));
        return updated == 1;
    }

    private static MapSqlParameterSource insertParameters(ProcessingJob candidate) {
        Instant createdAt = candidate.createdAt() == null ? Instant.now() : candidate.createdAt();
        Instant updatedAt = candidate.updatedAt() == null ? createdAt : candidate.updatedAt();
        Instant nextRunAt = candidate.nextRunAt() == null ? createdAt : candidate.nextRunAt();
        return new MapSqlParameterSource()
                .addValue("id", candidate.id() == null ? UUID.randomUUID() : candidate.id())
                .addValue("tenantId", candidate.tenantId())
                .addValue("sourceItemId", candidate.sourceItemId())
                .addValue("knowledgeBaseId", candidate.knowledgeBaseId())
                .addValue("failedStage", candidate.failedStage())
                .addValue("attemptCount", candidate.attemptCount())
                .addValue("nextRunAt", toOffsetDateTime(nextRunAt))
                .addValue("leaseUntil", toOffsetDateTime(candidate.leaseUntil()))
                .addValue("statusMessageId", candidate.statusMessageId())
                .addValue("errorCode", candidate.errorCode())
                .addValue("errorMessage", candidate.errorMessage())
                .addValue("confirmedAt", toOffsetDateTime(candidate.confirmedAt()))
                .addValue("createdAt", toOffsetDateTime(createdAt))
                .addValue("updatedAt", toOffsetDateTime(updatedAt));
    }

    private static Long nullableLong(Long value) {
        return value;
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime toOffsetDateTime(Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
