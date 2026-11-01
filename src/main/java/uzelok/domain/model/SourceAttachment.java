package uzelok.domain.model;

import java.time.Instant;
import java.util.UUID;

public record SourceAttachment(
        UUID id,
        UUID sourceItemId,
        UUID tenantId,
        int position,
        String telegramFileId,
        String filePath,
        String mime,
        long byteSize,
        String sha256,
        Instant createdAt) {
}
