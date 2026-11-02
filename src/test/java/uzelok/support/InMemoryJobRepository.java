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

    public synchronized int size() {
        return jobs.size();
    }

    public synchronized Optional<ProcessingJob> findById(UUID id) {
        return Optional.ofNullable(jobs.get(id));
    }

    public synchronized java.util.List<ProcessingJob> all() {
        return java.util.List.copyOf(jobs.values());
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
