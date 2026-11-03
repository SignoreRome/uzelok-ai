package uzelok.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uzelok.domain.exception.ContractViolation;
import uzelok.domain.model.ExportBundle;
import uzelok.domain.model.KnowledgeBase;
import uzelok.domain.model.KnowledgeBaseKind;

class ExportArchiverTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-02T12:34:56Z"), ZoneOffset.UTC);
    private static final long LIMIT = 50_000_000L;

    @TempDir
    Path root;

    private Path vaultRoot;
    private KnowledgeBase base;
    private ExportArchiver archiver;

    @BeforeEach
    void setUp() {
        vaultRoot = new PlainVault(root).create(root.resolve("internal"));
        base = new KnowledgeBase(
                UUID.fromString("abcdef12-3456-7890-abcd-ef1234567890"),
                KnowledgeBaseKind.INTERNAL_DIR,
                vaultRoot.toString(),
                null,
                null);
        archiver = new ExportArchiver(root.resolve("exports"), LIMIT, 5000, CLOCK);
    }

    @Test
    void zipContainsReadmeAndNotesAndSkipsHiddenPaths() throws IOException {
        String noteRelative = "notes/Kafka_Streams/Note.md";
        write(noteRelative, "# Заметка\n");
        Files.createDirectories(vaultRoot.resolve(".uzelok-tmp"));
        Files.writeString(vaultRoot.resolve(".uzelok-tmp/tmp.txt"), "tmp");
        Files.createDirectories(vaultRoot.resolve("notes/.hidden"));
        Files.writeString(vaultRoot.resolve("notes/.hidden/secret.md"), "secret");
        Files.writeString(vaultRoot.resolve("notes/.hidden-note.md"), "hidden");

        ExportBundle bundle = archiver.export(base);

        assertThat(bundle.parts()).hasSize(1);
        List<String> entries = entries(bundle.parts().getFirst().path());
        assertThat(entries).contains("README.md", noteRelative);
        assertThat(entries).noneMatch(entry -> entry.contains(".uzelok-tmp"));
        assertThat(entries).noneMatch(entry -> entry.contains(".hidden"));
        assertThat(bundle.fileCount()).isEqualTo(entries.size());

        archiver.cleanup(bundle);
    }

    @Test
    void smallLimitSplitsArchiveIntoParts() throws IOException {
        write("notes/Kafka_Streams/Note.md", "# Заметка\n");
        ExportArchiver small = new ExportArchiver(root.resolve("exports"), 64 * 1024, 5000, CLOCK);

        ExportBundle bundle = small.export(base);

        assertThat(bundle.parts().size()).isGreaterThan(1);
        for (int index = 0; index < bundle.parts().size(); index++) {
            var part = bundle.parts().get(index);
            assertThat(part.fileName())
                    .contains("-part" + (index + 1) + ".zip");
            assertThat(part.sizeBytes()).isLessThanOrEqualTo(64 * 1024);
            assertThat(part.path()).isRegularFile();
        }
        small.cleanup(bundle);
    }

    @Test
    void fileLargerThanLimitIsSkippedWithWarning() throws IOException {
        String big = "notes/Kafka_Streams/Big.md";
        Files.createDirectories(vaultRoot.resolve("notes/Kafka_Streams"));
        Files.write(vaultRoot.resolve(big), new byte[200_000]);
        ExportArchiver small = new ExportArchiver(root.resolve("exports"), 64 * 1024, 5000, CLOCK);

        ExportBundle bundle = small.export(base);

        assertThat(bundle.warnings()).anyMatch(warning ->
                warning.contains("файл больше лимита") && warning.contains(big));
        assertThat(bundle.parts()).isNotEmpty();
        for (var part : bundle.parts()) {
            assertThat(entries(part.path())).noneMatch(entry -> entry.equals(big));
        }
        small.cleanup(bundle);
    }

    @Test
    void vaultWithoutVisibleFilesProducesNoParts() throws IOException {
        Path fresh = root.resolve("empty");
        Files.createDirectories(fresh.resolve("notes/.hidden"));
        Files.writeString(fresh.resolve("notes/.hidden/secret.md"), "secret");
        KnowledgeBase empty = new KnowledgeBase(UUID.randomUUID(), KnowledgeBaseKind.INTERNAL_DIR,
                fresh.toString(), null, null);

        ExportBundle bundle = new ExportArchiver(
                root.resolve("exports-empty"), LIMIT, 5000, CLOCK).export(empty);

        assertThat(bundle.parts()).isEmpty();
        assertThat(bundle.fileCount()).isZero();
        assertThat(bundle.tempDirectory()).isNull();
    }

    @Test
    void fileWithBackslashInNameIsSkippedWithWarning() throws IOException {
        write("notes/Kafka_Streams/Good.md", "# Заметка\n");
        write("notes/Kafka_Streams/bad\\name.md", "# плохой путь\n");

        ExportBundle bundle = archiver.export(base);

        assertThat(bundle.warnings()).anyMatch(warning ->
                warning.contains("недопустимый путь в архиве"));
        for (var part : bundle.parts()) {
            assertThat(entries(part.path())).noneMatch(entry -> entry.contains("bad\\name"));
        }
        archiver.cleanup(bundle);
    }

    @Test
    void tooManyFilesFailsWithContractViolation() throws IOException {
        write("notes/Kafka_Streams/One.md", "# Один\n");
        write("notes/Kafka_Streams/Two.md", "# Два\n");
        ExportArchiver limited = new ExportArchiver(
                root.resolve("exports-limited"), LIMIT, 2, CLOCK);

        assertThatThrownBy(() -> limited.export(base))
                .isInstanceOf(ContractViolation.class)
                .hasMessageContaining("слишком много файлов для экспорта")
                .hasMessageContaining("> 2");
    }

    @Test
    void symbolicLinkToFileIsNotArchived() throws IOException {
        String targetRelative = "notes/Kafka_Streams/Target.md";
        write(targetRelative, "# Цель\n");
        Path link = vaultRoot.resolve("notes/Kafka_Streams/Link.md");
        Files.createSymbolicLink(link, vaultRoot.resolve(targetRelative));

        ExportBundle bundle = archiver.export(base);

        List<String> entries = entries(bundle.parts().getFirst().path());
        assertThat(entries).contains(targetRelative);
        assertThat(entries).noneMatch(entry -> entry.endsWith("Link.md"));
        archiver.cleanup(bundle);
    }

    @Test
    void cleanupRemovesTemporaryDirectory() throws IOException {
        write("notes/Kafka_Streams/Note.md", "# Заметка\n");

        ExportBundle bundle = archiver.export(base);
        Path temporary = bundle.tempDirectory();
        assertThat(temporary).isDirectory();

        archiver.cleanup(bundle);

        assertThat(temporary).doesNotExist();
    }

    @Test
    void externalBaseIsNotExported() {
        KnowledgeBase external = new KnowledgeBase(UUID.randomUUID(),
                KnowledgeBaseKind.EXTERNAL_OBSIDIAN, vaultRoot.toString(),
                "40_Knowledge/_meta/taxonomy.yaml", "40_Knowledge/_Inbox");

        assertThatThrownBy(() -> archiver.export(external))
                .isInstanceOf(ContractViolation.class)
                .hasMessageContaining("только внутренней");
    }

    private void write(String relative, String content) throws IOException {
        Path target = vaultRoot.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }

    private static List<String> entries(Path zip) throws IOException {
        try (ZipFile file = new ZipFile(zip.toFile())) {
            return file.stream().map(ZipEntry::getName).toList();
        }
    }
}
