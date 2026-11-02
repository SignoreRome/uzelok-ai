package uzelok.support;

import java.util.Optional;
import java.util.UUID;
import uzelok.domain.model.KnowledgeBase;
import uzelok.domain.port.KnowledgeBaseRepository;

public final class FixedKnowledgeBaseRepository implements KnowledgeBaseRepository {

    private final KnowledgeBase base;

    public FixedKnowledgeBaseRepository(KnowledgeBase base) {
        this.base = base;
    }

    @Override
    public Optional<KnowledgeBase> findByTenantId(UUID tenantId) {
        return Optional.ofNullable(base);
    }
}
