package uzelok.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uzelok.domain.model.ActionOutcome;
import uzelok.domain.model.JobStatus;
import uzelok.domain.model.PendingAction;
import uzelok.domain.model.PendingActionType;
import uzelok.domain.model.ProcessingJob;
import uzelok.domain.model.SourceItem;
import uzelok.domain.model.SourceKind;
import uzelok.support.InMemoryAttachmentRepository;
import uzelok.support.InMemoryJobRepository;
import uzelok.support.InMemorySourceItemRepository;
import uzelok.support.RecordingAuditRepository;
import uzelok.telegram.Messages;

class ActionServiceTest {

    private static final Instant NOW = Instant.parse("2026-05-01T12:00:00Z");

    private final UUID tenantId = UUID.randomUUID();
    private InMemorySourceItemRepository source;
    private InMemoryAttachmentRepository attachments;
    private InMemoryJobRepository jobs;
    private RecordingAuditRepository audit;
    private ActionService service;

    @BeforeEach
    void setUp() {
        source = new InMemorySourceItemRepository();
        attachments = new InMemoryAttachmentRepository();
        jobs = new InMemoryJobRepository(source);
        audit = new RecordingAuditRepository();
        service = serviceWith(20, 5);
    }

    private ActionService serviceWith(int jobsPerDay, int jobsPerHour) {
        return new ActionService(jobs, new JobService(jobs, new JobStateMachine()), 3,
                source, attachments, Clock.fixed(NOW, ZoneOffset.UTC), jobsPerDay, jobsPerHour,
                audit, new Messages());
    }

    @Test
    void saveStartsWritingWithoutInbox() {
        ProcessingJob job = job(JobStatus.AWAITING_CONFIRM, null, 0);

        ActionOutcome outcome = service.handle(action(job, PendingActionType.SAVE));

        ProcessingJob updated = jobs.find(job.id()).orElseThrow();
        assertThat(updated.status()).isEqualTo(JobStatus.WRITING);
        assertThat(updated.forceInbox()).isFalse();
        assertThat(outcome.handled()).isTrue();
    }

    @Test
    void inboxStartsWritingWithInboxFlag() {
        ProcessingJob job = job(JobStatus.AWAITING_CONFIRM, null, 0);

        service.handle(action(job, PendingActionType.INBOX));

        ProcessingJob updated = jobs.find(job.id()).orElseThrow();
        assertThat(updated.status()).isEqualTo(JobStatus.WRITING);
        assertThat(updated.forceInbox()).isTrue();
    }

    @Test
    void regenerateBumpsVersionAndReturnsToDrafting() {
        ProcessingJob job = job(JobStatus.AWAITING_CONFIRM, null, 0);

        service.handle(action(job, PendingActionType.REGENERATE));

        ProcessingJob updated = jobs.find(job.id()).orElseThrow();
        assertThat(updated.status()).isEqualTo(JobStatus.DRAFTING);
        assertThat(updated.draftVersion()).isEqualTo(1);
    }

    @Test
    void regenerateFailedJobReturnsToDraftingAndClearsErrors() {
        ProcessingJob job = job(JobStatus.FAILED, "WRITING", 2);
        jobs.markFailed(job.id(), "WRITING", "contract_violation", "линтер отклонил заметку");

        ActionOutcome outcome = service.handle(action(job, PendingActionType.REGENERATE));

        ProcessingJob updated = jobs.find(job.id()).orElseThrow();
        assertThat(updated.status()).isEqualTo(JobStatus.DRAFTING);
        assertThat(updated.failedStage()).isNull();
        assertThat(updated.errorCode()).isNull();
        assertThat(updated.errorMessage()).isNull();
        assertThat(updated.attemptCount()).isZero();
        assertThat(updated.draftVersion()).isEqualTo(1);
        assertThat(outcome.handled()).isTrue();
    }

    @Test
    void regenerateDoneJobIsIgnoredAndKeepsState() {
        ProcessingJob job = job(JobStatus.DONE, null, 0);

        ActionOutcome outcome = service.handle(action(job, PendingActionType.REGENERATE));

        ProcessingJob updated = jobs.find(job.id()).orElseThrow();
        assertThat(updated.status()).isEqualTo(JobStatus.DONE);
        assertThat(updated.draftVersion()).isZero();
        assertThat(outcome.handled()).isFalse();
    }

    @Test
    void duplicateRegenerateClonesMaterialAndCreatesNewJob() {
        ProcessingJob job = job(JobStatus.AWAITING_CONFIRM, null, 0);
        SourceItem original = source.findById(job.sourceItemId()).orElseThrow();
        attachments.append(original.id(), tenantId, "file-1", "/tmp/file-1.jpg",
                "image/jpeg", 10, "sha-1");

        ActionOutcome outcome = service.handle(action(job, PendingActionType.DUP_REGEN));

        assertThat(outcome.handled()).isTrue();
        assertThat(source.size()).isEqualTo(2);
        assertThat(jobs.size()).isEqualTo(2);
        ProcessingJob copy = jobs.all().stream()
                .filter(candidate -> !candidate.id().equals(job.id()))
                .findFirst().orElseThrow();
        assertThat(copy.status()).isEqualTo(JobStatus.RECEIVED);
        assertThat(copy.sourceItemId()).isNotEqualTo(original.id());
        assertThat(attachments.countBySourceItem(copy.sourceItemId())).isEqualTo(1);
        assertThat(source.findById(copy.sourceItemId()).orElseThrow().materialHash()).isNull();
        assertThat(outcome.userMessage())
                .isEqualTo(new Messages().text("action.duplicate_regenerate"));
        assertThat(jobs.find(job.id()).orElseThrow().status())
                .isEqualTo(JobStatus.AWAITING_CONFIRM);
    }

    @Test
    void duplicateRegenerateRespectsDailyQuota() {
        service = serviceWith(1, 5);
        ProcessingJob job = job(JobStatus.AWAITING_CONFIRM, null, 0);

        ActionOutcome outcome = service.handle(action(job, PendingActionType.DUP_REGEN));

        assertThat(outcome.userMessage()).isEqualTo(new Messages().text("quota.day"));
        assertThat(source.size()).isEqualTo(1);
        assertThat(jobs.size()).isEqualTo(1);
        assertThat(audit.actions()).contains("quota_exceeded");
        assertThat(audit.records()).anySatisfy(record -> {
            if ("quota_exceeded".equals(record.action())) {
                assertThat(record.detail()).containsEntry("reason", "duplicate_regenerate");
            }
        });
    }

    @Test
    void duplicateRegenerateRespectsHourlyQuota() {
        service = serviceWith(20, 1);
        ProcessingJob job = job(JobStatus.AWAITING_CONFIRM, null, 0);

        ActionOutcome outcome = service.handle(action(job, PendingActionType.DUP_REGEN));

        assertThat(outcome.userMessage()).isEqualTo(new Messages().text("quota.hour"));
        assertThat(source.size()).isEqualTo(1);
        assertThat(jobs.size()).isEqualTo(1);
        assertThat(audit.records()).anySatisfy(record -> {
            if ("quota_exceeded".equals(record.action())) {
                assertThat(record.detail()).containsEntry("reason", "duplicate_regenerate");
            }
        });
    }

    @Test
    void cancelMovesAwaitingConfirmToCancelled() {
        ProcessingJob job = job(JobStatus.AWAITING_CONFIRM, null, 0);

        ActionOutcome outcome = service.handle(action(job, PendingActionType.CANCEL));

        assertThat(jobs.find(job.id()).orElseThrow().status()).isEqualTo(JobStatus.CANCELLED);
        assertThat(outcome.userMessage()).isEqualTo("Отменено.");
    }

    @Test
    void retryReturnsFailedJobToFailedStageAndClearsErrors() {
        ProcessingJob job = job(JobStatus.FAILED, "EXTRACTING", 1);

        ActionOutcome outcome = service.handle(action(job, PendingActionType.RETRY));

        ProcessingJob updated = jobs.find(job.id()).orElseThrow();
        assertThat(updated.status()).isEqualTo(JobStatus.EXTRACTING);
        assertThat(updated.errorCode()).isNull();
        assertThat(updated.errorMessage()).isNull();
        assertThat(outcome.handled()).isTrue();
    }

    @Test
    void retryIsRefusedAfterThreeAttempts() {
        ProcessingJob job = job(JobStatus.FAILED, "EXTRACTING", 3);

        ActionOutcome outcome = service.handle(action(job, PendingActionType.RETRY));

        assertThat(jobs.find(job.id()).orElseThrow().status()).isEqualTo(JobStatus.FAILED);
        assertThat(outcome.userMessage()).isEqualTo("Повтор больше недоступен.");
    }

    @Test
    void retryIncrementsAttemptsAndFourthRetryIsRefused() {
        ProcessingJob job = job(JobStatus.FAILED, "EXTRACTING", 0);

        for (int attempt = 1; attempt <= 3; attempt++) {
            ActionOutcome outcome = service.handle(action(job, PendingActionType.RETRY));

            assertThat(outcome.handled()).isTrue();
            assertThat(jobs.find(job.id()).orElseThrow().attemptCount()).isEqualTo(attempt);
            assertThat(jobs.find(job.id()).orElseThrow().status())
                    .isEqualTo(JobStatus.EXTRACTING);
            jobs.markFailed(job.id(), "EXTRACTING", "step_failed", "ошибка");
        }

        ActionOutcome refused = service.handle(action(job, PendingActionType.RETRY));

        assertThat(jobs.find(job.id()).orElseThrow().status()).isEqualTo(JobStatus.FAILED);
        assertThat(jobs.find(job.id()).orElseThrow().attemptCount()).isEqualTo(3);
        assertThat(refused.userMessage()).isEqualTo("Повтор больше недоступен.");
    }

    @Test
    void cancelOnFailedJobKeepsStatusAndExplains() {
        ProcessingJob job = job(JobStatus.FAILED, "EXTRACTING", 1);

        ActionOutcome outcome = service.handle(action(job, PendingActionType.CANCEL));

        assertThat(jobs.find(job.id()).orElseThrow().status()).isEqualTo(JobStatus.FAILED);
        assertThat(outcome.userMessage()).isEqualTo("Задача уже остановлена.");
    }

    private ProcessingJob job(JobStatus status, String failedStage, int attempts) {
        SourceItem item = source.ensure(new SourceItem(UUID.randomUUID(), tenantId,
                UUID.randomUUID(), 42L, UUID.randomUUID().getMostSignificantBits(),
                SourceKind.TEXT, null, "текст", NOW)).item();
        return jobs.createIfAbsent(new ProcessingJob(UUID.randomUUID(), tenantId, item.id(),
                item.knowledgeBaseId(), status, failedStage, attempts, NOW, null, null, null,
                null, null, NOW, NOW, 0, false)).job();
    }

    private static PendingAction action(ProcessingJob job, PendingActionType type) {
        return new PendingAction("token", job.id(), job.tenantId(), type, 42L, null,
                NOW.plusSeconds(3600), null);
    }
}
