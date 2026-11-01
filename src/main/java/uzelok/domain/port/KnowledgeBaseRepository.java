package uzelok.domain.port;

import java.util.Optional;
import java.util.UUID;
import uzelok.domain.model.KnowledgeBase;

public interface KnowledgeBaseRepository {

    Optional<KnowledgeBase> findByTenantId(UUID tenantId);
}
