package uzelok.pipeline;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import uzelok.domain.model.ArtifactKind;
import uzelok.domain.model.AuditRecord;
import uzelok.domain.model.JobArtifact;
import uzelok.domain.model.KnowledgeBase;
import uzelok.domain.model.NoteDraft;
import uzelok.domain.model.NoteRecord;
import uzelok.domain.model.ProcessingJob;
import uzelok.domain.port.ArtifactRepository;
import uzelok.domain.port.AuditRepository;
import uzelok.domain.port.JsonCodec;
import uzelok.domain.port.KnowledgeBaseRepository;
import uzelok.domain.port.KnowledgeStore;
import uzelok.domain.port.NoteRepository;
import uzelok.domain.port.PreparedNote;
import uzelok.domain.port.WrittenNote;

public final class WriteStep {

    public record WriteResult(WrittenNote note, PreparedNote prepared) {
    }

    private final ArtifactRepository artifacts;
    private final KnowledgeBaseRepository bases;
    private final KnowledgeStore knowledge;
    private final NoteRepository notes;
    private final AuditRepository audit;
    private final JsonCodec json;

    public WriteStep(ArtifactRepository artifacts, KnowledgeBaseRepository bases,
                     KnowledgeStore knowledge, NoteRepository notes, AuditRepository audit,
                     JsonCodec json) {
        this.artifacts = artifacts;
        this.bases = bases;
        this.knowledge = knowledge;
        this.notes = notes;
        this.audit = audit;
        this.json = json;
    }

    public WriteResult run(ProcessingJob job) {
        JobArtifact draftArtifact = artifacts.findLatest(job.id(), ArtifactKind.DRAFT_JSON)
                .orElseThrow(() -> new IllegalStateException(
                        "нет артефакта DRAFT_JSON для задачи " + job.id()));
        NoteDraft draft = json.read(draftArtifact.inlineText(), NoteDraft.class);
        KnowledgeBase base = base(job);

        PreparedNote prepared = knowledge.prepare(base, job.id().toString(), draft,
                job.forceInbox());
        WrittenNote written = knowledge.write(base, prepared);

        UUID noteId = notes.upsert(new NoteRecord(
                written.noteId() == null ? prepared.noteId() : written.noteId(),
                job.tenantId(),
                base.id(),
                job.id(),
                draft.type(),
                draft.title(),
                domain(base, written.relPath()),
                draft.topic(),
                prepared.fileName(),
                written.relPath(),
                written.contentHash()));
        String latestHash = notes.latestVersionHash(noteId).orElse(null);
        if (latestHash == null || !latestHash.equals(written.contentHash())) {
            notes.appendVersion(noteId, job.tenantId(), prepared.markdown(),
                    written.contentHash());
        }
        audit.record(new AuditRecord(
                job.tenantId(),
                null,
                "note_saved",
                "note",
                noteId,
                Map.of(
                        "note_id", noteId.toString(),
                        "rel_path", written.relPath() == null ? "" : written.relPath(),
                        "content_hash", written.contentHash() == null ? "" : written.contentHash(),
                        "created", written.created())));

        return new WriteResult(written, prepared);
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

    private static String domain(KnowledgeBase base, String relPath) {
        if (relPath == null || relPath.isBlank()) {
            return null;
        }
        Path knowledgeRoot = knowledgeRoot(base);
        Path target = Path.of(relPath).normalize();
        if (knowledgeRoot == null || !target.startsWith(knowledgeRoot)) {
            return null;
        }
        Path relative = knowledgeRoot.relativize(target);
        return relative.getNameCount() >= 3 ? relative.getName(0).toString() : null;
    }

    private static Path knowledgeRoot(KnowledgeBase base) {
        if (base.taxonomyRelPath() == null || base.taxonomyRelPath().isBlank()) {
            return null;
        }
        Path taxonomy = Path.of(base.taxonomyRelPath()).normalize();
        Path meta = taxonomy.getParent();
        return meta == null ? null : meta.getParent();
    }
}
