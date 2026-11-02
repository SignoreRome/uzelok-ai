package uzelok.domain.port;

import java.time.Instant;
import java.util.UUID;
import uzelok.domain.model.TenantStats;

public interface StatsProvider {

    TenantStats forTenant(UUID tenantId, Instant now);
}
