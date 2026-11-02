package uzelok.config;

import java.util.Optional;
import uzelok.domain.model.InlineKeyboard;
import uzelok.domain.model.PreviewView;
import uzelok.domain.model.ProcessingJob;
import uzelok.domain.model.SourceItem;
import uzelok.domain.port.JobNotifier;
import uzelok.domain.port.SourceItemRepository;
import uzelok.domain.port.TelegramGateway;

public final class TelegramJobNotifier implements JobNotifier {

    private static final int PREVIEW_LIMIT = 3800;
    private static final String TRUNCATED = "\n…";

    private final TelegramGateway telegram;
    private final SourceItemRepository source;

    public TelegramJobNotifier(TelegramGateway telegram, SourceItemRepository source) {
        this.telegram = telegram;
        this.source = source;
    }

    @Override
    public void preview(PreviewView view) {
        String text = previewText(view);
        if (view.statusMessageId() == null) {
            telegram.sendMessage(view.chatId(), text, InlineKeyboard.empty());
            return;
        }
        telegram.editMessage(view.chatId(), view.statusMessageId(), text, InlineKeyboard.empty());
    }

    @Override
    public void failed(ProcessingJob job, String userMessage) {
        Optional<SourceItem> item = source.findById(job.sourceItemId());
        item.ifPresent(found -> telegram.sendMessage(found.chatId(), userMessage,
                InlineKeyboard.empty()));
    }

    private static String previewText(PreviewView view) {
        StringBuilder header = new StringBuilder();
        header.append("Черновик заметки готов.\n");
        header.append("Путь: ").append(view.relPath()).append('\n');
        if (view.topic() != null && !view.topic().isBlank()) {
            header.append("Тема: ").append(view.topic()).append('\n');
        }
        if (view.summary() != null && !view.summary().isBlank()) {
            header.append(view.summary()).append('\n');
        }

        StringBuilder footer = new StringBuilder();
        if (!view.warnings().isEmpty()) {
            footer.append("\n\nПредупреждения:\n");
            for (String warning : view.warnings()) {
                footer.append("- ").append(warning).append('\n');
            }
        }

        String body = view.bodyExcerpt() == null ? "" : view.bodyExcerpt();
        int available = PREVIEW_LIMIT - header.length() - 1 - footer.length();
        if (body.length() > available) {
            int cut = Math.max(0, available - TRUNCATED.length());
            body = body.substring(0, Math.min(cut, body.length())) + TRUNCATED;
        }
        String text = header.append('\n').append(body).append(footer).toString();
        if (text.length() > PREVIEW_LIMIT) {
            text = text.substring(0, PREVIEW_LIMIT - TRUNCATED.length()) + TRUNCATED;
        }
        return text;
    }
}
