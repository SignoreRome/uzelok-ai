package uzelok.telegram;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.objects.Document;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.photo.PhotoSize;
import uzelok.config.UzelokProperties;
import uzelok.domain.model.AuditRecord;
import uzelok.domain.model.ChatAccess;
import uzelok.domain.model.JobStatus;
import uzelok.domain.model.KnowledgeBase;
import uzelok.domain.model.ProcessingJob;
import uzelok.domain.model.SourceItem;
import uzelok.domain.model.SourceKind;
import uzelok.domain.port.AccessRepository;
import uzelok.domain.port.AttachmentRepository;
import uzelok.domain.port.AuditRepository;
import uzelok.domain.port.JobCreation;
import uzelok.domain.port.JobRepository;
import uzelok.domain.port.KnowledgeBaseRepository;
import uzelok.domain.port.SourceItemCreation;
import uzelok.domain.port.SourceItemRepository;
import uzelok.domain.port.TelegramGateway;

public final class MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(MessageHandler.class);

    private final AccessRepository access;
    private final KnowledgeBaseRepository bases;
    private final SourceItemRepository source;
    private final AttachmentRepository attachments;
    private final JobRepository jobs;
    private final AuditRepository audit;
    private final TelegramGateway telegram;
    private final CommandHandler commands;
    private final AttachmentReceiver receiver;
    private final Clock clock;
    private final UzelokProperties.Limits limits;
    private final UzelokProperties.Telegram telegramProps;
    private final Messages messages;

    public MessageHandler(AccessRepository access, KnowledgeBaseRepository bases, SourceItemRepository source,
                          AttachmentRepository attachments, JobRepository jobs, AuditRepository audit,
                          TelegramGateway telegram, CommandHandler commands, AttachmentReceiver receiver,
                          Clock clock, UzelokProperties.Limits limits,
                          UzelokProperties.Telegram telegramProps, Messages messages) {
        this.access = access;
        this.bases = bases;
        this.source = source;
        this.attachments = attachments;
        this.jobs = jobs;
        this.audit = audit;
        this.telegram = telegram;
        this.commands = commands;
        this.receiver = receiver;
        this.clock = clock;
        this.limits = limits;
        this.telegramProps = telegramProps;
        this.messages = messages;
    }

    public void handle(Update update) {
        Message message = update.getMessage();
        if (message == null || message.getChatId() == null) {
            return;
        }
        long chatId = message.getChatId();
        Long fromId = message.getFrom() == null ? null : message.getFrom().getId();

        Optional<ChatAccess> found = access.findByChatId(chatId);
        if (found.isEmpty()) {
            audit.record(new AuditRecord(null, chatId, "auth_denied", null, null,
                    Map.of("reason", "unknown_chat")));
            return;
        }

        ChatAccess chatAccess = found.get();
        if (!chatAccess.allowed()) {
            audit.record(new AuditRecord(chatAccess.tenantId(), chatId, "auth_denied", null, null,
                    Map.of("reason", "not_allowed")));
            telegram.sendMessage(chatId, messages.text("access.denied"), null);
            return;
        }

        if (fromId == null || fromId != chatAccess.telegramUserId()) {
            audit.record(new AuditRecord(chatAccess.tenantId(), chatId, "auth_denied", null, null,
                    Map.of("reason", "user_mismatch")));
            return;
        }

        String text = message.getText();
        if (commands.handle(chatId, text)) {
            return;
        }

        Optional<KnowledgeBase> base = bases.findByTenantId(chatAccess.tenantId());
        if (base.isEmpty()) {
            audit.record(new AuditRecord(chatAccess.tenantId(), chatId, "onboarding_required",
                    "tenant", chatAccess.tenantId(), Map.of("reason", "knowledge_base_missing")));
            telegram.sendMessage(chatId, messages.text("base.none"), null);
            return;
        }

        Instant now = clock.instant();
        String mediaGroupId = message.getMediaGroupId();
        boolean album = mediaGroupId != null && !mediaGroupId.isBlank();
        long telegramMessageId = message.getMessageId() == null ? 0 : message.getMessageId();

        Optional<SourceItem> existing = album
                ? source.findByChatAndMediaGroup(chatId, mediaGroupId)
                : source.findByChatAndMessage(chatId, telegramMessageId);

        if (existing.isEmpty()) {
            if (jobs.countForChatSince(chatId, now.minus(Duration.ofDays(1))) >= limits.jobsPerDay()) {
                audit.record(new AuditRecord(chatAccess.tenantId(), chatId, "quota_exceeded",
                        null, null, Map.of("reason", "jobs_per_day")));
                telegram.sendMessage(chatId, messages.text("quota.day"), null);
                return;
            }
            if (jobs.countForChatSince(chatId, now.minus(Duration.ofHours(1))) >= limits.jobsPerHour()) {
                audit.record(new AuditRecord(chatAccess.tenantId(), chatId, "quota_exceeded",
                        null, null, Map.of("reason", "jobs_per_hour")));
                telegram.sendMessage(chatId, messages.text("quota.hour"), null);
                return;
            }
        }

        Incoming incoming = classify(chatId, message, text);
        if (incoming == null) {
            return;
        }

        SourceItem candidate = new SourceItem(
                UUID.randomUUID(),
                chatAccess.tenantId(),
                base.get().id(),
                chatId,
                telegramMessageId,
                incoming.kind(),
                mediaGroupId,
                incoming.textBody(),
                now);
        SourceItemCreation sourceCreation = source.ensure(candidate);
        SourceItem item = sourceCreation.item();

        if (incoming.kind() == SourceKind.PHOTO || incoming.kind() == SourceKind.DOCUMENT) {
            try {
                AttachmentReceiver.StoredAttachment stored = receiver.receive(
                        item,
                        incoming.fileId(),
                        incoming.mime(),
                        incoming.declaredSize(),
                        incoming.fileName());
                if (incoming.kind() == SourceKind.DOCUMENT) {
                    readDocumentText(item, stored);
                }
            } catch (AttachmentRejected rejected) {
                audit.record(new AuditRecord(chatAccess.tenantId(), chatId, "attachment_rejected",
                        "source_item", item.id(), Map.of("reason", rejected.getMessage())));
                telegram.sendMessage(chatId, rejected.getMessage(), null);
                return;
            } catch (TelegramDeliveryException delivery) {
                log.error("не удалось получить вложение source_item_id={} exception={}",
                        item.id(), delivery.getClass().getSimpleName());
                audit.record(new AuditRecord(chatAccess.tenantId(), chatId, "ingest_failed",
                        "source_item", item.id(),
                        Map.of("reason", delivery.getClass().getSimpleName())));
                telegram.sendMessage(chatId, messages.text("attachment.failed"), null);
                return;
            }
        }

        Instant jobNow = Instant.now(clock);
        Instant nextRunAt = album
                ? jobNow.plusMillis(telegramProps.mediaGroupWindowMs())
                : jobNow;
        ProcessingJob jobCandidate = new ProcessingJob(
                UUID.randomUUID(),
                chatAccess.tenantId(),
                item.id(),
                base.get().id(),
                JobStatus.RECEIVED,
                null,
                0,
                nextRunAt,
                null,
                null,
                null,
                null,
                null,
                jobNow,
                jobNow,
                0,
                false);
        JobCreation creation = jobs.createIfAbsent(jobCandidate);

        if (creation.created()) {
            long statusMessageId = telegram.sendMessage(chatId, messages.text("job.accepted"), null);
            jobs.attachStatusMessage(creation.job().id(), statusMessageId);
            audit.record(new AuditRecord(chatAccess.tenantId(), chatId, "job_created",
                    "processing_job", creation.job().id(),
                    Map.of("source_item_id", item.id().toString())));
        } else if (album && !sourceCreation.created()) {
            audit.record(new AuditRecord(chatAccess.tenantId(), chatId, "album_appended",
                    "processing_job", creation.job().id(),
                    Map.of("source_item_id", item.id().toString())));
        } else {
            audit.record(new AuditRecord(chatAccess.tenantId(), chatId, "duplicate_delivery",
                    "processing_job", creation.job().id(),
                    Map.of("source_item_id", item.id().toString())));
        }

        if (album) {
            jobs.postpone(creation.job().id(),
                    Instant.now(clock).plusMillis(telegramProps.mediaGroupWindowMs()));
        }
    }

    private Incoming classify(long chatId, Message message, String text) {
        if (text != null && !text.isBlank()) {
            if (text.length() > telegramProps.maxTextChars()) {
                telegram.sendMessage(chatId, messages.text(
                        "text.too_long", text.length(), telegramProps.maxTextChars()), null);
                return null;
            }
            return new Incoming(SourceKind.TEXT, text, null, null, 0, null);
        }

        List<PhotoSize> photos = message.getPhoto();
        if (photos != null && !photos.isEmpty()) {
            PhotoSize photo = photos.get(photos.size() - 1);
            int size = photo.getFileSize() == null ? 0 : photo.getFileSize();
            return new Incoming(SourceKind.PHOTO, null, photo.getFileId(), null, size, null);
        }

        Document document = message.getDocument();
        if (document != null && isSupportedDocument(document.getFileName())) {
            long size = document.getFileSize() == null ? 0 : document.getFileSize();
            if (size > telegramProps.maxDocumentBytes()) {
                telegram.sendMessage(chatId, messages.text(
                        "document.too_big", size, telegramProps.maxDocumentBytes()), null);
                return null;
            }
            return new Incoming(SourceKind.DOCUMENT, null, document.getFileId(),
                    document.getMimeType(), size, document.getFileName());
        }

        telegram.sendMessage(chatId, messages.text("input.unsupported"), null);
        return null;
    }

    private void readDocumentText(SourceItem item, AttachmentReceiver.StoredAttachment stored) {
        try {
            String content = Files.readString(stored.path(), StandardCharsets.UTF_8);
            source.setTextBody(item.id(), content);
        } catch (IOException exception) {
            log.warn("не удалось прочитать текст документа source_item_id={} exception={}",
                    item.id(), exception.getClass().getSimpleName());
        }
    }

    private static boolean isSupportedDocument(String fileName) {
        if (fileName == null) {
            return false;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".txt") || lower.endsWith(".md");
    }

    private record Incoming(
            SourceKind kind,
            String textBody,
            String fileId,
            String mime,
            long declaredSize,
            String fileName) {
    }
}
