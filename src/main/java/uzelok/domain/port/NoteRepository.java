package uzelok.domain.port;

import java.util.Optional;
import java.util.UUID;
import uzelok.domain.model.NoteRecord;

public interface NoteRepository {

    /**
     * Вставляет или обновляет заметку по уникальности (knowledge_base_id, file_name).
     */
    UUID upsert(NoteRecord note);

    /**
     * Добавляет версию markdown с номером COALESCE(MAX(version_no), 0) + 1.
     */
    void appendVersion(UUID noteId, UUID tenantId, String markdown, String contentHash);

    /**
     * Хеш последней сохранённой версии заметки.
     */
    Optional<String> latestVersionHash(UUID noteId);

    long countByTenant(UUID tenantId);
}
