package uzelok.pipeline;

import java.util.Optional;
import uzelok.domain.model.ActionOutcome;
import uzelok.domain.model.JobStatus;
import uzelok.domain.model.PendingAction;
import uzelok.domain.model.ProcessingJob;
import uzelok.domain.port.ActionHandler;
import uzelok.domain.port.JobRepository;

public final class ActionService implements ActionHandler {

    private final JobRepository jobs;
    private final JobService state;
    private final int maxAttempts;

    public ActionService(JobRepository jobs, JobService state, int maxAttempts) {
        this.jobs = jobs;
        this.state = state;
        this.maxAttempts = maxAttempts;
    }

    @Override
    public ActionOutcome handle(PendingAction action) {
        return switch (action.type()) {
            case SAVE -> startWriting(action, false, "Записываю заметку…");
            case INBOX -> startWriting(action, true, "Записываю во входящие…");
            case REGENERATE -> regenerate(action);
            case CANCEL -> cancel(action);
            case RETRY -> retry(action);
        };
    }

    private ActionOutcome startWriting(PendingAction action, boolean forceInbox, String message) {
        if (!jobs.startWriting(action.jobId(), forceInbox)) {
            return ActionOutcome.ignored();
        }
        return ActionOutcome.done(message);
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
