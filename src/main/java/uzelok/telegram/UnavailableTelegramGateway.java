package uzelok.telegram;

import uzelok.domain.model.InlineKeyboard;
import uzelok.domain.port.TelegramGateway;

public final class UnavailableTelegramGateway implements TelegramGateway {

    private static final String MESSAGE = "bot-token не задан";

    @Override
    public long sendMessage(long chatId, String text, InlineKeyboard keyboard) {
        throw new TelegramDeliveryException(MESSAGE);
    }

    @Override
    public void editMessage(long chatId, long messageId, String text, InlineKeyboard keyboard) {
        throw new TelegramDeliveryException(MESSAGE);
    }

    @Override
    public byte[] download(String fileId, long maxBytes) {
        throw new TelegramDeliveryException(MESSAGE);
    }
}
