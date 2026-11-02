package uzelok.domain.model;

import java.time.Instant;
import java.util.UUID;

public record PendingAction(
        String token,
        UUID jobId,
        UUID tenantId,
        PendingActionType type,
        long chatId,
        Long messageId,
        Instant expiresAt,
        Instant usedAt) {
}
