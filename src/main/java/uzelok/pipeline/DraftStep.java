package uzelok.pipeline;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import uzelok.domain.model.ArtifactKind;
import uzelok.domain.model.Extraction;
import uzelok.domain.model.JobArtifact;
import uzelok.domain.model.KnowledgeBase;
import uzelok.domain.model.LlmContext;
import uzelok.domain.model.NoteDraft;
import uzelok.domain.model.ProcessingJob;
import uzelok.domain.model.Taxonomy;
import uzelok.domain.port.ArtifactRepository;
import uzelok.domain.port.JsonCodec;
import uzelok.domain.port.KnowledgeBaseRepository;
import uzelok.domain.port.KnowledgeStore;
import uzelok.domain.port.LlmGateway;

public final class DraftStep {

    private final ArtifactRepository artifacts;
    private final KnowledgeBaseRepository bases;
    private final KnowledgeStore knowledge;
    private final LlmGateway llm;
    private final JsonCodec json;

    public DraftStep(ArtifactRepository artifacts, KnowledgeBaseRepository bases,
                     KnowledgeStore knowledge, LlmGateway llm, JsonCodec json) {
        this.artifacts = artifacts;
        this.bases = bases;
        this.knowledge = knowledge;
        this.llm = llm;
        this.json = json;
    }

    public NoteDraft run(ProcessingJob job) {
        int draftVersion = job.draftVersion() + 1;
        Optional<JobArtifact> latest = artifacts.findLatest(job.id(), ArtifactKind.DRAFT_JSON);
        if (latest.isPresent() && latest.get().version() == draftVersion) {
            return json.read(latest.get().inlineText(), NoteDraft.class);
        }

        JobArtifact extractionArtifact = artifacts.findLatest(job.id(), ArtifactKind.EXTRACTION)
                .orElseThrow(() -> new IllegalStateException(
                        "нет артефакта EXTRACTION для задачи " + job.id()));
        Extraction extraction = json.read(extractionArtifact.inlineText(), Extraction.class);
        KnowledgeBase base = base(job);
        Taxonomy taxonomy = knowledge.taxonomy(base);
        Set<String> existingNames = knowledge.existingNoteNames(base);

        LlmContext context = new LlmContext(job.tenantId(), job.id(), "DRAFT",
                extractionArtifact.sha256(), draftVersion);
        NoteDraft draft = llm.draft(context, extraction, taxonomy, existingNames);

        String inline = json.write(draft);
        artifacts.save(new JobArtifact(
                UUID.randomUUID(),
                job.id(),
                job.tenantId(),
                ArtifactKind.DRAFT_JSON,
                draftVersion,
                sha256(inline),
                null,
                inline,
                null,
                null,
                Instant.now()));
        return draft;
    }

    private KnowledgeBase base(ProcessingJob job) {
        KnowledgeBase base = bases.findByTenantId(job.tenantId())
                .orElseThrow(() -> new IllegalStateException(
                        "база знаний не найдена для задачи " + job.id()));
        if (!base.id().equals(job.knowledgeBaseId())) {
            throw new IllegalStateException("база знаний задачи не совпадает с базой тенанта");
        }
        return base;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 недоступен", exception);
        }
    }
}
