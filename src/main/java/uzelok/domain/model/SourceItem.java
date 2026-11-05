package uzelok.domain.model;

import java.time.Instant;
import java.util.UUID;

public record SourceItem(
        UUID id,
        UUID tenantId,
        UUID knowledgeBaseId,
        long chatId,
        long telegramMessageId,
        SourceKind kind,
        String mediaGroupId,
        String textBody,
        Instant createdAt,
        String materialHash) {

    public SourceItem(UUID id, UUID tenantId, UUID knowledgeBaseId, long chatId,
                      long telegramMessageId, SourceKind kind, String mediaGroupId,
                      String textBody, Instant createdAt) {
        this(id, tenantId, knowledgeBaseId, chatId, telegramMessageId, kind, mediaGroupId,
                textBody, createdAt, null);
    }
}
