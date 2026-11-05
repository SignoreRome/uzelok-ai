package uzelok.pipeline;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import uzelok.domain.model.ArtifactKind;
import uzelok.domain.model.Extraction;
import uzelok.domain.model.JobArtifact;
import uzelok.domain.model.LlmContext;
import uzelok.domain.model.MaterialHash;
import uzelok.domain.model.ProcessingJob;
import uzelok.domain.model.SourceAttachment;
import uzelok.domain.model.SourceItem;
import uzelok.domain.port.ArtifactRepository;
import uzelok.domain.port.AttachmentRepository;
import uzelok.domain.port.JsonCodec;
import uzelok.domain.port.LlmGateway;
import uzelok.domain.port.SourceItemRepository;

public final class ExtractStep {

    private final SourceItemRepository source;
    private final AttachmentRepository attachments;
    private final ArtifactRepository artifacts;
    private final LlmGateway llm;
    private final JsonCodec json;

    public ExtractStep(SourceItemRepository source, AttachmentRepository attachments,
                       ArtifactRepository artifacts, LlmGateway llm, JsonCodec json) {
        this.source = source;
        this.attachments = attachments;
        this.artifacts = artifacts;
        this.llm = llm;
        this.json = json;
    }

    public JobArtifact run(ProcessingJob job) {
        Optional<JobArtifact> existing = artifacts.findLatest(job.id(), ArtifactKind.EXTRACTION);
        if (existing.isPresent()) {
            return existing.get();
        }

        SourceItem item = source.findById(job.sourceItemId())
                .orElseThrow(() -> new IllegalStateException(
                        "источник задачи не найден: " + job.id()));
        List<SourceAttachment> stored = attachments.listBySourceItem(item.id());
        List<Path> images = new ArrayList<>();
        for (SourceAttachment attachment : stored) {
            if (attachment.mime() != null && attachment.mime().startsWith("image/")
                    && attachment.filePath() != null && !attachment.filePath().isBlank()) {
                images.add(Path.of(attachment.filePath()));
            }
        }
        String text = item.textBody();
        String inputHash = MaterialHash.of(stored, text);
        LlmContext context = new LlmContext(job.tenantId(), job.id(), "EXTRACT", inputHash, 0);
        Extraction extraction = llm.extract(context, List.copyOf(images), text);

        String inline = json.write(extraction);
        JobArtifact artifact = new JobArtifact(
                UUID.randomUUID(),
                job.id(),
                job.tenantId(),
                ArtifactKind.EXTRACTION,
                1,
                MaterialHash.sha256(inline),
                null,
                inline,
                null,
                null,
                Instant.now());
        return artifacts.save(artifact);
    }
}
