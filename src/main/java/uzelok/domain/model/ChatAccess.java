package uzelok.domain.model;

import java.util.UUID;

public record ChatAccess(UUID tenantId, UUID appUserId, long telegramUserId, boolean allowed) {
}
