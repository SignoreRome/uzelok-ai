package uzelok.support;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import uzelok.domain.exception.AttachmentTooLarge;
import uzelok.domain.model.InlineKeyboard;
import uzelok.domain.port.TelegramGateway;

public final class InMemoryTelegramGateway implements TelegramGateway {

    public record SentMessage(long chatId, String text, InlineKeyboard keyboard) {
    }

    public record EditedMessage(long chatId, long messageId, String text, InlineKeyboard keyboard) {
    }

    public record SentDocument(long chatId, String fileName, byte[] content, String caption) {
    }

    private final List<SentMessage> sent = new ArrayList<>();
    private final List<EditedMessage> edited = new ArrayList<>();
    private final List<SentDocument> documents = new ArrayList<>();
    private final Map<String, byte[]> files = new HashMap<>();
    private final List<String> downloadCalls = new ArrayList<>();
    private long nextMessageId = 1000;
    private RuntimeException downloadFailure;

    public InMemoryTelegramGateway putFile(String fileId, byte[] bytes) {
        files.put(fileId, bytes);
        return this;
    }

    public synchronized InMemoryTelegramGateway failDownload(RuntimeException failure) {
        this.downloadFailure = failure;
        return this;
    }

    @Override
    public synchronized long sendMessage(long chatId, String text, InlineKeyboard keyboard) {
        sent.add(new SentMessage(chatId, text, keyboard));
        return nextMessageId++;
    }

    @Override
    public synchronized void editMessage(long chatId, long messageId, String text, InlineKeyboard keyboard) {
        edited.add(new EditedMessage(chatId, messageId, text, keyboard));
    }

    @Override
    public synchronized long sendDocument(long chatId, String fileName, byte[] content,
                                          String caption) {
        documents.add(new SentDocument(chatId, fileName, content.clone(), caption));
        return nextMessageId++;
    }

    @Override
    public synchronized byte[] download(String fileId, long maxBytes) {
        downloadCalls.add(fileId);
        if (downloadFailure != null) {
            throw downloadFailure;
        }
        byte[] bytes = files.get(fileId);
        if (bytes == null) {
            throw new IllegalStateException("в памяти нет файла " + fileId);
        }
        if (bytes.length > maxBytes) {
            throw new AttachmentTooLarge("файл больше лимита: " + bytes.length + " байт");
        }
        return bytes.clone();
    }

    public synchronized List<SentMessage> sent() {
        return List.copyOf(sent);
    }

    public synchronized List<EditedMessage> edited() {
        return List.copyOf(edited);
    }

    public synchronized List<SentDocument> documents() {
        return List.copyOf(documents);
    }

    public synchronized List<String> downloadCalls() {
        return List.copyOf(downloadCalls);
    }

    public synchronized Optional<SentMessage> lastSent() {
        return sent.isEmpty() ? Optional.empty() : Optional.of(sent.get(sent.size() - 1));
    }
}
