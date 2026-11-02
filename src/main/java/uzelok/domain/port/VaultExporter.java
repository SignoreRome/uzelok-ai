package uzelok.domain.port;

import uzelok.domain.exception.ContractViolation;
import uzelok.domain.model.ExportBundle;
import uzelok.domain.model.KnowledgeBase;

public interface VaultExporter {

    ExportBundle export(KnowledgeBase base) throws ContractViolation;

    /** Удаляет временные архивы; вызывается всегда, в том числе после ошибки отправки. */
    void cleanup(ExportBundle bundle);
}
