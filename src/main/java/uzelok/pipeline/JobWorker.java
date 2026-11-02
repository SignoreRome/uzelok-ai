package uzelok.pipeline;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uzelok.domain.exception.ContractViolation;
import uzelok.domain.exception.LlmCallFailed;
import uzelok.domain.exception.LlmContractViolation;
import uzelok.domain.exception.QuotaExceeded;
import uzelok.domain.model.JobStatus;
import uzelok.domain.model.ProcessingJob;
import uzelok.domain.port.JobNotifier;
import uzelok.domain.port.JobRepository;

public final class JobWorker {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);
    private static final int MAX_ITERATIONS = 10;
    private static final String FAILED_MESSAGE =
            "Не удалось обработать материал. Задача остановлена, попробуйте отправить его ещё раз.";

    public record WorkerReport(int claimed, int advanced, int retried, int failed) {
    }

    private final JobRepository jobs;
    private final JobService state;
    private final ExtractStep extract;
    private final DraftStep draft;
    private final RenderStep render;
    private final WriteStep write;
    private final JobNotifier notifier;
    private final int leaseMinutes;
    private final int maxAttempts;

    public JobWorker(JobRepository jobs, JobService state, ExtractStep extract, DraftStep draft,
                     RenderStep render, WriteStep write, JobNotifier notifier, int leaseMinutes,
                     int maxAttempts) {
        this.jobs = jobs;
        this.state = state;
        this.extract = extract;
        this.draft = draft;
        this.render = render;
        this.write = write;
        this.notifier = notifier;
        this.leaseMinutes = leaseMinutes;
        this.maxAttempts = maxAttempts;
    }

    public WorkerReport runOnce(Instant now) {
        jobs.reclaimExpiredLeases(now);
        int claimed = 0;
        int advanced = 0;
        int retried = 0;
        int failed = 0;
        Instant leaseUntil = now.plus(Duration.ofMinutes(leaseMinutes));

        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            Optional<ProcessingJob> next = jobs.claimNext(now, leaseUntil);
            if (next.isEmpty()) {
                break;
            }
            ProcessingJob job = next.get();
            claimed++;
            try {
                StepOutcome outcome = dispatch(job);
                if (outcome.advanced()) {
                    advanced++;
                }
            } catch (QuotaExceeded exception) {
                fail(job, "budget_exceeded", "дневной бюджет LLM исчерпан");
                failed++;
            } catch (ContractViolation | LlmContractViolation exception) {
                fail(job, "contract_violation", "ответ или материал не прошёл проверку");
                failed++;
            } catch (RuntimeException exception) {
                if (retry(job, now, exception)) {
                    retried++;
                } else {
                    failed++;
                }
            }
        }
        return new WorkerReport(claimed, advanced, retried, failed);
    }

    private StepOutcome dispatch(ProcessingJob job) {
        return switch (job.status()) {
            case RECEIVED -> {
                state.transition(job, JobStatus.EXTRACTING);
                yield new StepOutcome(true, JobStatus.EXTRACTING);
            }
            case EXTRACTING -> {
                extract.run(job);
                state.transition(job, JobStatus.DRAFTING);
                yield new StepOutcome(true, JobStatus.DRAFTING);
            }
            case DRAFTING -> {
                draft.run(job);
                render.run(job);
                state.transition(job, JobStatus.AWAITING_CONFIRM);
                yield new StepOutcome(true, JobStatus.AWAITING_CONFIRM);
            }
            case WRITING -> {
                WriteStep.WriteResult result = write.run(job);
                state.transition(job, JobStatus.DONE);
                notifier.saved(job, result.note().relPath());
                yield new StepOutcome(true, JobStatus.DONE);
            }
            default -> new StepOutcome(false, job.status());
        };
    }

    private boolean retry(ProcessingJob job, Instant now, RuntimeException exception) {
        int attempt = job.attemptCount() + 1;
        String stage = job.status().name();
        String errorCode = errorCode(exception);
        if (attempt >= maxAttempts) {
            fail(job, errorCode, "шаг " + stage + " не удался после " + attempt + " попыток");
            return false;
        }
        long backoffSeconds = Math.min(30L * attempt, 600L);
        jobs.scheduleRetry(job.id(), now.plusSeconds(backoffSeconds), stage, errorCode);
        Throwable root = exception;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        log.warn("job_id={} step={} повтор через {} с (попытка {}) ошибка={}: {} причина={}: {}",
                job.id(), stage, backoffSeconds, attempt,
                exception.getClass().getSimpleName(), exception.getMessage(),
                root.getClass().getSimpleName(), root.getMessage());
        return true;
    }

    private void fail(ProcessingJob job, String errorCode, String errorMessage) {
        String stage = job.status().name();
        jobs.markFailed(job.id(), stage, errorCode, errorMessage);
        notifier.failed(failedJob(job, stage, errorCode, errorMessage), FAILED_MESSAGE);
        log.warn("job_id={} step={} status=FAILED error_code={}", job.id(), stage, errorCode);
    }

    private static String errorCode(RuntimeException exception) {
        if (exception instanceof LlmCallFailed) {
            return "llm_call_failed";
        }
        return "step_failed";
    }

    private static ProcessingJob failedJob(ProcessingJob job, String stage, String errorCode,
                                           String errorMessage) {
        return new ProcessingJob(
                job.id(),
                job.tenantId(),
                job.sourceItemId(),
                job.knowledgeBaseId(),
                JobStatus.FAILED,
                stage,
                job.attemptCount(),
                job.nextRunAt(),
                null,
                job.statusMessageId(),
                errorCode,
                errorMessage,
                job.confirmedAt(),
                job.createdAt(),
                job.updatedAt(),
                job.draftVersion(),
                job.forceInbox());
    }
}
