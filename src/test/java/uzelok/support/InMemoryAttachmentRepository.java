package uzelok.support;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import uzelok.domain.model.SourceAttachment;
import uzelok.domain.port.AttachmentRepository;

public final class InMemoryAttachmentRepository implements AttachmentRepository {

    private final Map<UUID, List<SourceAttachment>> attachments = new LinkedHashMap<>();

    @Override
    public synchronized SourceAttachment append(UUID sourceItemId, UUID tenantId, String telegramFileId,
                                                String filePath, String mime, long byteSize, String sha256) {
        List<SourceAttachment> existing = attachments.computeIfAbsent(sourceItemId, key -> new ArrayList<>());
        int position = existing.size();
        SourceAttachment attachment = new SourceAttachment(
                UUID.randomUUID(),
                sourceItemId,
                tenantId,
                position,
                telegramFileId,
                filePath,
                mime,
                byteSize,
                sha256,
                Instant.now());
        existing.add(attachment);
        return attachment;
    }

    @Override
    public synchronized List<SourceAttachment> listBySourceItem(UUID sourceItemId) {
        return List.copyOf(attachments.getOrDefault(sourceItemId, List.of()));
    }

    @Override
    public synchronized int countBySourceItem(UUID sourceItemId) {
        return attachments.getOrDefault(sourceItemId, List.of()).size();
    }
}
