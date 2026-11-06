package uzelok.pipeline;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import uzelok.domain.model.ArtifactKind;
import uzelok.domain.model.JobArtifact;
import uzelok.domain.model.KnowledgeBase;
import uzelok.domain.model.NoteDraft;
import uzelok.domain.model.PreviewView;
import uzelok.domain.model.ProcessingJob;
import uzelok.domain.model.SourceItem;
import uzelok.domain.port.ArtifactRepository;
import uzelok.domain.port.JobNotifier;
import uzelok.domain.port.JsonCodec;
import uzelok.domain.port.KnowledgeBaseRepository;
import uzelok.domain.port.KnowledgeStore;
import uzelok.domain.port.PreparedNote;
import uzelok.domain.port.SourceItemRepository;

public final class RenderStep {

    private static final int EXCERPT_LENGTH = 100_000;

    private final ArtifactRepository artifacts;
    private final KnowledgeBaseRepository bases;
    private final KnowledgeStore knowledge;
    private final SourceItemRepository source;
    private final JobNotifier notifier;
    private final JsonCodec json;

    public RenderStep(ArtifactRepository artifacts, KnowledgeBaseRepository bases,
                      KnowledgeStore knowledge, SourceItemRepository source,
                      JobNotifier notifier, JsonCodec json) {
        this.artifacts = artifacts;
        this.bases = bases;
        this.knowledge = knowledge;
        this.source = source;
        this.notifier = notifier;
        this.json = json;
    }

    public PreviewView run(ProcessingJob job) {
        int version = job.draftVersion() + 1;
        JobArtifact draftArtifact = artifacts.findLatest(job.id(), ArtifactKind.DRAFT_JSON)
                .orElseThrow(() -> new IllegalStateException(
                        "нет артефакта DRAFT_JSON для задачи " + job.id()));
        NoteDraft draft = json.read(draftArtifact.inlineText(), NoteDraft.class);

        List<String> warnings = artifacts.findLatest(job.id(), ArtifactKind.LINT_REPORT)
                .filter(artifact -> artifact.version() == version)
                .map(artifact -> splitWarnings(artifact.inlineText()))
                .orElse(List.of());

        PreparedNote prepared = artifacts.findLatest(job.id(), ArtifactKind.RENDERED_MD)
                .filter(artifact -> artifact.version() == version)
                .map(artifact -> fromArtifact(artifact, warnings))
                .orElseGet(() -> render(job, draft, version));

        SourceItem item = source.findById(job.sourceItemId())
                .orElseThrow(() -> new IllegalStateException(
                        "источник задачи не найден: " + job.id()));
        PreviewView view = new PreviewView(
                job.id(),
                item.chatId(),
                job.statusMessageId(),
                prepared.relPath(),
                draft.topic(),
                draft.summary(),
                excerpt(prepared.markdown()),
                prepared.markdown(),
                prepared.warnings(),
                job.draftVersion());
        notifier.preview(view);
        return view;
    }

    private PreparedNote render(ProcessingJob job, NoteDraft draft, int version) {
        KnowledgeBase base = bases.findByTenantId(job.tenantId())
                .orElseThrow(() -> new IllegalStateException(
                        "база знаний не найдена для задачи " + job.id()));
        if (!base.id().equals(job.knowledgeBaseId())) {
            throw new IllegalStateException("база знаний задачи не совпадает с базой тенанта");
        }

        PreparedNote prepared = knowledge.prepare(base, job.id().toString(), draft, false);
        artifacts.save(new JobArtifact(
                UUID.randomUUID(),
                job.id(),
                job.tenantId(),
                ArtifactKind.RENDERED_MD,
                version,
                prepared.contentHash(),
                prepared.relPath(),
                prepared.markdown(),
                null,
                null,
                Instant.now()));
        if (!prepared.warnings().isEmpty()) {
            String report = String.join("\n", prepared.warnings());
            artifacts.save(new JobArtifact(
                    UUID.randomUUID(),
                    job.id(),
                    job.tenantId(),
                    ArtifactKind.LINT_REPORT,
                    version,
                    sha256(report),
                    null,
                    report,
                    null,
                    null,
                    Instant.now()));
        }
        return prepared;
    }

    private static PreparedNote fromArtifact(JobArtifact artifact, List<String> warnings) {
        String relPath = artifact.storagePath() == null ? "" : artifact.storagePath();
        String fileName = relPath.isBlank()
                ? ""
                : Path.of(relPath).getFileName().toString();
        return new PreparedNote(null, relPath, fileName,
                artifact.inlineText() == null ? "" : artifact.inlineText(),
                artifact.sha256(), warnings);
    }

    private static List<String> splitWarnings(String report) {
        if (report == null || report.isBlank()) {
            return List.of();
        }
        return Arrays.stream(report.split("\n"))
                .filter(line -> !line.isBlank())
                .toList();
    }

    private static String excerpt(String markdown) {
        String value = markdown == null ? "" : markdown;
        return value.length() <= EXCERPT_LENGTH ? value : value.substring(0, EXCERPT_LENGTH);
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
