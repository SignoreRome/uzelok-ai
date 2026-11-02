package uzelok.domain.port;

import java.util.Optional;
import java.util.UUID;
import uzelok.domain.model.ArtifactKind;
import uzelok.domain.model.JobArtifact;

public interface ArtifactRepository {

    JobArtifact save(JobArtifact artifact);

    Optional<JobArtifact> findLatest(UUID jobId, ArtifactKind kind);
}
