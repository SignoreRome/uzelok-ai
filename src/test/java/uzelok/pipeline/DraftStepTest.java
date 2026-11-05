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
import uzelok.domain.model.ArtifactKind;
import uzelok.domain.model.Extraction;
import uzelok.domain.model.JobArtifact;
import uzelok.domain.model.JobStatus;
import uzelok.domain.model.KnowledgeBase;
import uzelok.domain.model.KnowledgeBaseKind;
import uzelok.domain.model.NoteDraft;
import uzelok.domain.model.ProcessingJob;
import uzelok.domain.model.Taxonomy;
import uzelok.domain.port.KnowledgeStore;
import uzelok.domain.port.PreparedNote;
import uzelok.domain.port.WrittenNote;
import uzelok.support.FixedKnowledgeBaseRepository;
import uzelok.support.InMemoryArtifactRepository;
import uzelok.support.ScriptedLlmGateway;

class DraftStepTest {

    private static final Instant NOW = Instant.parse("2026-05-01T12:00:00Z");

    private final UUID tenantId = UUID.randomUUID();
    private final KnowledgeBase base = new KnowledgeBase(UUID.randomUUID(),
            KnowledgeBaseKind.EXTERNAL_OBSIDIAN, "/vault",
            "40_Knowledge/_meta/taxonomy.yaml", "40_Knowledge/_Inbox");

    private InMemoryArtifactRepository artifacts;
    private ScriptedLlmGateway llm;
    private DraftStep step;
    private JacksonJsonCodec codec;

    @BeforeEach
    void setUp() {
        artifacts = new InMemoryArtifactRepository();
        llm = new ScriptedLlmGateway();
        codec = new JacksonJsonCodec(JsonMapper.builder().build());
        step = new DraftStep(artifacts, new FixedKnowledgeBaseRepository(base),
                new EmptyStore(), llm, codec);
    }

    @Test
    void draftVersionFromJobProducesNextArtifactAndRequestKey() {
        ProcessingJob job = job(1);
        saveExtraction(job);

        step.run(job);

        JobArtifact artifact = artifacts.findLatest(job.id(), ArtifactKind.DRAFT_JSON)
                .orElseThrow();
        assertThat(artifact.version()).isEqualTo(2);
        assertThat(llm.draftCalls()).isEqualTo(1);
        assertThat(llm.draftContexts().getFirst().draftVersion()).isEqualTo(2);
    }

    @Test
    void existingArtifactForTargetVersionSkipsLlm() {
        ProcessingJob job = job(1);
        saveExtraction(job);
        step.run(job);

        step.run(job);

        assertThat(llm.draftCalls()).isEqualTo(1);
        assertThat(artifacts.count(job.id(), ArtifactKind.DRAFT_JSON)).isEqualTo(1);
    }

    @Test
    void oldDraftArtifactWithoutFileNameIsReadAsNull() {
        ProcessingJob job = job(1);
        String oldDraft = """
                {"title":"Старая заметка","type":"concept","topic":"Graphs",
                 "summary":"кратко","bodyMd":"## Тело","confidence":0.9}
                """;
        artifacts.save(new JobArtifact(UUID.randomUUID(), job.id(), tenantId,
                ArtifactKind.DRAFT_JSON, 2, "sha", null, oldDraft, null, null, NOW));

        NoteDraft draft = step.run(job);

        assertThat(draft.fileName()).isNull();
        assertThat(llm.draftCalls()).isZero();
    }

    private ProcessingJob job(int draftVersion) {
        return new ProcessingJob(UUID.randomUUID(), tenantId, UUID.randomUUID(), base.id(),
                JobStatus.DRAFTING, null, 0, NOW, null, null, null, null, null, NOW, NOW,
                draftVersion, false);
    }

    private void saveExtraction(ProcessingJob job) {
        Extraction extraction = new Extraction("текст", List.of(), List.of(), 0.9);
        artifacts.save(new JobArtifact(UUID.randomUUID(), job.id(), tenantId,
                ArtifactKind.EXTRACTION, 1, "sha", null, codec.write(extraction), null, null, NOW));
    }

    private static final class EmptyStore implements KnowledgeStore {

        @Override
        public PreparedNote prepare(KnowledgeBase ignored, String jobRef, NoteDraft draft,
                                    boolean forceInbox) {
            throw new UnsupportedOperationException();
        }

        @Override
        public WrittenNote write(KnowledgeBase ignored, PreparedNote note) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set<String> existingNoteNames(KnowledgeBase ignored) {
            return Set.of();
        }

        @Override
        public Taxonomy taxonomy(KnowledgeBase ignored) {
            return Taxonomy.free();
        }
    }
}
