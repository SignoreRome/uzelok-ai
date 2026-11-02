package uzelok.domain.port;

import java.nio.file.Path;
import uzelok.domain.model.InlineKeyboard;

public interface TelegramGateway {

    /**
     * @return telegram message_id отправленного сообщения
     */
    long sendMessage(long chatId, String text, InlineKeyboard keyboard);

    void editMessage(long chatId, long messageId, String text, InlineKeyboard keyboard);

    byte[] download(String fileId, long maxBytes);

    /**
     * Отправляет документ в чат.
     *
     * @param caption короткий текст, может быть {@code null}
     * @return telegram message_id отправленного сообщения
     */
    default long sendDocument(long chatId, String fileName, byte[] content, String caption) {
        throw new UnsupportedOperationException("sendDocument не поддерживается");
    }

    /**
     * Отправляет файл потоком, не читая его целиком в память.
     *
     * @return telegram message_id отправленного сообщения
     */
    long sendDocumentFile(long chatId, String fileName, Path file, String caption);
}
