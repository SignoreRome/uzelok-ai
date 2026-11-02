package uzelok.pipeline;

import uzelok.domain.exception.IllegalStateTransition;
import uzelok.domain.model.JobStatus;
import uzelok.domain.model.ProcessingJob;
import uzelok.domain.port.JobRepository;

public final class JobService {

    private final JobRepository jobs;
    private final JobStateMachine stateMachine;

    public JobService(JobRepository jobs, JobStateMachine stateMachine) {
        this.jobs = jobs;
        this.stateMachine = stateMachine;
    }

    public ProcessingJob transition(ProcessingJob job, JobStatus to) {
        if (!stateMachine.canTransition(job.status(), to)) {
            throw new IllegalStateTransition("недопустимый переход задачи " + job.id() + ": "
                    + job.status() + " -> " + to);
        }
        if (!jobs.transition(job.id(), job.status(), to)) {
            throw new IllegalStateTransition("задача " + job.id() + " уже сменила статус");
        }
        return new ProcessingJob(
                job.id(),
                job.tenantId(),
                job.sourceItemId(),
                job.knowledgeBaseId(),
                to,
                job.failedStage(),
                job.attemptCount(),
                job.nextRunAt(),
                null,
                job.statusMessageId(),
                job.errorCode(),
                job.errorMessage(),
                job.confirmedAt(),
                job.createdAt(),
                job.updatedAt());
    }
}
