package uzelok.telegram;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.telegram.telegrambots.meta.api.objects.Document;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.photo.PhotoSize;
import uzelok.config.UzelokProperties;
import uzelok.domain.model.AuditRecord;
import uzelok.domain.model.ChatAccess;
import uzelok.domain.model.InlineKeyboard;
import uzelok.domain.model.JobStatus;
import uzelok.domain.model.KnowledgeBase;
import uzelok.domain.model.MaterialHash;
import uzelok.domain.model.NoteRecord;
import uzelok.domain.model.OnboardingResult;
import uzelok.domain.model.PendingAction;
import uzelok.domain.model.PendingActionType;
import uzelok.domain.model.ProcessingJob;
import uzelok.domain.model.SourceAttachment;
import uzelok.domain.model.SourceItem;
import uzelok.domain.model.SourceKind;
import uzelok.domain.port.AccessRepository;
import uzelok.domain.port.AttachmentRepository;
import uzelok.domain.port.AuditRepository;
import uzelok.domain.port.JobCreation;
import uzelok.domain.port.JobRepository;
import uzelok.domain.port.KnowledgeBaseRepository;
import uzelok.domain.port.NoteRepository;
import uzelok.domain.port.OnboardingHandler;
import uzelok.domain.port.PendingActionRepository;
import uzelok.domain.port.SourceItemCreation;
import uzelok.domain.port.SourceItemRepository;
import uzelok.domain.port.TelegramGateway;

public final class MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(MessageHandler.class);
    private static final long DUPLICATE_TOKEN_TTL_HOURS = 24;
    private static final Duration UNKNOWN_CHAT_REPLY_INTERVAL = Duration.ofMinutes(10);
    private static final int UNKNOWN_CHAT_CACHE_SIZE = 10_000;

    private final Map<Long, Instant> unknownChatReplies =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, Instant> eldest) {
                    return size() > UNKNOWN_CHAT_CACHE_SIZE;
                }
            };

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
    private final OnboardingHandler onboarding;
    private final NoteRepository notes;
    private final PendingActionRepository actions;

    public MessageHandler(AccessRepository access, KnowledgeBaseRepository bases, SourceItemRepository source,
                          AttachmentRepository attachments, JobRepository jobs, AuditRepository audit,
                          TelegramGateway telegram, CommandHandler commands, AttachmentReceiver receiver,
                          Clock clock, UzelokProperties.Limits limits,
                          UzelokProperties.Telegram telegramProps, Messages messages,
                          OnboardingHandler onboarding) {
        this(access, bases, source, attachments, jobs, audit, telegram, commands, receiver, clock,
                limits, telegramProps, messages, onboarding, null, null);
    }

    public MessageHandler(AccessRepository access, KnowledgeBaseRepository bases, SourceItemRepository source,
                          AttachmentRepository attachments, JobRepository jobs, AuditRepository audit,
                          TelegramGateway telegram, CommandHandler commands, AttachmentReceiver receiver,
                          Clock clock, UzelokProperties.Limits limits,
                          UzelokProperties.Telegram telegramProps, Messages messages,
                          OnboardingHandler onboarding, NoteRepository notes,
                          PendingActionRepository actions) {
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
        this.onboarding = onboarding;
        this.notes = notes;
        this.actions = actions;
    }

    public void handle(Update update) {
        handle(update, false);
    }

    /**
     * {@code force} — осознанный повтор материала: дедуп пропускается, создаётся новая задача.
     */
    public void handle(Update update, boolean force) {
        Message message = update.getMessage();
        if (message == null || message.getChatId() == null) {
            return;
        }
        long chatId = message.getChatId();
        Long fromId = message.getFrom() == null ? null : message.getFrom().getId();

        Optional<ChatAccess> found = access.findByChatId(chatId);
        if (found.isEmpty()) {
            // ТЗ UC-1 п.1: неизвестному чату — ответ «доступ не выдан», но не чаще раза в 10 минут,
            // иначе бот превращается в эхо для любого chatId. Сбой доставки не должен уходить
            // в роутер как ошибка обработки апдейта: ответ здесь — best-effort.
            String reply = "throttled";
            if (!unknownChatReplyThrottled(chatId)) {
                reply = "failed";
                try {
                    telegram.sendMessage(chatId, messages.text("access.denied"), null);
                    rememberUnknownChatReply(chatId);
                    reply = "sent";
                } catch (RuntimeException exception) {
                    log.warn("не удалось ответить неизвестному чату chat_id={} exception={}",
                            chatId, exception.getClass().getSimpleName());
                }
            }
            audit.record(new AuditRecord(null, chatId, "auth_denied", null, null,
                    Map.of("reason", "unknown_chat", "reply", reply)));
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
        Optional<KnowledgeBase> base = bases.findByTenantId(chatAccess.tenantId());
        boolean awaiting = base.isEmpty() && text != null && !text.isBlank()
                && onboarding.awaitingPath(chatId);
        if (awaiting && commands.handles(text)) {
            onboarding.reset(chatId);
            awaiting = false;
        }
        if (!awaiting && commands.handle(chatId, text)) {
            return;
        }

        if (base.isEmpty()) {
            if (awaiting && text != null && !text.isBlank()) {
                OnboardingResult result = onboarding.connect(chatId, chatAccess.tenantId(), text);
                telegram.sendMessage(chatId, messages.text(result.messageKey(),
                        result.args().toArray()), null);
                return;
            }
            audit.record(new AuditRecord(chatAccess.tenantId(), chatId, "onboarding_required",
                    "tenant", chatAccess.tenantId(), Map.of("reason", "knowledge_base_missing")));
            telegram.sendMessage(chatId, messages.text("base.prompt"),
                    Keyboards.onboarding(messages));
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

        List<AttachmentReceiver.PreparedAttachment> prepared = new ArrayList<>();
        if (incoming.kind() == SourceKind.PHOTO || incoming.kind() == SourceKind.DOCUMENT) {
            try {
                prepared.add(receiver.prepare(incoming.fileId(), incoming.mime(),
                        incoming.declaredSize(), incoming.fileName(), incoming.kind()));
            } catch (AttachmentRejected rejected) {
                audit.record(new AuditRecord(chatAccess.tenantId(), chatId, "attachment_rejected",
                        "source_item", null, Map.of("reason", rejected.getMessage())));
                telegram.sendMessage(chatId, rejected.getMessage(), null);
                return;
            } catch (TelegramDeliveryException delivery) {
                log.error("не удалось получить вложение chat_id={} exception={}",
                        chatId, delivery.getClass().getSimpleName());
                audit.record(new AuditRecord(chatAccess.tenantId(), chatId, "ingest_failed",
                        null, null, Map.of("reason", delivery.getClass().getSimpleName())));
                telegram.sendMessage(chatId, messages.text("attachment.failed"), null);
                return;
            }
        }

        String materialText = combineText(incoming.textBody(), prepared);
        String materialHash = MaterialHash.ofHashes(
                prepared.stream().map(AttachmentReceiver.PreparedAttachment::sha256).toList(),
                materialText);

        Optional<SourceItem> duplicateMaterial = existing.isEmpty() && !album
                ? source.findByTenantAndMaterialHash(chatAccess.tenantId(), materialHash)
                : Optional.empty();
        boolean materialDuplicate = duplicateMaterial.isPresent();

        if (materialDuplicate && !force) {
            reportDuplicate(chatAccess.tenantId(), chatId, duplicateMaterial.get());
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
                materialText,
                now);
        SourceItemCreation sourceCreation = source.ensure(candidate);
        SourceItem item = sourceCreation.item();

        Set<String> storedFiles = new HashSet<>();
        for (SourceAttachment attachment : attachments.listBySourceItem(item.id())) {
            if (attachment.telegramFileId() != null) {
                storedFiles.add(attachment.telegramFileId());
            }
        }
        for (AttachmentReceiver.PreparedAttachment attachment : prepared) {
            // Повторная доставка апдейта не должна дописывать тот же файл второй раз:
            // иначе хеш материала считается по двум копиям и дедуп перестаёт срабатывать.
            // Сравнение по file_id, а не по sha256: в альбоме могут лежать разные file_id
            // с одинаковыми байтами, и это законный материал.
            if (storedFiles.add(attachment.fileId())) {
                receiver.persist(item, attachment);
            }
        }
        if (item.textBody() == null && materialText != null) {
            source.setTextBody(item.id(), materialText);
            item = source.findById(item.id()).orElse(item);
        }

        if (!(materialDuplicate && force)) {
            List<SourceAttachment> stored = attachments.listBySourceItem(item.id());
            String storedText = item.textBody() == null ? materialText : item.textBody();
            String storedHash = MaterialHash.of(stored, storedText);
            try {
                source.setMaterialHash(item.id(), storedHash);
            } catch (DuplicateKeyException conflict) {
                reportMaterialHashConflict(chatAccess.tenantId(), chatId, item.id(), storedHash);
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

    private synchronized boolean unknownChatReplyThrottled(long chatId) {
        Instant last = unknownChatReplies.get(chatId);
        return last != null
                && Duration.between(last, clock.instant()).compareTo(UNKNOWN_CHAT_REPLY_INTERVAL) <= 0;
    }

    private synchronized void rememberUnknownChatReply(long chatId) {
        unknownChatReplies.put(chatId, clock.instant());
    }

    private static String combineText(String text,
                                      List<AttachmentReceiver.PreparedAttachment> prepared) {
        String result = text;
        for (AttachmentReceiver.PreparedAttachment attachment : prepared) {
            if (attachment.textBody() == null) {
                continue;
            }
            if (result == null || result.isEmpty()) {
                result = attachment.textBody();
            } else {
                result = result + "\n" + attachment.textBody();
            }
        }
        return result;
    }

    private void reportMaterialHashConflict(UUID tenantId, long chatId, UUID itemId,
                                            String materialHash) {
        Optional<SourceItem> existing = source.findByTenantAndMaterialHash(tenantId, materialHash);
        if (existing.isPresent() && !existing.get().id().equals(itemId)) {
            reportDuplicate(tenantId, chatId, existing.get());
            return;
        }
        if (existing.isPresent()) {
            log.warn("material_hash уже принадлежит source_item_id={}", itemId);
            return;
        }
        audit.record(new AuditRecord(tenantId, chatId, "duplicate_material",
                "source_item", null, Map.of("existing_note", "",
                        "material_hash", materialHash)));
        telegram.sendMessage(chatId, messages.text("duplicate.material.pending"), null);
    }

    private void reportDuplicate(UUID tenantId, long chatId, SourceItem existing) {
        Optional<ProcessingJob> existingJob = jobs.findBySourceItemId(existing.id());
        String fileName = existingJob
                .flatMap(job -> notes == null ? Optional.<NoteRecord>empty()
                        : notes.findByJobId(job.id()))
                .map(NoteRecord::fileName)
                .orElse(null);

        audit.record(new AuditRecord(tenantId, chatId, "duplicate_material",
                "source_item", existing.id(), Map.of(
                        "existing_note", fileName == null ? "" : fileName,
                        "source_item_id", existing.id().toString())));

        InlineKeyboard keyboard = InlineKeyboard.empty();
        if (actions != null && existingJob.isPresent() && fileName != null) {
            // Кнопка имеет смысл только для уже записанной заметки: у незавершённой задачи
            // токен гасится invalidateForJob при следующем рендере превью.
            String token = createDuplicateToken(existingJob.get(), chatId);
            keyboard = Keyboards.duplicate(token, messages);
        }
        String reply = fileName == null
                ? messages.text("duplicate.material.pending")
                : messages.text("duplicate.material", fileName);
        telegram.sendMessage(chatId, reply, keyboard);
    }

    private String createDuplicateToken(ProcessingJob job, long chatId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        actions.create(new PendingAction(token, job.id(), job.tenantId(),
                PendingActionType.DUP_REGEN, chatId, job.statusMessageId(),
                clock.instant().plus(Duration.ofHours(DUPLICATE_TOKEN_TTL_HOURS)), null));
        return token;
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

        String caption = message.getCaption();
        List<PhotoSize> photos = message.getPhoto();
        if (photos != null && !photos.isEmpty()) {
            PhotoSize photo = photos.get(photos.size() - 1);
            int size = photo.getFileSize() == null ? 0 : photo.getFileSize();
            return new Incoming(SourceKind.PHOTO, caption, photo.getFileId(), null, size, null);
        }

        Document document = message.getDocument();
        if (document != null && isSupportedDocument(document.getFileName())) {
            long size = document.getFileSize() == null ? 0 : document.getFileSize();
            if (size > telegramProps.maxDocumentBytes()) {
                telegram.sendMessage(chatId, messages.text(
                        "document.too_big", size, telegramProps.maxDocumentBytes()), null);
                return null;
            }
            return new Incoming(SourceKind.DOCUMENT, caption, document.getFileId(),
                    document.getMimeType(), size, document.getFileName());
        }

        telegram.sendMessage(chatId, messages.text("input.unsupported"), null);
        return null;
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
