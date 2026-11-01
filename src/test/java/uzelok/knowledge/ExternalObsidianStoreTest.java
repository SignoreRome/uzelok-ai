package uzelok.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uzelok.domain.exception.ContractViolation;
import uzelok.domain.model.KnowledgeBase;
import uzelok.domain.model.NoteDraft;
import uzelok.domain.model.NoteType;
import uzelok.domain.port.PreparedNote;
import uzelok.domain.port.WrittenNote;

class ExternalObsidianStoreTest {

    private static final String JOB_REF = "12345678-aaaa-bbbb-cccc-dddddddddddd";

    @TempDir
    Path root;

    @TempDir
    Path outside;

    private KnowledgeBase base;

    @BeforeEach
    void setUp() throws IOException {
        VaultFixture.copyVault(root);
        base = VaultFixture.externalBase(root);
    }

    @Test
    void prepareAndWriteCreatesFileWithRenderedContent() throws IOException {
        ExternalObsidianStore store = VaultFixture.store("");
        PreparedNote prepared = store.prepare(base, JOB_REF, draft("Store Test", "Graphs"), false);

        assertThat(prepared.relPath()).isEqualTo("40_Knowledge/Algorithms/Graphs/Store_Test.md");
        assertThat(root.resolve(prepared.relPath())).doesNotExist();

        WrittenNote written = store.write(base, prepared);

        Path file = root.resolve(written.relPath());
        assertThat(written.created()).isTrue();
        assertThat(written.noteId()).isEqualTo(prepared.noteId());
        assertThat(file).exists();
        assertThat(Files.readString(file)).isEqualTo(prepared.markdown());
        assertThat(Files.readString(file)).contains("domain: Algorithms", "topic: Graphs");
    }

    @Test
    void repeatedWriteWithSameContentIsIdempotent() {
        ExternalObsidianStore store = VaultFixture.store("");
        PreparedNote prepared = store.prepare(base, JOB_REF, draft("Repeat Test", "Graphs"), false);
        store.write(base, prepared);

        WrittenNote second = store.write(base, prepared);

        assertThat(second.created()).isFalse();
        assertThat(second.contentHash()).isEqualTo(prepared.contentHash());
    }

    @Test
    void writeRejectsExistingFileWithDifferentContent() {
        ExternalObsidianStore store = VaultFixture.store("");
        PreparedNote prepared = store.prepare(base, JOB_REF, draft("Conflict Test", "Graphs"), false);
        store.write(base, prepared);
        PreparedNote conflicting = new PreparedNote(prepared.noteId(), prepared.relPath(),
                prepared.fileName(), prepared.markdown() + "изменено\n", "deadbeef", List.of());

        assertThatThrownBy(() -> store.write(base, conflicting))
                .isInstanceOf(ContractViolation.class)
                .hasMessageContaining("другим содержимым");
    }

    @Test
    void writeRejectsPathTraversal() {
        ExternalObsidianStore store = VaultFixture.store("");
        PreparedNote prepared = store.prepare(base, JOB_REF, draft("Traversal Test", "Graphs"), false);
        PreparedNote escaping = new PreparedNote(prepared.noteId(), "../outside.md",
                prepared.fileName(), prepared.markdown(), prepared.contentHash(), List.of());

        assertThatThrownBy(() -> store.write(base, escaping))
                .isInstanceOf(ContractViolation.class)
                .hasMessageContaining("корень");
        assertThat(root.getParent().resolve("outside.md")).doesNotExist();
    }

    @Test
    void writeRejectsDirectoryMissingFromTaxonomy() {
        ExternalObsidianStore store = VaultFixture.store("");
        PreparedNote prepared = store.prepare(base, JOB_REF, draft("Folder Test", "Graphs"), false);
        PreparedNote wrongFolder = new PreparedNote(prepared.noteId(),
                "40_Knowledge/Unknown/Topic/Folder_Test.md", prepared.fileName(),
                prepared.markdown(), prepared.contentHash(), List.of());

        assertThatThrownBy(() -> store.write(base, wrongFolder))
                .isInstanceOf(ContractViolation.class)
                .hasMessageContaining("словар");
    }

    @Test
    void nameCollisionGoesToInbox() {
        ExternalObsidianStore store = VaultFixture.store("");
        PreparedNote prepared = store.prepare(base, JOB_REF,
                draft("Two Pointers", "Graphs"), false);

        assertThat(prepared.relPath())
                .isEqualTo("40_Knowledge/_Inbox/Two_Pointers_12345678.md");
        WrittenNote written = store.write(base, prepared);

        assertThat(written.created()).isTrue();
        assertThat(root.resolve(written.relPath())).exists();
    }

    @Test
    void prepareDoesNotWriteToDisk() throws IOException {
        ExternalObsidianStore store = VaultFixture.store("");
        List<String> before = filesUnder(root);

        store.prepare(base, JOB_REF, draft("Dry Run", "Graphs"), false);

        assertThat(filesUnder(root)).isEqualTo(before);
    }

    @Test
    void sameJobRefProducesDeterministicNoteIdAndHash() {
        ExternalObsidianStore store = VaultFixture.store("");
        PreparedNote first = store.prepare(base, JOB_REF, draft("Same Job", "Graphs"), false);
        PreparedNote second = store.prepare(base, JOB_REF, draft("Same Job", "Graphs"), false);
        PreparedNote other = store.prepare(base, "87654321-aaaa-bbbb-cccc-dddddddddddd",
                draft("Same Job", "Graphs"), false);

        assertThat(second.noteId()).isEqualTo(first.noteId());
        assertThat(second.contentHash()).isEqualTo(first.contentHash());
        assertThat(other.noteId()).isNotEqualTo(first.noteId());
    }

    @Test
    void writeRejectsNoteWhenLinterFails() {
        ExternalObsidianStore store = VaultFixture.store("false");
        PreparedNote prepared = store.prepare(base, JOB_REF, draft("Lint Fail", "Graphs"), false);

        assertThatThrownBy(() -> store.write(base, prepared))
                .isInstanceOf(ContractViolation.class)
                .hasMessageContaining("линтер");
        assertThat(root.resolve("40_Knowledge/Algorithms/Graphs")).doesNotExist();
        assertThat(root.resolve(prepared.relPath())).doesNotExist();
    }

    @Test
    void writeRejectsPathThroughSymlink() throws IOException {
        ExternalObsidianStore store = VaultFixture.store("");
        PreparedNote prepared = store.prepare(base, JOB_REF, draft("Symlink Test", "Graphs"), false);
        Path symlink = root.resolve("40_Knowledge/Algorithms/Graphs");
        Files.createSymbolicLink(symlink, outside);

        assertThatThrownBy(() -> store.write(base, prepared))
                .isInstanceOf(ContractViolation.class)
                .hasMessageContaining("символическую ссылку");
        assertThat(outside.resolve(prepared.fileName())).doesNotExist();
    }

    private static NoteDraft draft(String title, String topic) {
        return new NoteDraft(title, NoteType.concept, topic,
                "Короткое описание заметки", "## Тело\n\nСодержимое заметки.", 0.92);
    }

    private static List<String> filesUnder(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.map(root::relativize).map(Path::toString).sorted().toList();
        }
    }
}
