package uzelok.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uzelok.domain.model.KnowledgeBase;
import uzelok.domain.model.KnowledgeBaseKind;
import uzelok.domain.model.NoteDraft;
import uzelok.domain.model.NoteType;
import uzelok.domain.port.PreparedNote;
import uzelok.domain.port.WrittenNote;

class InternalDirStoreTest {

    private static final String JOB_REF = "12345678-aaaa-bbbb-cccc-dddddddddddd";

    @TempDir
    Path root;

    private Path vaultRoot;
    private KnowledgeBase base;
    private InternalDirStore store;

    @BeforeEach
    void setUp() {
        vaultRoot = new TemplateVault(root).create(root.resolve("internal"));
        base = new KnowledgeBase(
                UUID.randomUUID(),
                KnowledgeBaseKind.INTERNAL_DIR,
                vaultRoot.toString(),
                VaultFixture.TAXONOMY_REL_PATH,
                VaultFixture.INBOX_REL_PATH);
        store = new InternalDirStore(
                VaultFixture.options(),
                new ContractValidator(),
                new NameUniqueness(new GitIndex()),
                new FileNamePolicy(60),
                new ContentSanitizer(),
                new LinterRunner("", 60),
                new VaultLockRegistry(),
                VaultFixture.FIXED_CLOCK);
    }

    @Test
    void prepareAndWriteCreatesNoteInTopicWithoutGit() throws IOException {
        PreparedNote prepared = store.prepare(base, JOB_REF, draft("Internal Store Test"), false);

        assertThat(prepared.relPath())
                .startsWith("40_Knowledge/Algorithms/Two_Pointers/")
                .endsWith(".md");
        assertThat(vaultRoot.resolve(".git")).doesNotExist();

        WrittenNote written = store.write(base, prepared);

        Path file = vaultRoot.resolve(written.relPath());
        assertThat(written.created()).isTrue();
        assertThat(file).isRegularFile();
        assertThat(Files.readString(file)).isEqualTo(prepared.markdown());
    }

    @Test
    void repeatedWriteOfSamePreparedNoteCreatesNoSecondFile() throws IOException {
        PreparedNote prepared = store.prepare(base, JOB_REF, draft("Repeated Internal"), false);
        store.write(base, prepared);

        WrittenNote second = store.write(base, prepared);

        assertThat(second.created()).isFalse();
        try (var files = Files.list(vaultRoot.resolve("40_Knowledge/Algorithms/Two_Pointers"))) {
            assertThat(files.count()).isEqualTo(1);
        }
    }

    @Test
    void existingNoteNamesSeesWrittenNoteWithoutGit() {
        PreparedNote prepared = store.prepare(base, JOB_REF, draft("Visible Internal"), false);
        store.write(base, prepared);

        assertThat(store.existingNoteNames(base)).contains("visible_internal");
    }

    private static NoteDraft draft(String title) {
        return new NoteDraft(title, NoteType.concept, "Two_Pointers",
                "Короткое описание внутренней заметки",
                "## Тело\n\nСодержимое внутренней заметки.", 0.93);
    }
}
