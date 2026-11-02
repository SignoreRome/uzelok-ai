package uzelok.knowledge;

import java.util.Set;
import uzelok.domain.exception.ContractViolation;
import uzelok.domain.model.KnowledgeBase;
import uzelok.domain.model.NoteDraft;
import uzelok.domain.model.Taxonomy;
import uzelok.domain.port.KnowledgeStore;
import uzelok.domain.port.PreparedNote;
import uzelok.domain.port.WrittenNote;

public final class KnowledgeStoreRouter implements KnowledgeStore {

    private final KnowledgeStore external;
    private final KnowledgeStore internal;

    public KnowledgeStoreRouter(KnowledgeStore external, KnowledgeStore internal) {
        this.external = external;
        this.internal = internal;
    }

    @Override
    public PreparedNote prepare(KnowledgeBase base, String jobRef, NoteDraft draft,
                                boolean forceInbox) throws ContractViolation {
        return store(base).prepare(base, jobRef, draft, forceInbox);
    }

    @Override
    public WrittenNote write(KnowledgeBase base, PreparedNote note) throws ContractViolation {
        return store(base).write(base, note);
    }

    @Override
    public Set<String> existingNoteNames(KnowledgeBase base) throws ContractViolation {
        return store(base).existingNoteNames(base);
    }

    @Override
    public Taxonomy taxonomy(KnowledgeBase base) throws ContractViolation {
        return store(base).taxonomy(base);
    }

    private KnowledgeStore store(KnowledgeBase base) {
        return switch (base.kind()) {
            case EXTERNAL_OBSIDIAN -> external;
            case INTERNAL_DIR -> internal;
        };
    }
}
