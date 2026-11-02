package uzelok.support;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import uzelok.domain.model.KnowledgeBase;
import uzelok.domain.port.KnowledgeBaseRepository;

public final class InMemoryKnowledgeBaseRepository implements KnowledgeBaseRepository {

    private final Map<UUID, KnowledgeBase> byTenant = new HashMap<>();
    private int saveCalls;

    @Override
    public synchronized Optional<KnowledgeBase> findByTenantId(UUID tenantId) {
        return Optional.ofNullable(byTenant.get(tenantId));
    }

    @Override
    public synchronized Optional<KnowledgeBase> findByRootPath(String rootPath) {
        return byTenant.values().stream()
                .filter(base -> base.rootPath().equals(rootPath))
                .findFirst();
    }

    @Override
    public synchronized KnowledgeBase save(UUID tenantId, KnowledgeBase base) {
        if (tenantId == null) {
            throw new IllegalArgumentException("у базы не задан tenantId");
        }
        byTenant.put(tenantId, base);
        saveCalls++;
        return base;
    }

    public synchronized int size() {
        return byTenant.size();
    }

    public synchronized int saveCalls() {
        return saveCalls;
    }
}
