package uzelok.config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import uzelok.domain.model.InlineKeyboard;
import uzelok.domain.model.PendingAction;
import uzelok.domain.model.PendingActionType;
import uzelok.domain.model.PreviewView;
import uzelok.domain.model.ProcessingJob;
import uzelok.domain.model.SourceItem;
import uzelok.domain.port.JobNotifier;
import uzelok.domain.port.JobRepository;
import uzelok.domain.port.PendingActionRepository;
import uzelok.domain.port.SourceItemRepository;
import uzelok.domain.port.TelegramGateway;
import uzelok.telegram.Documents;
import uzelok.telegram.Keyboards;

public final class TelegramJobNotifier implements JobNotifier {

    private static final int PREVIEW_LIMIT = 3800;
    private static final int BODY_THRESHOLD = 3500;
    private static final long TOKEN_TTL_HOURS = 24;
    private static final String TRUNCATED = "\n…";

    private final TelegramGateway telegram;
    private final SourceItemRepository source;
    private final JobRepository jobs;
    private final PendingActionRepository actions;
    private final Clock clock;
    private final Documents documents;

    public TelegramJobNotifier(TelegramGateway telegram, SourceItemRepository source) {
        this(telegram, source, null, null, Clock.systemUTC());
    }

    public TelegramJobNotifier(TelegramGateway telegram, SourceItemRepository source,
                               JobRepository jobs, PendingActionRepository actions, Clock clock) {
        this.telegram = telegram;
        this.source = source;
        this.jobs = jobs;
        this.actions = actions;
        this.clock = clock;
        this.documents = new Documents(telegram);
    }

    @Override
    public void preview(PreviewView view) {
        InlineKeyboard keyboard = keyboard(view);
        String body = view.bodyExcerpt() == null ? "" : view.bodyExcerpt();
        String fullMarkdown = view.fullMarkdown() == null ? body : view.fullMarkdown();
        String text;
        if (actions != null && fullMarkdown.length() > BODY_THRESHOLD) {
            documents.sendDraft(view.chatId(), view.relPath(), fullMarkdown, caption(view));
            text = header(view) + "\nФайл с черновиком отправлен отдельно.\n" + footer(view);
        } else {
            text = previewText(view);
        }
        text = limit(text);
        if (view.statusMessageId() == null) {
            telegram.sendMessage(view.chatId(), text, keyboard);
            return;
        }
        telegram.editMessage(view.chatId(), view.statusMessageId(), text, keyboard);
    }

    @Override
    public void saved(ProcessingJob job, String relPath) {
        Optional<SourceItem> item = source.findById(job.sourceItemId());
        item.ifPresent(found -> telegram.sendMessage(found.chatId(),
                "Готово. Заметка сохранена: " + relPath, InlineKeyboard.empty()));
    }

    @Override
    public void failed(ProcessingJob job, String userMessage) {
        Optional<SourceItem> item = source.findById(job.sourceItemId());
        item.ifPresent(found -> telegram.sendMessage(found.chatId(), userMessage,
                InlineKeyboard.empty()));
    }

    private InlineKeyboard keyboard(PreviewView view) {
        if (actions == null || jobs == null) {
            return InlineKeyboard.empty();
        }
        Optional<ProcessingJob> found = jobs.find(view.jobId());
        if (found.isEmpty()) {
            return InlineKeyboard.empty();
        }
        ProcessingJob job = found.get();
        actions.invalidateForJob(job.id());
        Instant expiresAt = clock.instant().plus(Duration.ofHours(TOKEN_TTL_HOURS));
        return Keyboards.preview(
                createToken(job, PendingActionType.SAVE, view, expiresAt),
                createToken(job, PendingActionType.REGENERATE, view, expiresAt),
                createToken(job, PendingActionType.INBOX, view, expiresAt),
                createToken(job, PendingActionType.CANCEL, view, expiresAt));
    }

    private String createToken(ProcessingJob job, PendingActionType type, PreviewView view,
                               Instant expiresAt) {
        String token = UUID.randomUUID().toString().replace("-", "");
        actions.create(new PendingAction(token, job.id(), job.tenantId(), type, view.chatId(),
                view.statusMessageId(), expiresAt, null));
        return token;
    }

    private static String caption(PreviewView view) {
        StringBuilder caption = new StringBuilder();
        caption.append("Путь: ").append(view.relPath());
        if (view.topic() != null && !view.topic().isBlank()) {
            caption.append("\nТема: ").append(view.topic());
        }
        if (view.summary() != null && !view.summary().isBlank()) {
            caption.append('\n').append(view.summary());
        }
        return caption.length() <= 900 ? caption.toString() : caption.substring(0, 900) + "…";
    }

    private static String previewText(PreviewView view) {
        String body = view.bodyExcerpt() == null ? "" : view.bodyExcerpt();
        int available = PREVIEW_LIMIT - header(view).length() - 1 - footer(view).length();
        if (body.length() > available) {
            int cut = Math.max(0, available - TRUNCATED.length());
            body = body.substring(0, Math.min(cut, body.length())) + TRUNCATED;
        }
        return header(view) + '\n' + body + footer(view);
    }

    private static String header(PreviewView view) {
        StringBuilder header = new StringBuilder();
        header.append("Черновик заметки готов.\n");
        header.append("Путь: ").append(view.relPath()).append('\n');
        if (view.topic() != null && !view.topic().isBlank()) {
            header.append("Тема: ").append(view.topic()).append('\n');
        }
        if (view.summary() != null && !view.summary().isBlank()) {
            header.append(view.summary()).append('\n');
        }
        return header.toString();
    }

    private static String footer(PreviewView view) {
        if (view.warnings().isEmpty()) {
            return "";
        }
        StringBuilder footer = new StringBuilder();
        footer.append("\n\nПредупреждения:\n");
        for (String warning : view.warnings()) {
            footer.append("- ").append(warning).append('\n');
        }
        return footer.toString();
    }

    private static String limit(String text) {
        if (text.length() <= PREVIEW_LIMIT) {
            return text;
        }
        return text.substring(0, PREVIEW_LIMIT - TRUNCATED.length()) + TRUNCATED;
    }
}
