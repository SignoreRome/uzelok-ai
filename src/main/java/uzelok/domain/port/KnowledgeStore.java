package uzelok.domain.port;

import uzelok.domain.exception.ContractViolation;
import uzelok.domain.model.KnowledgeBase;
import uzelok.domain.model.NoteDraft;

public interface KnowledgeStore {

    PreparedNote prepare(KnowledgeBase base, String jobRef, NoteDraft draft, boolean forceInbox)
            throws ContractViolation;

    WrittenNote write(KnowledgeBase base, PreparedNote note) throws ContractViolation;
}
