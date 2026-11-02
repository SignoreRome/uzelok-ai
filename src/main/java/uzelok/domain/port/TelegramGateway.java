package uzelok.domain.port;

import uzelok.domain.model.InlineKeyboard;

public interface TelegramGateway {

    /**
     * @return telegram message_id отправленного сообщения
     */
    long sendMessage(long chatId, String text, InlineKeyboard keyboard);

    void editMessage(long chatId, long messageId, String text, InlineKeyboard keyboard);

    byte[] download(String fileId, long maxBytes);
}
