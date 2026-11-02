package uzelok.domain.port;

import java.util.Optional;
import java.util.UUID;
import uzelok.domain.model.SourceItem;

public interface SourceItemRepository {

    /**
     * Находит существующий по (chat_id, message_id) или по (chat_id, media_group_id),
     * иначе создаёт.
     */
    SourceItemCreation ensure(SourceItem candidate);

    Optional<SourceItem> findById(UUID id);

    Optional<SourceItem> findByChatAndMessage(long chatId, long telegramMessageId);

    Optional<SourceItem> findByChatAndMediaGroup(long chatId, String mediaGroupId);

    /**
     * Записывает текст документа, только если {@code text_body} ещё не заполнен.
     *
     * @return {@code true}, если строка обновлена
     */
    boolean setTextBody(UUID id, String text);
}
