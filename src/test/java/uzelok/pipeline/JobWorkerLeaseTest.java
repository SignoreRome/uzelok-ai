package uzelok.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import uzelok.config.JacksonJsonCodec;
import uzelok.domain.model.JobStatus;
import uzelok.domain.model.KnowledgeBase;
import uzelok.domain.model.KnowledgeBaseKind;
import uzelok.domain.model.NoteDraft;
import uzelok.domain.model.ProcessingJob;
import uzelok.domain.model.SourceItem;
import uzelok.domain.model.SourceKind;
import uzelok.domain.model.Taxonomy;
import uzelok.domain.port.KnowledgeStore;
import uzelok.domain.port.PreparedNote;
import uzelok.domain.port.WrittenNote;
import uzelok.support.FixedKnowledgeBaseRepository;
import uzelok.support.InMemoryArtifactRepository;
import uzelok.support.InMemoryAttachmentRepository;
import uzelok.support.InMemoryJobRepository;
import uzelok.support.InMemoryNoteRepository;
import uzelok.support.InMemorySourceItemRepository;
import uzelok.support.RecordingAuditRepository;
import uzelok.support.RecordingJobNotifier;
import uzelok.support.ScriptedLlmGateway;

class JobWorkerLeaseTest {

    @Test
    void expiredLeaseIsReclaimedAndAttemptCountGrows() {
        Instant now = Instant.parse("2026-05-01T12:00:00Z");
        UUID tenantId = UUID.randomUUID();
        KnowledgeBase base = new KnowledgeBase(UUID.randomUUID(),
                KnowledgeBaseKind.EXTERNAL_OBSIDIAN, "/vault",
                "40_Knowledge/_meta/taxonomy.yaml", "40_Knowledge/_Inbox");
        InMemorySourceItemRepository source = new InMemorySourceItemRepository();
        InMemoryJobRepository jobs = new InMemoryJobRepository(source);
        SourceItem item = source.ensure(new SourceItem(UUID.randomUUID(), tenantId, base.id(),
                42L, 100L, SourceKind.TEXT, null, "текст", now)).item();
        ProcessingJob job = jobs.createIfAbsent(new ProcessingJob(UUID.randomUUID(), tenantId,
                item.id(), base.id(), JobStatus.RECEIVED, null, 0, now,
                now.minusSeconds(60), null, null, null, null, now, now, 0, false)).job();

        JobWorker worker = worker(jobs, source, base);

        JobWorker.WorkerReport report = worker.runOnce(now);

        ProcessingJob reclaimed = jobs.findById(job.id()).orElseThrow();
        assertThat(reclaimed.attemptCount()).isEqualTo(1);
        assertThat(reclaimed.status()).isEqualTo(JobStatus.AWAITING_CONFIRM);
        assertThat(reclaimed.leaseUntil()).isNull();
        assertThat(report.claimed()).isGreaterThanOrEqualTo(1);
    }

    private static JobWorker worker(InMemoryJobRepository jobs,
                                    InMemorySourceItemRepository source, KnowledgeBase base) {
        JsonMapper mapper = JsonMapper.builder().build();
        JacksonJsonCodec codec = new JacksonJsonCodec(mapper);
        InMemoryArtifactRepository artifacts = new InMemoryArtifactRepository();
        ScriptedLlmGateway llm = new ScriptedLlmGateway();
        RecordingJobNotifier notifier = new RecordingJobNotifier();
        KnowledgeStore knowledge = new KnowledgeStore() {
            @Override
            public PreparedNote prepare(KnowledgeBase knowledgeBase, String jobRef, NoteDraft draft,
                                        boolean forceInbox) {
                return new PreparedNote(UUID.randomUUID(), "40_Knowledge/Test.md", "Test.md",
                        "## body", "hash", List.of());
            }

            @Override
            public WrittenNote write(KnowledgeBase knowledgeBase, PreparedNote note) {
                return new WrittenNote(note.noteId(), note.relPath(), note.contentHash(), true);
            }

            @Override
            public Set<String> existingNoteNames(KnowledgeBase knowledgeBase) {
                return Set.of();
            }

            @Override
            public Taxonomy taxonomy(KnowledgeBase knowledgeBase) {
                return new Taxonomy(2, List.of(new Taxonomy.Domain("Algorithms", List.of(),
                        List.of(new Taxonomy.Topic("Graphs", List.of())))));
            }
        };
        FixedKnowledgeBaseRepository bases = new FixedKnowledgeBaseRepository(base);
        JobService state = new JobService(jobs, new JobStateMachine());
        WriteStep write = new WriteStep(artifacts, bases, knowledge, new InMemoryNoteRepository(),
                new RecordingAuditRepository(), codec);
        return new JobWorker(jobs, state,
                new ExtractStep(source, new InMemoryAttachmentRepository(), artifacts, llm, codec),
                new DraftStep(artifacts, bases, knowledge, llm, codec),
                new RenderStep(artifacts, bases, knowledge, source, notifier, codec),
                write, notifier, 10, 3);
    }
}
