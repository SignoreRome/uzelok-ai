package uzelok.telegram;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import uzelok.domain.exception.AttachmentTooLarge;
import uzelok.domain.model.BotCommandEntry;
import uzelok.domain.model.InlineKeyboard;
import uzelok.domain.port.TelegramGateway;

public final class TelegramBotGateway implements TelegramGateway {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotGateway.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);

    private final TelegramClient client;
    private final String botToken;
    private final okhttp3.OkHttpClient httpClient;

    public TelegramBotGateway(TelegramClient client, String botToken) {
        this(client, botToken, new okhttp3.OkHttpClient());
    }

    /**
     * Скачивание файлов Bot API идёт мимо библиотеки, поэтому клиенту нужен тот же прокси,
     * что и остальному трафику. Скачиваем OkHttp, а не {@code java.net.http}: JDK по умолчанию
     * запрещает Basic-аутентификацию в CONNECT-туннеле (jdk.http.auth.tunneling.disabledSchemes),
     * и прокси отвечает 407.
     */
    public TelegramBotGateway(TelegramClient client, String botToken, okhttp3.OkHttpClient httpClient) {
        this.client = client;
        this.botToken = botToken;
        this.httpClient = httpClient;
    }

    @Override
    public long sendMessage(long chatId, String text, InlineKeyboard keyboard) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .replyMarkup(toMarkup(keyboard))
                .build();
        try {
            return client.execute(message).getMessageId();
        } catch (org.telegram.telegrambots.meta.exceptions.TelegramApiException exception) {
            throw new TelegramDeliveryException("не удалось отправить сообщение в Telegram", exception);
        }
    }

    @Override
    public void editMessage(long chatId, long messageId, String text, InlineKeyboard keyboard) {
        EditMessageText edit = EditMessageText.builder()
                .chatId(chatId)
                .messageId((int) messageId)
                .text(text)
                .replyMarkup(toMarkup(keyboard))
                .build();
        try {
            client.execute(edit);
        } catch (org.telegram.telegrambots.meta.exceptions.TelegramApiException exception) {
            if (isNotModified(exception)) {
                log.debug("сообщение не изменилось chat_id={} message_id={}", chatId, messageId);
                return;
            }
            throw new TelegramDeliveryException("не удалось изменить сообщение в Telegram", exception);
        }
    }

    @Override
    public void answerCallback(String callbackId, String text) {
        AnswerCallbackQuery answer = AnswerCallbackQuery.builder()
                .callbackQueryId(callbackId)
                .text(text)
                .build();
        try {
            client.execute(answer);
        } catch (org.telegram.telegrambots.meta.exceptions.TelegramApiException exception) {
            log.warn("не удалось ответить на callback query_id={} exception={}",
                    callbackId, exception.getClass().getSimpleName());
        }
    }

    @Override
    public long sendDocument(long chatId, String fileName, byte[] content, String caption) {
        SendDocument document = SendDocument.builder()
                .chatId(chatId)
                .document(new InputFile(new ByteArrayInputStream(content), fileName))
                .caption(caption)
                .build();
        try {
            return client.execute(document).getMessageId();
        } catch (org.telegram.telegrambots.meta.exceptions.TelegramApiException exception) {
            throw new TelegramDeliveryException("не удалось отправить документ в Telegram", exception);
        }
    }

    @Override
    public long sendDocumentFile(long chatId, String fileName, Path file, String caption) {
        SendDocument document = SendDocument.builder()
                .chatId(chatId)
                .document(new InputFile(file.toFile(), fileName))
                .caption(caption)
                .build();
        try {
            return client.execute(document).getMessageId();
        } catch (org.telegram.telegrambots.meta.exceptions.TelegramApiException exception) {
            throw new TelegramDeliveryException("не удалось отправить документ в Telegram", exception);
        }
    }

    @Override
    public void setCommands(List<BotCommandEntry> commands) {
        if (commands == null || commands.isEmpty()) {
            return;
        }
        SetMyCommands method = SetMyCommands.builder()
                .commands(commands.stream()
                        .map(command -> new BotCommand(command.command(), command.description()))
                        .toList())
                .build();
        try {
            client.execute(method);
        } catch (org.telegram.telegrambots.meta.exceptions.TelegramApiException exception) {
            throw new TelegramDeliveryException(
                    "не удалось установить меню команд в Telegram", exception);
        }
    }

    @Override
    public byte[] download(String fileId, long maxBytes) {
        org.telegram.telegrambots.meta.api.objects.File file;
        try {
            file = client.execute(org.telegram.telegrambots.meta.api.methods.GetFile.builder()
                    .fileId(fileId)
                    .build());
        } catch (org.telegram.telegrambots.meta.exceptions.TelegramApiException exception) {
            throw new TelegramDeliveryException("не удалось получить файл из Telegram");
        }

        okhttp3.Request request;
        try {
            request = new okhttp3.Request.Builder()
                    .url(file.getFileUrl(botToken))
                    .get()
                    .build();
        } catch (IllegalArgumentException exception) {
            throw new TelegramDeliveryException("не удалось построить URL файла");
        }

        try (okhttp3.Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new TelegramDeliveryException(
                        "Telegram вернул HTTP " + response.code() + " при скачивании файла");
            }
            okhttp3.ResponseBody body = response.body();
            if (body == null) {
                return new byte[0];
            }
            try (InputStream stream = body.byteStream()) {
                return readLimited(stream, maxBytes);
            }
        } catch (IOException exception) {
            throw new TelegramDeliveryException("не удалось скачать файл из Telegram");
        }
    }

    private static boolean isNotModified(
            org.telegram.telegrambots.meta.exceptions.TelegramApiException exception) {
        String message = exception.getMessage();
        return message != null && message.contains("message is not modified");
    }

    private static byte[] readLimited(InputStream body, long maxBytes) throws IOException {
        long limit = maxBytes + 1;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        while (total < limit) {
            int toRead = (int) Math.min(buffer.length, limit - total);
            int read = body.read(buffer, 0, toRead);
            if (read < 0) {
                break;
            }
            out.write(buffer, 0, read);
            total += read;
        }
        if (total > maxBytes) {
            throw new AttachmentTooLarge("файл больше лимита: " + maxBytes + " байт");
        }
        return out.toByteArray();
    }

    private static InlineKeyboardMarkup toMarkup(InlineKeyboard keyboard) {
        if (keyboard == null || keyboard.rows().isEmpty()) {
            return null;
        }
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (List<uzelok.domain.model.InlineButton> sourceRow : keyboard.rows()) {
            InlineKeyboardRow targetRow = new InlineKeyboardRow();
            for (uzelok.domain.model.InlineButton button : sourceRow) {
                targetRow.add(InlineKeyboardButton.builder()
                        .text(button.text())
                        .callbackData(button.callbackData())
                        .build());
            }
            rows.add(targetRow);
        }
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }
}
