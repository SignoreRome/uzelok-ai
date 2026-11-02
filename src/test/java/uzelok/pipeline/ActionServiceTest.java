package uzelok.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
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
import uzelok.support.InMemoryJobRepository;
import uzelok.support.InMemorySourceItemRepository;

class ActionServiceTest {

    private static final Instant NOW = Instant.parse("2026-05-01T12:00:00Z");

    private final UUID tenantId = UUID.randomUUID();
    private InMemorySourceItemRepository source;
    private InMemoryJobRepository jobs;
    private ActionService service;

    @BeforeEach
    void setUp() {
        source = new InMemorySourceItemRepository();
        jobs = new InMemoryJobRepository(source);
        service = new ActionService(jobs, new JobService(jobs, new JobStateMachine()), 3);
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
