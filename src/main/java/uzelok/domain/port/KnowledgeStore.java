package uzelok.domain.port;

import java.util.List;
import uzelok.domain.exception.ContractViolation;
import uzelok.domain.model.NoteDraft;

public interface KnowledgeStore {

    PreparedNote prepare(NoteDraft draft, boolean forceInbox) throws ContractViolation;

    WrittenNote write(PreparedNote note) throws ContractViolation;
}
