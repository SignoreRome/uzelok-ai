package uzelok.domain.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import uzelok.domain.model.JobStatus;
import uzelok.domain.model.ProcessingJob;

public interface JobRepository {

    Optional<ProcessingJob> findBySourceItemId(UUID sourceItemId);

    /**
     * Создаёт задачу по {@code UNIQUE(source_item_id)}.
     */
    JobCreation createIfAbsent(ProcessingJob candidate);

    boolean attachStatusMessage(UUID jobId, long messageId);

    /**
     * Сдвигает {@code next_run_at}, только пока задача в {@code RECEIVED}.
     */
    boolean postpone(UUID jobId, Instant nextRunAt);

    int countForChatSince(long chatId, Instant since);

    /**
     * Атомарный захват: work-статус, next_run_at <= now, lease истёк или пуст.
     * Ставит lease и возвращает задачу.
     */
    Optional<ProcessingJob> claimNext(Instant now, Instant leaseUntil);

    /**
     * Возврат задач с истёкшим lease в очередь: attempt_count + 1, lease_until = NULL.
     */
    int reclaimExpiredLeases(Instant now);

    boolean transition(UUID jobId, JobStatus from, JobStatus to);

    boolean scheduleRetry(UUID jobId, Instant nextRunAt, String stage, String errorCode);

    boolean markFailed(UUID jobId, String stage, String errorCode, String errorMessage);
}
