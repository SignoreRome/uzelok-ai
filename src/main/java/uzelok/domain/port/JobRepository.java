package uzelok.domain.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
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
}
