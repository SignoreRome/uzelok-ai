package uzelok.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import uzelok.config.JacksonJsonCodec;
import uzelok.domain.exception.LlmCallFailed;
import uzelok.domain.exception.QuotaExceeded;
import uzelok.domain.model.ArtifactKind;
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
import uzelok.support.InMemorySourceItemRepository;
import uzelok.support.RecordingJobNotifier;
import uzelok.support.ScriptedLlmGateway;

class JobWorkerTest {

    private static final Instant NOW = Instant.parse("2026-05-01T12:00:00Z");

    private final UUID tenantId = UUID.randomUUID();
    private final KnowledgeBase base = new KnowledgeBase(UUID.randomUUID(),
            KnowledgeBaseKind.EXTERNAL_OBSIDIAN, "/vault",
            "40_Knowledge/_meta/taxonomy.yaml", "40_Knowledge/_Inbox");

    private InMemorySourceItemRepository source;
    private InMemoryJobRepository jobs;
    private InMemoryArtifactRepository artifacts;
    private ScriptedLlmGateway llm;
    private RecordingJobNotifier notifier;
    private FakeKnowledgeStore knowledge;
    private JobWorker worker;
    private ProcessingJob job;

    @BeforeEach
    void setUp() {
        source = new InMemorySourceItemRepository();
        jobs = new InMemoryJobRepository(source);
        artifacts = new InMemoryArtifactRepository();
        llm = new ScriptedLlmGateway();
        notifier = new RecordingJobNotifier();
        knowledge = new FakeKnowledgeStore();
        worker = worker(3);

        SourceItem item = source.ensure(new SourceItem(
                UUID.randomUUID(), tenantId, base.id(), 42L, 100L, SourceKind.TEXT,
                null, "материал", NOW)).item();
        job = jobs.createIfAbsent(new ProcessingJob(
                UUID.randomUUID(), tenantId, item.id(), base.id(), JobStatus.RECEIVED,
                null, 0, NOW, null, 99L, null, null, null, NOW, NOW)).job();
    }

    @Test
    void runsWholeChainInOnePass() {
        JobWorker.WorkerReport report = worker.runOnce(NOW);

        ProcessingJob processed = jobs.findById(job.id()).orElseThrow();
        assertThat(processed.status()).isEqualTo(JobStatus.AWAITING_CONFIRM);
        assertThat(processed.leaseUntil()).isNull();
        assertThat(report.advanced()).isEqualTo(3);
        assertThat(llm.extractCalls()).isEqualTo(1);
        assertThat(llm.draftCalls()).isEqualTo(1);
        assertThat(artifacts.findLatest(job.id(), ArtifactKind.EXTRACTION)).isPresent();
        assertThat(artifacts.findLatest(job.id(), ArtifactKind.DRAFT_JSON)).isPresent();
        assertThat(artifacts.findLatest(job.id(), ArtifactKind.RENDERED_MD)).isPresent();
        assertThat(notifier.previews()).hasSize(1);
        assertThat(notifier.previews().getFirst().chatId()).isEqualTo(42L);
        assertThat(notifier.previews().getFirst().bodyExcerpt()).contains("markdown");
    }

    @Test
    void secondRunDoesNotCallLlmAgain() {
        worker.runOnce(NOW);
        worker.runOnce(NOW.plusSeconds(5));

        assertThat(llm.extractCalls()).isEqualTo(1);
        assertThat(llm.draftCalls()).isEqualTo(1);
        assertThat(notifier.previews()).hasSize(1);
    }

    @Test
    void failedStepRetriesAndThenMarksJobFailed() {
        llm.failExtract(new LlmCallFailed("сеть недоступна"));

        worker.runOnce(NOW);
        ProcessingJob afterFirst = jobs.findById(job.id()).orElseThrow();
        assertThat(afterFirst.status()).isEqualTo(JobStatus.EXTRACTING);
        assertThat(afterFirst.attemptCount()).isEqualTo(1);
        assertThat(afterFirst.nextRunAt()).isEqualTo(NOW.plusSeconds(30));

        worker.runOnce(NOW.plusSeconds(31));
        ProcessingJob afterSecond = jobs.findById(job.id()).orElseThrow();
        assertThat(afterSecond.attemptCount()).isEqualTo(2);

        worker.runOnce(NOW.plusSeconds(92));
        ProcessingJob failed = jobs.findById(job.id()).orElseThrow();
        assertThat(failed.status()).isEqualTo(JobStatus.FAILED);
        assertThat(failed.errorCode()).isEqualTo("llm_call_failed");
        assertThat(notifier.failures()).hasSize(1);
        assertThat(notifier.failures().getFirst().userMessage()).isNotBlank();
    }

    @Test
    void quotaExceededFailsImmediatelyWithoutRetry() {
        llm.failExtract(new QuotaExceeded("бюджет"));

        JobWorker.WorkerReport report = worker.runOnce(NOW);

        ProcessingJob failed = jobs.findById(job.id()).orElseThrow();
        assertThat(failed.status()).isEqualTo(JobStatus.FAILED);
        assertThat(failed.errorCode()).isEqualTo("budget_exceeded");
        assertThat(report.retried()).isZero();
        assertThat(notifier.failures()).hasSize(1);
    }

    private JobWorker worker(int maxAttempts) {
        JsonMapper mapper = JsonMapper.builder().build();
        JacksonJsonCodec codec = new JacksonJsonCodec(mapper);
        JobStateMachine machine = new JobStateMachine();
        JobService state = new JobService(jobs, machine);
        ExtractStep extract = new ExtractStep(source, new InMemoryAttachmentRepository(),
                artifacts, llm, codec);
        DraftStep draft = new DraftStep(artifacts, new FixedKnowledgeBaseRepository(base),
                knowledge, llm, codec);
        RenderStep render = new RenderStep(artifacts, new FixedKnowledgeBaseRepository(base),
                knowledge, source, notifier, codec);
        return new JobWorker(jobs, state, extract, draft, render, notifier, 10, maxAttempts);
    }

    private static final class FakeKnowledgeStore implements KnowledgeStore {

        @Override
        public PreparedNote prepare(KnowledgeBase base, String jobRef, NoteDraft draft,
                                    boolean forceInbox) {
            return new PreparedNote(UUID.randomUUID(),
                    "40_Knowledge/Algorithms/Graphs/Test.md", "Test.md",
                    "---\ntype: concept\n---\n\n## markdown", "hash",
                    List.of("предупреждение"));
        }

        @Override
        public WrittenNote write(KnowledgeBase base, PreparedNote note) {
            return new WrittenNote(note.noteId(), note.relPath(), note.contentHash(), true);
        }

        @Override
        public Set<String> existingNoteNames(KnowledgeBase base) {
            return Set.of("two_pointers");
        }

        @Override
        public Taxonomy taxonomy(KnowledgeBase base) {
            return new Taxonomy(2, List.of(new Taxonomy.Domain("Algorithms", List.of(),
                    List.of(new Taxonomy.Topic("Graphs", List.of())))));
        }
    }
}
