package uzelok.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import uzelok.domain.exception.AttachmentTooLarge;
import uzelok.domain.model.BotCommandEntry;
import uzelok.domain.model.InlineKeyboard;
import uzelok.domain.port.TelegramGateway;

public final class InMemoryTelegramGateway implements TelegramGateway {

    public record SentMessage(long chatId, String text, InlineKeyboard keyboard) {
    }

    public record EditedMessage(long chatId, long messageId, String text, InlineKeyboard keyboard) {
    }

    public record AnsweredCallback(String callbackId, String text) {
    }

    public record SentDocument(long chatId, String fileName, byte[] content, String caption) {
    }

    public record SentFile(long chatId, String fileName, long sizeBytes, String caption) {
    }

    private final List<SentMessage> sent = new ArrayList<>();
    private final List<EditedMessage> edited = new ArrayList<>();
    private final List<AnsweredCallback> answeredCallbacks = new ArrayList<>();
    private final List<SentDocument> documents = new ArrayList<>();
    private final List<SentFile> files = new ArrayList<>();
    private final List<List<BotCommandEntry>> commandsCalls = new ArrayList<>();
    private final Map<String, byte[]> stored = new HashMap<>();
    private final List<String> downloadCalls = new ArrayList<>();
    private long nextMessageId = 1000;
    private RuntimeException downloadFailure;
    private RuntimeException sendFailure;

    public synchronized InMemoryTelegramGateway failSend(RuntimeException failure) {
        this.sendFailure = failure;
        return this;
    }

    public InMemoryTelegramGateway putFile(String fileId, byte[] bytes) {
        stored.put(fileId, bytes);
        return this;
    }

    public synchronized InMemoryTelegramGateway failDownload(RuntimeException failure) {
        this.downloadFailure = failure;
        return this;
    }

    @Override
    public synchronized long sendMessage(long chatId, String text, InlineKeyboard keyboard) {
        if (sendFailure != null) {
            throw sendFailure;
        }
        sent.add(new SentMessage(chatId, text, keyboard));
        return nextMessageId++;
    }

    @Override
    public synchronized void editMessage(long chatId, long messageId, String text, InlineKeyboard keyboard) {
        edited.add(new EditedMessage(chatId, messageId, text, keyboard));
    }

    @Override
    public synchronized void answerCallback(String callbackId, String text) {
        answeredCallbacks.add(new AnsweredCallback(callbackId, text));
    }

    @Override
    public synchronized long sendDocument(long chatId, String fileName, byte[] content,
                                          String caption) {
        documents.add(new SentDocument(chatId, fileName, content.clone(), caption));
        return nextMessageId++;
    }

    @Override
    public synchronized long sendDocumentFile(long chatId, String fileName, Path file,
                                              String caption) {
        long size;
        try {
            size = Files.size(file);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        files.add(new SentFile(chatId, fileName, size, caption));
        return nextMessageId++;
    }

    @Override
    public synchronized void setCommands(List<BotCommandEntry> commands) {
        commandsCalls.add(List.copyOf(commands));
    }

    @Override
    public synchronized byte[] download(String fileId, long maxBytes) {
        downloadCalls.add(fileId);
        if (downloadFailure != null) {
            throw downloadFailure;
        }
        byte[] bytes = stored.get(fileId);
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

    public synchronized List<AnsweredCallback> answeredCallbacks() {
        return List.copyOf(answeredCallbacks);
    }

    public synchronized List<SentDocument> documents() {
        return List.copyOf(documents);
    }

    public synchronized List<SentFile> files() {
        return List.copyOf(files);
    }

    public synchronized List<String> downloadCalls() {
        return List.copyOf(downloadCalls);
    }

    public synchronized List<List<BotCommandEntry>> commandsCalls() {
        return List.copyOf(commandsCalls);
    }

    public synchronized Optional<SentMessage> lastSent() {
        return sent.isEmpty() ? Optional.empty() : Optional.of(sent.get(sent.size() - 1));
    }
}
