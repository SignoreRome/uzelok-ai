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
        Instant updatedAt,
        int draftVersion,
        boolean forceInbox) {

    public ProcessingJob(
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
        this(id, tenantId, sourceItemId, knowledgeBaseId, status, failedStage, attemptCount,
                nextRunAt, leaseUntil, statusMessageId, errorCode, errorMessage, confirmedAt,
                createdAt, updatedAt, 0, false);
    }
}
