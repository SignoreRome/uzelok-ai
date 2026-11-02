package uzelok.domain.port;

import java.util.Set;
import uzelok.domain.exception.ContractViolation;
import uzelok.domain.model.KnowledgeBase;
import uzelok.domain.model.NoteDraft;
import uzelok.domain.model.Taxonomy;

public interface KnowledgeStore {

    PreparedNote prepare(KnowledgeBase base, String jobRef, NoteDraft draft, boolean forceInbox)
            throws ContractViolation;

    WrittenNote write(KnowledgeBase base, PreparedNote note) throws ContractViolation;

    /**
     * Стемы имён заметок без {@code .md}, в нижнем регистре.
     */
    Set<String> existingNoteNames(KnowledgeBase base) throws ContractViolation;

    Taxonomy taxonomy(KnowledgeBase base) throws ContractViolation;
}
