package uzelok.domain.model;

import java.util.Map;
import java.util.UUID;

public record AuditRecord(
        UUID tenantId,
        Long chatId,
        String action,
        String entityType,
        UUID entityId,
        Map<String, Object> detail) {
}
