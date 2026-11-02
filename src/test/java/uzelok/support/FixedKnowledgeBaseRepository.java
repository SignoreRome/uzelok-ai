package uzelok.support;

import java.util.Optional;
import java.util.UUID;
import uzelok.domain.model.KnowledgeBase;
import uzelok.domain.port.KnowledgeBaseRepository;

public final class FixedKnowledgeBaseRepository implements KnowledgeBaseRepository {

    private KnowledgeBase base;

    public FixedKnowledgeBaseRepository(KnowledgeBase base) {
        this.base = base;
    }

    @Override
    public Optional<KnowledgeBase> findByTenantId(UUID tenantId) {
        return Optional.ofNullable(base);
    }

    @Override
    public Optional<KnowledgeBase> findByRootPath(String rootPath) {
        return base != null && base.rootPath().equals(rootPath)
                ? Optional.of(base)
                : Optional.empty();
    }

    @Override
    public KnowledgeBase save(UUID tenantId, KnowledgeBase updated) {
        this.base = updated;
        return updated;
    }
}
