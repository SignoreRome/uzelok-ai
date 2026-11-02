package uzelok.domain.model;

import java.util.UUID;

public record NoteRecord(
        UUID noteId,
        UUID tenantId,
        UUID knowledgeBaseId,
        UUID jobId,
        NoteType type,
        String title,
        String domain,
        String topic,
        String fileName,
        String relPath,
        String contentHash) {
}
