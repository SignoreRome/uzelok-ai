package uzelok.domain.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import uzelok.domain.model.JobStatus;
import uzelok.domain.model.ProcessingJob;

public interface JobRepository {

    Optional<ProcessingJob> findBySourceItemId(UUID sourceItemId);

    default Optional<ProcessingJob> find(UUID jobId) {
        throw new UnsupportedOperationException("find не поддерживается");
    }

    /**
     * Последняя задача чата в {@code AWAITING_CONFIRM} или {@code FAILED}.
     */
    default Optional<ProcessingJob> findActiveByChat(long chatId) {
        throw new UnsupportedOperationException("findActiveByChat не поддерживается");
    }

    /**
     * Создаёт задачу по {@code UNIQUE(source_item_id)}.
     */
    JobCreation createIfAbsent(ProcessingJob candidate);

    /**
     * {@code AWAITING_CONFIRM -> WRITING} с флагом {@code force_inbox}.
     */
    default boolean startWriting(UUID jobId, boolean forceInbox) {
        throw new UnsupportedOperationException("startWriting не поддерживается");
    }

    default boolean startRegeneration(UUID jobId) {
        throw new UnsupportedOperationException("startRegeneration не поддерживается");
    }

    boolean attachStatusMessage(UUID jobId, long messageId);

    /**
     * Сдвигает {@code next_run_at}, только пока задача в {@code RECEIVED}.
     */
    boolean postpone(UUID jobId, Instant nextRunAt);

    int countForChatSince(long chatId, Instant since);

    /**
     * Количество задач тенанта, созданных не раньше {@code since}.
     */
    default int countForTenantSince(UUID tenantId, Instant since) {
        throw new UnsupportedOperationException("countForTenantSince не поддерживается");
    }

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

    /**
     * Атомарно возвращает задачу из {@code FAILED} в {@code target}, если лимит попыток
     * не исчерпан.
     */
    default boolean retryFailed(UUID jobId, JobStatus target, int maxAttempts) {
        throw new UnsupportedOperationException("retryFailed не поддерживается");
    }
}
