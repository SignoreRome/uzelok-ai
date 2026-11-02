package uzelok.support;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import uzelok.domain.model.ArtifactKind;
import uzelok.domain.model.JobArtifact;
import uzelok.domain.port.ArtifactRepository;

public final class InMemoryArtifactRepository implements ArtifactRepository {

    private final Map<String, JobArtifact> artifacts = new LinkedHashMap<>();

    @Override
    public synchronized JobArtifact save(JobArtifact artifact) {
        JobArtifact stored = new JobArtifact(
                artifact.id() == null ? UUID.randomUUID() : artifact.id(),
                artifact.jobId(),
                artifact.tenantId(),
                artifact.kind(),
                artifact.version(),
                artifact.sha256(),
                artifact.storagePath(),
                artifact.inlineText(),
                artifact.model(),
                artifact.promptVersion(),
                artifact.createdAt() == null ? Instant.now() : artifact.createdAt());
        artifacts.put(key(stored.jobId(), stored.kind(), stored.version()), stored);
        return stored;
    }

    @Override
    public synchronized Optional<JobArtifact> findLatest(UUID jobId, ArtifactKind kind) {
        return artifacts.values().stream()
                .filter(artifact -> artifact.jobId().equals(jobId))
                .filter(artifact -> artifact.kind() == kind)
                .max(java.util.Comparator.comparingInt(JobArtifact::version));
    }

    public synchronized int count(UUID jobId, ArtifactKind kind) {
        return (int) artifacts.values().stream()
                .filter(artifact -> artifact.jobId().equals(jobId))
                .filter(artifact -> artifact.kind() == kind)
                .count();
    }

    private static String key(UUID jobId, ArtifactKind kind, int version) {
        return jobId + "/" + kind + "/" + version;
    }
}
