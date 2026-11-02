package uzelok.support;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import uzelok.domain.model.JobStatus;
import uzelok.domain.model.ProcessingJob;
import uzelok.domain.model.SourceItem;
import uzelok.domain.port.JobCreation;
import uzelok.domain.port.JobRepository;

public final class InMemoryJobRepository implements JobRepository {

    private final InMemorySourceItemRepository source;
    private final Map<UUID, ProcessingJob> jobs = new LinkedHashMap<>();

    public InMemoryJobRepository(InMemorySourceItemRepository source) {
        this.source = source;
    }

    @Override
    public synchronized Optional<ProcessingJob> findBySourceItemId(UUID sourceItemId) {
        return jobs.values().stream()
                .filter(job -> job.sourceItemId().equals(sourceItemId))
                .findFirst();
    }

    @Override
    public synchronized JobCreation createIfAbsent(ProcessingJob candidate) {
        Optional<ProcessingJob> existing = findBySourceItemId(candidate.sourceItemId());
        if (existing.isPresent()) {
            return new JobCreation(existing.get(), false);
        }
        ProcessingJob stored = new ProcessingJob(
                candidate.id() == null ? UUID.randomUUID() : candidate.id(),
                candidate.tenantId(),
                candidate.sourceItemId(),
                candidate.knowledgeBaseId(),
                candidate.status() == null ? JobStatus.RECEIVED : candidate.status(),
                candidate.failedStage(),
                candidate.attemptCount(),
                candidate.nextRunAt() == null ? Instant.now() : candidate.nextRunAt(),
                candidate.leaseUntil(),
                candidate.statusMessageId(),
                candidate.errorCode(),
                candidate.errorMessage(),
                candidate.confirmedAt(),
                candidate.createdAt() == null ? Instant.now() : candidate.createdAt(),
                candidate.updatedAt() == null ? Instant.now() : candidate.updatedAt());
        jobs.put(stored.id(), stored);
        return new JobCreation(stored, true);
    }

    @Override
    public synchronized boolean attachStatusMessage(UUID jobId, long messageId) {
        ProcessingJob existing = jobs.get(jobId);
        if (existing == null) {
            return false;
        }
        jobs.put(jobId, withStatusMessage(existing, messageId));
        return true;
    }

    @Override
    public synchronized boolean postpone(UUID jobId, Instant nextRunAt) {
        ProcessingJob existing = jobs.get(jobId);
        if (existing == null || existing.status() != JobStatus.RECEIVED) {
            return false;
        }
        jobs.put(jobId, new ProcessingJob(
                existing.id(),
                existing.tenantId(),
                existing.sourceItemId(),
                existing.knowledgeBaseId(),
                existing.status(),
                existing.failedStage(),
                existing.attemptCount(),
                nextRunAt,
                existing.leaseUntil(),
                existing.statusMessageId(),
                existing.errorCode(),
                existing.errorMessage(),
                existing.confirmedAt(),
                existing.createdAt(),
                nextRunAt));
        return true;
    }

    @Override
    public synchronized int countForChatSince(long chatId, Instant since) {
        int count = 0;
        for (ProcessingJob job : jobs.values()) {
            SourceItem item = source.findById(job.sourceItemId()).orElse(null);
            if (item != null && item.chatId() == chatId && !item.createdAt().isBefore(since)) {
                count++;
            }
        }
        return count;
    }

    @Override
    public synchronized java.util.Optional<ProcessingJob> claimNext(Instant now, Instant leaseUntil) {
        java.util.Optional<ProcessingJob> candidate = jobs.values().stream()
                .filter(job -> isWorkStatus(job.status()))
                .filter(job -> !job.nextRunAt().isAfter(now))
                .filter(job -> job.leaseUntil() == null || job.leaseUntil().isBefore(now))
                .min(java.util.Comparator.comparing(ProcessingJob::nextRunAt));
        if (candidate.isEmpty()) {
            return java.util.Optional.empty();
        }
        ProcessingJob job = candidate.get();
        ProcessingJob claimed = copy(job, job.status(), job.attemptCount(), job.nextRunAt(),
                leaseUntil, job.failedStage(), job.errorCode(), job.errorMessage(), now);
        jobs.put(job.id(), claimed);
        return java.util.Optional.of(claimed);
    }

    @Override
    public synchronized int reclaimExpiredLeases(Instant now) {
        int reclaimed = 0;
        for (ProcessingJob job : java.util.List.copyOf(jobs.values())) {
            if (job.leaseUntil() != null && job.leaseUntil().isBefore(now)) {
                jobs.put(job.id(), copy(job, job.status(), job.attemptCount() + 1,
                        job.nextRunAt(), null, job.failedStage(), job.errorCode(),
                        job.errorMessage(), now));
                reclaimed++;
            }
        }
        return reclaimed;
    }

    @Override
    public synchronized boolean transition(UUID jobId, JobStatus from, JobStatus to) {
        ProcessingJob existing = jobs.get(jobId);
        if (existing == null || existing.status() != from) {
            return false;
        }
        jobs.put(jobId, copy(existing, to, existing.attemptCount(), existing.nextRunAt(), null,
                existing.failedStage(), existing.errorCode(), existing.errorMessage(),
                Instant.now()));
        return true;
    }

    @Override
    public synchronized boolean scheduleRetry(UUID jobId, Instant nextRunAt, String stage,
                                              String errorCode) {
        ProcessingJob existing = jobs.get(jobId);
        if (existing == null) {
            return false;
        }
        jobs.put(jobId, copy(existing, existing.status(), existing.attemptCount() + 1, nextRunAt,
                null, stage, errorCode, existing.errorMessage(), Instant.now()));
        return true;
    }

    @Override
    public synchronized boolean markFailed(UUID jobId, String stage, String errorCode,
                                           String errorMessage) {
        ProcessingJob existing = jobs.get(jobId);
        if (existing == null) {
            return false;
        }
        jobs.put(jobId, copy(existing, JobStatus.FAILED, existing.attemptCount(),
                existing.nextRunAt(), null, stage, errorCode, errorMessage, Instant.now()));
        return true;
    }

    public synchronized int size() {
        return jobs.size();
    }

    public synchronized Optional<ProcessingJob> findById(UUID id) {
        return Optional.ofNullable(jobs.get(id));
    }

    public synchronized java.util.List<ProcessingJob> all() {
        return java.util.List.copyOf(jobs.values());
    }

    private static boolean isWorkStatus(JobStatus status) {
        return status == JobStatus.RECEIVED || status == JobStatus.EXTRACTING
                || status == JobStatus.DRAFTING || status == JobStatus.WRITING;
    }

    private static ProcessingJob copy(ProcessingJob job, JobStatus status, int attemptCount,
                                      Instant nextRunAt, Instant leaseUntil, String failedStage,
                                      String errorCode, String errorMessage, Instant updatedAt) {
        return new ProcessingJob(
                job.id(),
                job.tenantId(),
                job.sourceItemId(),
                job.knowledgeBaseId(),
                status,
                failedStage,
                attemptCount,
                nextRunAt,
                leaseUntil,
                job.statusMessageId(),
                errorCode,
                errorMessage,
                job.confirmedAt(),
                job.createdAt(),
                updatedAt);
    }

    private static ProcessingJob withStatusMessage(ProcessingJob job, long messageId) {
        return new ProcessingJob(
                job.id(),
                job.tenantId(),
                job.sourceItemId(),
                job.knowledgeBaseId(),
                job.status(),
                job.failedStage(),
                job.attemptCount(),
                job.nextRunAt(),
                job.leaseUntil(),
                messageId,
                job.errorCode(),
                job.errorMessage(),
                job.confirmedAt(),
                job.createdAt(),
                job.updatedAt());
    }
}
