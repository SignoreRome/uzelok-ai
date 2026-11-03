package uzelok.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uzelok.domain.exception.ContractViolation;
import uzelok.domain.model.KnowledgeBase;
import uzelok.domain.model.KnowledgeBaseKind;
import uzelok.domain.model.NoteDraft;
import uzelok.domain.model.NoteType;
import uzelok.domain.port.PreparedNote;
import uzelok.domain.port.WrittenNote;

class PlainVaultStoreTest {

    private static final String JOB_REF = "12345678-aaaa-bbbb-cccc-dddddddddddd";

    @TempDir
    Path root;

    private Path vaultRoot;
    private KnowledgeBase base;
    private PlainVaultStore store;

    @BeforeEach
    void setUp() {
        vaultRoot = new PlainVault(root).create(root.resolve("internal"));
        base = new KnowledgeBase(UUID.randomUUID(), KnowledgeBaseKind.INTERNAL_DIR,
                vaultRoot.toString(), null, null);
        store = new PlainVaultStore(
                new VaultOptions(0.55),
                new ContractValidator(),
                new NameUniqueness(),
                new FileNamePolicy(60),
                new ContentSanitizer(),
                new LinterRunner("", 60),
                new VaultLockRegistry(),
                VaultFixture.FIXED_CLOCK);
    }

    @Test
    void prepareAndWriteCreatesNoteUnderNotesTopic() throws IOException {
        PreparedNote prepared = store.prepare(base, JOB_REF,
                draft("Exactly Once", "Kafka Streams"), false);

        assertThat(prepared.relPath())
                .isEqualTo("notes/Kafka_Streams/Exactly_Once.md");
        assertThat(prepared.markdown()).contains("topic: Kafka Streams")
                .doesNotContain("domain:");

        WrittenNote written = store.write(base, prepared);

        Path file = vaultRoot.resolve(written.relPath());
        assertThat(written.created()).isTrue();
        assertThat(file).isRegularFile();
        assertThat(Files.readString(file)).isEqualTo(prepared.markdown());
        assertThat(Files.readString(vaultRoot.resolve("README.md"))).contains("notes/");
    }

    @Test
    void repeatedWriteIsIdempotentAndLeavesNoTemporaryFile() throws IOException {
        PreparedNote prepared = store.prepare(base, JOB_REF,
                draft("Repeated Note", "Kafka Streams"), false);
        store.write(base, prepared);

        WrittenNote second = store.write(base, prepared);

        assertThat(second.created()).isFalse();
        assertThat(filesUnder(vaultRoot.resolve("notes")))
                .noneMatch(path -> path.getFileName().toString().startsWith(".uzelok-tmp"));
        assertThat(store.existingNoteNames(base)).contains("repeated_note");
    }

    @Test
    void collisionAddsDeterministicSuffixWithoutInbox() {
        PreparedNote prepared = store.prepare(base, JOB_REF,
                draft("Exactly Once", "Kafka Streams"), false);
        store.write(base, prepared);

        PreparedNote collision = store.prepare(base, JOB_REF,
                draft("Exactly Once", "Kafka Streams"), false);
        PreparedNote again = store.prepare(base, JOB_REF,
                draft("Exactly Once", "Kafka Streams"), false);

        assertThat(collision.relPath()).isEqualTo("notes/Kafka_Streams/Exactly_Once_12345678.md");
        assertThat(again.relPath()).isEqualTo(collision.relPath());
        assertThat(collision.warnings()).anyMatch(warning -> warning.contains("имя занято"));
        assertThat(collision.warnings()).noneMatch(warning -> warning.contains("входящ"));
    }

    @Test
    void forceInboxIsIgnoredForNeutralBase() {
        PreparedNote prepared = store.prepare(base, JOB_REF,
                draft("Forced Note", "Kafka Streams"), true);

        assertThat(prepared.relPath()).isEqualTo("notes/Kafka_Streams/Forced_Note.md");
        assertThat(prepared.warnings()).anyMatch(warning -> warning.contains("без входящих"));
    }

    @Test
    void problemWithoutProblemFieldsStaysInNotes() {
        PreparedNote prepared = store.prepare(base, JOB_REF, new NoteDraft(
                "Problem Note", NoteType.problem, "Kafka Streams", "Задача",
                "## Условие\n\nТекст.", 0.9), false);

        assertThat(prepared.relPath()).isEqualTo("notes/Kafka_Streams/Problem_Note.md");
        assertThat(prepared.warnings()).anyMatch(warning -> warning.contains("problem_*"));
        assertThat(prepared.warnings()).noneMatch(warning -> warning.contains("входящ"));
    }

    @Test
    void neutralBaseDoesNotRunLinter() {
        PlainVaultStore noLinter = new PlainVaultStore(
                new VaultOptions(0.55),
                new ContractValidator(),
                new NameUniqueness(),
                new FileNamePolicy(60),
                new ContentSanitizer(),
                new LinterRunner("false", 60),
                new VaultLockRegistry(),
                VaultFixture.FIXED_CLOCK);
        PreparedNote prepared = noLinter.prepare(base, JOB_REF,
                draft("No Linter", "Kafka Streams"), false);

        WrittenNote written = noLinter.write(base, prepared);

        assertThat(written.created()).isTrue();
    }

    @Test
    void taxonomyIsFree() {
        assertThat(store.taxonomy(base).isFree()).isTrue();
        assertThat(store.taxonomy(base).domains()).isEmpty();
    }

    @Test
    void rejectsPathOutsideNotes() {
        PreparedNote prepared = store.prepare(base, JOB_REF,
                draft("Traversal", "Kafka Streams"), false);
        PreparedNote escaping = new PreparedNote(prepared.noteId(), "../outside.md",
                prepared.fileName(), prepared.markdown(), prepared.contentHash(), java.util.List.of());

        assertThatThrownBy(() -> store.write(base, escaping))
                .isInstanceOf(ContractViolation.class)
                .hasMessageContaining("корень");
        assertThat(root.resolve("outside.md")).doesNotExist();
    }

    @Test
    void rejectsTopicFolderThatIsNotAValidName() {
        PreparedNote prepared = store.prepare(base, JOB_REF,
                draft("Bad Folder", "Kafka Streams"), false);
        PreparedNote wrongFolder = new PreparedNote(prepared.noteId(),
                "notes/lowercase/Bad_Folder.md", prepared.fileName(),
                prepared.markdown(), prepared.contentHash(), java.util.List.of());

        assertThatThrownBy(() -> store.write(base, wrongFolder))
                .isInstanceOf(ContractViolation.class)
                .hasMessageContaining("каталог темы");
    }

    private static NoteDraft draft(String title, String topic) {
        return new NoteDraft(title, NoteType.concept, topic,
                "Короткое описание внутренней заметки",
                "## Тело\n\nСодержимое внутренней заметки.", 0.93);
    }

    private static Stream<Path> filesUnder(Path root) throws IOException {
        return Files.walk(root).filter(Files::isRegularFile);
    }
}
