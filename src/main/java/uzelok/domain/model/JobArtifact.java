package uzelok.domain.model;

import java.time.Instant;
import java.util.UUID;

public record JobArtifact(
        UUID id,
        UUID jobId,
        UUID tenantId,
        ArtifactKind kind,
        int version,
        String sha256,
        String storagePath,
        String inlineText,
        String model,
        String promptVersion,
        Instant createdAt) {
}
