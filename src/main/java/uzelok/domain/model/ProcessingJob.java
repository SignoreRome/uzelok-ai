package uzelok.domain.model;

import java.time.Instant;
import java.util.UUID;

public record ProcessingJob(
        UUID id,
        UUID tenantId,
        UUID sourceItemId,
        UUID knowledgeBaseId,
        JobStatus status,
        String failedStage,
        int attemptCount,
        Instant nextRunAt,
        Instant leaseUntil,
        Long statusMessageId,
        String errorCode,
        String errorMessage,
        Instant confirmedAt,
        Instant createdAt,
        Instant updatedAt) {
}
