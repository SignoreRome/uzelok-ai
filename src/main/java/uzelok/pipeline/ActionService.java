package uzelok.pipeline;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import uzelok.domain.model.ActionOutcome;
import uzelok.domain.model.AuditRecord;
import uzelok.domain.model.JobStatus;
import uzelok.domain.model.PendingAction;
import uzelok.domain.model.ProcessingJob;
import uzelok.domain.model.SourceAttachment;
import uzelok.domain.model.SourceItem;
import uzelok.domain.port.ActionHandler;
import uzelok.domain.port.AttachmentRepository;
import uzelok.domain.port.AuditRepository;
import uzelok.domain.port.JobRepository;
import uzelok.domain.port.MessageCatalog;
import uzelok.domain.port.SourceItemRepository;

public final class ActionService implements ActionHandler {

    private final JobRepository jobs;
    private final JobService state;
    private final int maxAttempts;
    private final SourceItemRepository source;
    private final AttachmentRepository attachments;
    private final Clock clock;
    private final int jobsPerDay;
    private final int jobsPerHour;
    private final AuditRepository audit;
    private final MessageCatalog messages;

    public ActionService(JobRepository jobs, JobService state, int maxAttempts) {
        this(jobs, state, maxAttempts, null, null, Clock.systemUTC(),
                Integer.MAX_VALUE, Integer.MAX_VALUE, null, (key, args) -> key);
    }

    public ActionService(JobRepository jobs, JobService state, int maxAttempts,
                         SourceItemRepository source, AttachmentRepository attachments, Clock clock) {
        this(jobs, state, maxAttempts, source, attachments, clock,
                Integer.MAX_VALUE, Integer.MAX_VALUE, null, (key, args) -> key);
    }

    public ActionService(JobRepository jobs, JobService state, int maxAttempts,
                         SourceItemRepository source, AttachmentRepository attachments, Clock clock,
                         int jobsPerDay, int jobsPerHour, AuditRepository audit,
                         MessageCatalog messages) {
        this.jobs = jobs;
        this.state = state;
        this.maxAttempts = maxAttempts;
        this.source = source;
        this.attachments = attachments;
        this.clock = clock;
        this.jobsPerDay = jobsPerDay;
        this.jobsPerHour = jobsPerHour;
        this.audit = audit;
        this.messages = messages;
    }

    @Override
    public ActionOutcome handle(PendingAction action) {
        return switch (action.type()) {
            case SAVE -> startWriting(action, false, "Записываю заметку…");
            case INBOX -> startWriting(action, true, "Записываю во входящие…");
            case REGENERATE -> regenerate(action);
            case CANCEL -> cancel(action);
            case RETRY -> retry(action);
            case DUP_REGEN -> duplicateRegenerate(action);
        };
    }

    private ActionOutcome startWriting(PendingAction action, boolean forceInbox, String message) {
        if (!jobs.startWriting(action.jobId(), forceInbox)) {
            return ActionOutcome.ignored();
        }
        return ActionOutcome.done(message);
    }

    /**
     * Осознанный повтор: клонирует материал в новую запись и создаёт новую задачу,
     * не проходя дедуп по material_hash.
     */
    private ActionOutcome duplicateRegenerate(PendingAction action) {
        if (source == null || attachments == null) {
            return ActionOutcome.ignored();
        }
        Optional<ProcessingJob> found = jobs.find(action.jobId());
        if (found.isEmpty()) {
            return ActionOutcome.ignored();
        }
        ProcessingJob original = found.get();
        Optional<SourceItem> originalItem = source.findById(original.sourceItemId());
        if (originalItem.isEmpty()) {
            return ActionOutcome.ignored();
        }

        Instant now = clock.instant();
        SourceItem item = originalItem.get();
        if (jobs.countForChatSince(item.chatId(), now.minus(Duration.ofDays(1))) >= jobsPerDay) {
            return quotaExceeded(original, item, "quota.day");
        }
        if (jobs.countForChatSince(item.chatId(), now.minus(Duration.ofHours(1))) >= jobsPerHour) {
            return quotaExceeded(original, item, "quota.hour");
        }

        // material_hash намеренно null: «Перегенерировать» создаёт новый материал,
        // не участвующий в дедупе; повторная отправка ссылается на первую заметку.
        SourceItem copy = new SourceItem(
                UUID.randomUUID(),
                item.tenantId(),
                item.knowledgeBaseId(),
                item.chatId(),
                UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE,
                item.kind(),
                null,
                item.textBody(),
                now,
                null);
        SourceItem created = source.ensure(copy).item();
        for (SourceAttachment attachment : attachments.listBySourceItem(item.id())) {
            attachments.append(created.id(), created.tenantId(), attachment.telegramFileId(),
                    attachment.filePath(), attachment.mime(), attachment.byteSize(),
                    attachment.sha256());
        }

        ProcessingJob candidate = new ProcessingJob(
                UUID.randomUUID(),
                original.tenantId(),
                created.id(),
                original.knowledgeBaseId(),
                JobStatus.RECEIVED,
                null,
                0,
                now,
                null,
                null,
                null,
                null,
                null,
                now,
                now,
                0,
                false);
        if (!jobs.createIfAbsent(candidate).created()) {
            return ActionOutcome.ignored();
        }
        return ActionOutcome.done(messages.text("action.duplicate_regenerate"));
    }

    private ActionOutcome quotaExceeded(ProcessingJob original, SourceItem item, String messageKey) {
        if (audit != null) {
            audit.record(new AuditRecord(original.tenantId(), item.chatId(), "quota_exceeded",
                    "source_item", item.id(), Map.of("reason", "duplicate_regenerate")));
        }
        return ActionOutcome.done(messages.text(messageKey));
    }

    private ActionOutcome regenerate(PendingAction action) {
        if (!jobs.startRegeneration(action.jobId())) {
            return ActionOutcome.ignored();
        }
        return ActionOutcome.done("Генерирую заново…");
    }

    private ActionOutcome cancel(PendingAction action) {
        Optional<ProcessingJob> found = jobs.find(action.jobId());
        if (found.isEmpty()) {
            return ActionOutcome.ignored();
        }
        ProcessingJob job = found.get();
        if (job.status() == JobStatus.FAILED) {
            return ActionOutcome.done("Задача уже остановлена.");
        }
        if (job.status() != JobStatus.AWAITING_CONFIRM) {
            return ActionOutcome.ignored();
        }
        state.transition(job, JobStatus.CANCELLED);
        return ActionOutcome.done("Отменено.");
    }

    private ActionOutcome retry(PendingAction action) {
        Optional<ProcessingJob> found = jobs.find(action.jobId());
        if (found.isEmpty() || found.get().status() != JobStatus.FAILED) {
            return ActionOutcome.ignored();
        }
        ProcessingJob job = found.get();
        if (!jobs.retryFailed(job.id(), retryTarget(job.failedStage()), maxAttempts)) {
            return ActionOutcome.done("Повтор больше недоступен.");
        }
        return ActionOutcome.done("Повторяю…");
    }

    private static JobStatus retryTarget(String failedStage) {
        if (failedStage == null || failedStage.isBlank()) {
            return JobStatus.RECEIVED;
        }
        try {
            JobStatus target = JobStatus.valueOf(failedStage);
            return switch (target) {
                case RECEIVED, EXTRACTING, DRAFTING, WRITING -> target;
                default -> JobStatus.RECEIVED;
            };
        } catch (IllegalArgumentException exception) {
            return JobStatus.RECEIVED;
        }
    }
}
