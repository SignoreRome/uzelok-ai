package uzelok.telegram;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import uzelok.domain.exception.AttachmentTooLarge;
import uzelok.domain.model.InlineKeyboard;
import uzelok.domain.port.TelegramGateway;

public final class TelegramBotGateway implements TelegramGateway {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);

    private final TelegramClient client;
    private final String botToken;
    private final HttpClient httpClient;

    public TelegramBotGateway(TelegramClient client, String botToken) {
        this(client, botToken, HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .build());
    }

    /**
     * Скачивание файлов Bot API идёт мимо библиотеки, поэтому клиенту нужен тот же прокси,
     * что и остальному трафику: иначе в сетях без прямого доступа к api.telegram.org
     * приём фото и документов падает с TelegramDeliveryException.
     */
    public TelegramBotGateway(TelegramClient client, String botToken, HttpClient httpClient) {
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
            throw new TelegramDeliveryException("не удалось изменить сообщение в Telegram", exception);
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
    public byte[] download(String fileId, long maxBytes) {
        org.telegram.telegrambots.meta.api.objects.File file;
        try {
            file = client.execute(org.telegram.telegrambots.meta.api.methods.GetFile.builder()
                    .fileId(fileId)
                    .build());
        } catch (org.telegram.telegrambots.meta.exceptions.TelegramApiException exception) {
            throw new TelegramDeliveryException("не удалось получить файл из Telegram");
        }

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(file.getFileUrl(botToken)))
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();
        } catch (IllegalArgumentException exception) {
            throw new TelegramDeliveryException("не удалось построить URL файла");
        }

        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new TelegramDeliveryException("скачивание файла из Telegram прервано");
        } catch (IOException exception) {
            throw new TelegramDeliveryException("не удалось скачать файл из Telegram");
        }

        try (InputStream body = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new TelegramDeliveryException(
                        "Telegram вернул HTTP " + response.statusCode() + " при скачивании файла");
            }
            return readLimited(body, maxBytes);
        } catch (IOException exception) {
            throw new TelegramDeliveryException("не удалось прочитать файл из Telegram");
        }
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
