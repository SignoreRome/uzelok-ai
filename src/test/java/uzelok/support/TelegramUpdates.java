package uzelok.support;

import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.photo.PhotoSize;

public final class TelegramUpdates {

    private TelegramUpdates() {
    }

    public static Update text(int updateId, long chatId, long messageId, long fromId, String text) {
        Message message = message(chatId, messageId, fromId);
        message.setText(text);
        return messageUpdate(updateId, message);
    }

    public static Update photo(int updateId, long chatId, long messageId, long fromId,
                               String mediaGroupId, String fileId, int fileSize) {
        return photo(updateId, chatId, messageId, fromId, mediaGroupId, fileId, fileSize, null);
    }

    public static Update photo(int updateId, long chatId, long messageId, long fromId,
                               String mediaGroupId, String fileId, int fileSize, String caption) {
        Message message = message(chatId, messageId, fromId);
        if (mediaGroupId != null) {
            message.setMediaGroupId(mediaGroupId);
        }
        if (caption != null) {
            message.setCaption(caption);
        }
        message.setPhoto(java.util.List.of(PhotoSize.builder()
                .fileId(fileId)
                .fileSize(fileSize)
                .width(10)
                .height(10)
                .build()));
        return messageUpdate(updateId, message);
    }

    public static Update document(int updateId, long chatId, long messageId, long fromId,
                                  String fileId, String fileName, String mime, long fileSize) {
        return document(updateId, chatId, messageId, fromId, fileId, fileName, mime,
                fileSize, null);
    }

    public static Update document(int updateId, long chatId, long messageId, long fromId,
                                  String fileId, String fileName, String mime, long fileSize,
                                  String caption) {
        Message message = message(chatId, messageId, fromId);
        if (caption != null) {
            message.setCaption(caption);
        }
        org.telegram.telegrambots.meta.api.objects.Document document =
                new org.telegram.telegrambots.meta.api.objects.Document();
        document.setFileId(fileId);
        document.setFileName(fileName);
        document.setMimeType(mime);
        document.setFileSize(fileSize);
        message.setDocument(document);
        return messageUpdate(updateId, message);
    }

    public static Update edited(int updateId, long chatId, long messageId, long fromId, String text) {
        Update update = new Update();
        update.setUpdateId(updateId);
        Message message = message(chatId, messageId, fromId);
        message.setText(text);
        update.setEditedMessage(message);
        return update;
    }

    public static Update callback(int updateId, long chatId, long messageId, long fromId, String data) {
        Message message = message(chatId, messageId, fromId);
        CallbackQuery callback = new CallbackQuery();
        callback.setId("callback-" + updateId);
        callback.setFrom(new User(fromId, "user", false));
        callback.setMessage(message);
        callback.setData(data);
        Update update = new Update();
        update.setUpdateId(updateId);
        update.setCallbackQuery(callback);
        return update;
    }

    public static Update other(int updateId) {
        Update update = new Update();
        update.setUpdateId(updateId);
        return update;
    }

    private static Update messageUpdate(int updateId, Message message) {
        Update update = new Update();
        update.setUpdateId(updateId);
        update.setMessage(message);
        return update;
    }

    private static Message message(long chatId, long messageId, long fromId) {
        Message message = new Message();
        message.setMessageId((int) messageId);
        message.setDate(1_700_000_000);
        message.setChat(new Chat(chatId, "private"));
        message.setFrom(new User(fromId, "user", false));
        return message;
    }
}
