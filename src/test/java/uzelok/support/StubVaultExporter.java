package uzelok.support;

import uzelok.domain.exception.ContractViolation;
import uzelok.domain.model.ExportBundle;
import uzelok.domain.model.KnowledgeBase;
import uzelok.domain.port.VaultExporter;

public final class StubVaultExporter implements VaultExporter {

    private ExportBundle bundle = new ExportBundle(null, java.util.List.of(), 0,
            java.util.List.of());
    private ContractViolation failure;
    private boolean cleaned;
    private KnowledgeBase lastBase;

    public StubVaultExporter bundle(ExportBundle bundle) {
        this.bundle = bundle;
        return this;
    }

    public StubVaultExporter failWith(ContractViolation failure) {
        this.failure = failure;
        return this;
    }

    public boolean cleaned() {
        return cleaned;
    }

    public KnowledgeBase lastBase() {
        return lastBase;
    }

    @Override
    public ExportBundle export(KnowledgeBase base) throws ContractViolation {
        lastBase = base;
        if (failure != null) {
            throw failure;
        }
        return bundle;
    }

    @Override
    public void cleanup(ExportBundle used) {
        cleaned = true;
    }
}
