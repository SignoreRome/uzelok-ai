package uzelok.domain.port;

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
}
