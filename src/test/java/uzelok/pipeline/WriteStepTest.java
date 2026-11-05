package uzelok.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import uzelok.config.JacksonJsonCodec;
import uzelok.domain.model.ArtifactKind;
import uzelok.domain.model.JobArtifact;
import uzelok.domain.model.JobStatus;
import uzelok.domain.model.KnowledgeBase;
import uzelok.domain.model.KnowledgeBaseKind;
import uzelok.domain.model.NoteDraft;
import uzelok.domain.model.NoteRecord;
import uzelok.domain.model.NoteType;
import uzelok.domain.model.ProcessingJob;
import uzelok.domain.model.Taxonomy;
import uzelok.domain.port.KnowledgeStore;
import uzelok.domain.port.PreparedNote;
import uzelok.domain.port.WrittenNote;
import uzelok.support.FixedKnowledgeBaseRepository;
import uzelok.support.InMemoryArtifactRepository;
import uzelok.support.InMemoryNoteRepository;
import uzelok.support.RecordingAuditRepository;

class WriteStepTest {

    private static final Instant NOW = Instant.parse("2026-05-01T12:00:00Z");

    @TempDir
    Path vault;

    private final UUID tenantId = UUID.randomUUID();
    private final KnowledgeBase base = new KnowledgeBase(UUID.randomUUID(),
            KnowledgeBaseKind.EXTERNAL_OBSIDIAN, "/vault",
            "40_Knowledge/_meta/taxonomy.yaml", "40_Knowledge/_Inbox");

    private InMemoryArtifactRepository artifacts;
    private InMemoryNoteRepository notes;
    private RecordingAuditRepository audit;
    private FakeStore knowledge;
    private WriteStep step;
    private JacksonJsonCodec codec;

    @BeforeEach
    void setUp() {
        artifacts = new InMemoryArtifactRepository();
        notes = new InMemoryNoteRepository();
        audit = new RecordingAuditRepository();
        knowledge = new FakeStore();
        codec = new JacksonJsonCodec(JsonMapper.builder().build());
        step = new WriteStep(artifacts, new FixedKnowledgeBaseRepository(base), knowledge, notes,
                audit, codec);
    }

    @Test
    void writesNoteAndVersionAndAudits() {
        ProcessingJob job = job(false);

        WriteStep.WriteResult result = step.run(job);

        assertThat(result.note().created()).isTrue();
        assertThat(knowledge.writes).isEqualTo(1);
        assertThat(notes.noteCount()).isEqualTo(1);
        assertThat(notes.versions()).hasSize(1);
        assertThat(audit.actions()).containsExactly("note_saved");
        assertThat(audit.records().getFirst().detail()).containsKey("content_hash");
    }

    @Test
    void repeatedRunDoesNotAddVersionWhenContentIsUnchanged() {
        ProcessingJob job = job(false);
        step.run(job);

        knowledge.created = false;
        step.run(job);

        assertThat(notes.noteCount()).isEqualTo(1);
        assertThat(notes.versions()).hasSize(1);
    }

    @Test
    void versionIsWrittenAfterCrashBetweenFileAndDatabase() {
        ProcessingJob job = job(false);
        knowledge.failAfterWrite = true;

        assertThatThrownBy(() -> step.run(job))
                .isInstanceOf(IllegalStateException.class);
        assertThat(notes.noteCount()).isZero();

        knowledge.failAfterWrite = false;
        knowledge.created = false;
        step.run(job);

        assertThat(notes.noteCount()).isEqualTo(1);
        assertThat(notes.versions()).hasSize(1);
    }

    @Test
    void changedContentAddsAnotherVersion() {
        ProcessingJob job = job(false);
        step.run(job);

        knowledge.created = true;
        knowledge.markdown = "## v2";
        knowledge.hash = "hash-2";
        step.run(job);

        assertThat(notes.versions()).hasSize(2);
    }

    @Test
    void repeatedRunDoesNotCreateSecondFileWhenNoteAlreadyOnDisk() throws IOException {
        ProcessingJob job = job(false);
        KnowledgeBase diskBase = new KnowledgeBase(base.id(), base.kind(), vault.toString(),
                base.taxonomyRelPath(), base.inboxRelPath());
        FakeStore diskStore = new FakeStore();
        diskStore.root = vault;
        WriteStep filesystemStep = new WriteStep(artifacts,
                new FixedKnowledgeBaseRepository(diskBase), diskStore, notes, audit, codec);

        filesystemStep.run(job);
        filesystemStep.run(job);

        Path noteDir = vault.resolve("40_Knowledge/Algorithms/Graphs");
        try (var files = Files.list(noteDir)) {
            assertThat(files.toList()).hasSize(1);
        }
        NoteRecord note = notes.findByJobId(job.id()).orElseThrow();
        assertThat(note.fileName())
                .isEqualTo(Path.of(note.relPath()).getFileName().toString());
        assertThat(diskStore.writes).isEqualTo(1);
    }

    @Test
    void forceInboxIsPassedToKnowledgeStore() {
        knowledge.relPath = "40_Knowledge/_Inbox/Test.md";
        ProcessingJob job = job(true);

        step.run(job);

        assertThat(knowledge.forceInbox).isTrue();
        assertThat(notes.findByFileName("Test.md").orElseThrow().relPath())
                .isEqualTo("40_Knowledge/_Inbox/Test.md");
    }

    private ProcessingJob job(boolean forceInbox) {
        ProcessingJob job = new ProcessingJob(UUID.randomUUID(), tenantId, UUID.randomUUID(),
                base.id(), JobStatus.WRITING, null, 0, NOW, null, null, null, null, null,
                NOW, NOW, 0, forceInbox);
        NoteDraft draft = new NoteDraft("Заметка", NoteType.concept, "Graphs", "summary",
                "## v1", 0.9);
        artifacts.save(new JobArtifact(UUID.randomUUID(), job.id(), tenantId,
                ArtifactKind.DRAFT_JSON, 1, "sha", null, codec.write(draft), null, null, NOW));
        return job;
    }

    private final class FakeStore implements KnowledgeStore {

        private boolean created = true;
        private boolean failAfterWrite;
        private boolean forceInbox;
        private int writes;
        private String relPath = "40_Knowledge/Algorithms/Graphs/Test.md";
        private String markdown = "## v1";
        private String hash = "hash-1";
        private Path root;

        @Override
        public PreparedNote prepare(KnowledgeBase ignored, String jobRef, NoteDraft draft,
                                    boolean forceInbox) {
            this.forceInbox = forceInbox;
            return new PreparedNote(UUID.randomUUID(), relPath, "Test.md", markdown, hash,
                    List.of());
        }

        @Override
        public WrittenNote write(KnowledgeBase ignored, PreparedNote note) {
            writes++;
            if (root != null) {
                Path target = root.resolve(note.relPath());
                try {
                    Files.createDirectories(target.getParent());
                    Files.writeString(target, note.markdown());
                } catch (IOException exception) {
                    throw new IllegalStateException("не удалось записать файл", exception);
                }
            }
            if (failAfterWrite) {
                throw new IllegalStateException("падение после записи файла");
            }
            return new WrittenNote(note.noteId(), note.relPath(), note.contentHash(), created);
        }

        @Override
        public Set<String> existingNoteNames(KnowledgeBase ignored) {
            return Set.of();
        }

        @Override
        public Taxonomy taxonomy(KnowledgeBase ignored) {
            return new Taxonomy(2, List.of());
        }
    }
}
