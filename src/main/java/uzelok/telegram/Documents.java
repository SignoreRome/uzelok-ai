package uzelok.telegram;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import uzelok.domain.port.TelegramGateway;

public final class Documents {

    static final int DOCUMENT_THRESHOLD = 3500;

    private final TelegramGateway telegram;

    public Documents(TelegramGateway telegram) {
        this.telegram = telegram;
    }

    /**
     * Тело больше {@value #DOCUMENT_THRESHOLD} символов отправляем .md-файлом.
     */
    public void sendDraft(long chatId, String fileName, String markdown, String caption) {
        if (markdown == null || markdown.length() <= DOCUMENT_THRESHOLD) {
            return;
        }
        telegram.sendDocument(chatId, documentName(fileName), markdown.getBytes(StandardCharsets.UTF_8),
                caption);
    }

    static String documentName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "note.md";
        }
        Path path = Path.of(fileName);
        Path name = path.getFileName();
        String base = name == null ? "note" : name.toString();
        if (base.toLowerCase(java.util.Locale.ROOT).endsWith(".md")) {
            base = base.substring(0, base.length() - 3);
        }
        return (base.isBlank() ? "note" : base) + ".md";
    }
}
