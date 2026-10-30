package uzelok.domain.model;

import java.time.Instant;
import java.util.UUID;

public record SourceItem(
        UUID id,
        UUID tenantId,
        UUID knowledgeBaseId,
        long chatId,
        long telegramMessageId,
        String kind,
        String mediaGroupId,
        String textBody,
        Instant createdAt) {
}
