package uzelok.domain.port;

import java.util.List;
import java.util.UUID;
import uzelok.domain.model.SourceAttachment;

public interface AttachmentRepository {

    /**
     * position выделяется атомарно (max+1), повтор при конфликте уникальности — один раз.
     */
    SourceAttachment append(UUID sourceItemId, UUID tenantId, String telegramFileId, String filePath,
                            String mime, long byteSize, String sha256);

    List<SourceAttachment> listBySourceItem(UUID sourceItemId);

    int countBySourceItem(UUID sourceItemId);
}
