package uzelok.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
        vaultRoot = new TemplateVault(root).create(root.resolve("internal"));
        base = new KnowledgeBase(
                UUID.fromString("abcdef12-3456-7890-abcd-ef1234567890"),
                KnowledgeBaseKind.INTERNAL_DIR,
                vaultRoot.toString(),
                VaultFixture.TAXONOMY_REL_PATH,
                VaultFixture.INBOX_REL_PATH);
        archiver = new ExportArchiver(new ContractValidator(), root.resolve("exports"),
                LIMIT, 5000, CLOCK);
    }

    @Test
    void zipContainsMetadataAndNoteAndSkipsHiddenDirectories() throws IOException {
        String noteRelative = "40_Knowledge/Algorithms/Two_Pointers/Note.md";
        write(noteRelative, "# Заметка\n");
        Files.createDirectories(vaultRoot.resolve(".uzelok-tmp"));
        Files.writeString(vaultRoot.resolve(".uzelok-tmp/tmp.txt"), "tmp");
        Files.createDirectories(vaultRoot.resolve("40_Knowledge/.hidden"));
        Files.writeString(vaultRoot.resolve("40_Knowledge/.hidden/secret.md"), "secret");

        ExportBundle bundle = archiver.export(base);

        assertThat(bundle.parts()).hasSize(1);
        List<String> entries = entries(bundle.parts().getFirst().path());
        assertThat(entries).contains(
                "40_Knowledge/_meta/taxonomy.yaml",
                noteRelative);
        assertThat(entries).noneMatch(entry -> entry.contains(".uzelok-tmp"));
        assertThat(entries).noneMatch(entry -> entry.contains(".hidden"));

        archiver.cleanup(bundle);
    }

    @Test
    void smallLimitSplitsArchiveIntoParts() throws IOException {
        write("40_Knowledge/Algorithms/Two_Pointers/Note.md", "# Заметка\n");
        ExportArchiver small = new ExportArchiver(new ContractValidator(),
                root.resolve("exports"), 64 * 1024, 5000, CLOCK);

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
        String big = "40_Knowledge/Algorithms/Two_Pointers/Big.md";
        Files.createDirectories(vaultRoot.resolve("40_Knowledge/Algorithms/Two_Pointers"));
        Files.write(vaultRoot.resolve(big), new byte[200_000]);
        ExportArchiver small = new ExportArchiver(new ContractValidator(),
                root.resolve("exports"), 64 * 1024, 5000, CLOCK);

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
        Path meta = fresh.resolve("40_Knowledge/.meta");
        Files.createDirectories(meta);
        Path fixture = VaultFixture.resourcePath().resolve("40_Knowledge/_meta");
        Files.copy(fixture.resolve("taxonomy.yaml"), meta.resolve("taxonomy.yaml"),
                StandardCopyOption.REPLACE_EXISTING);
        Files.copy(fixture.resolve("CONTRACT.md"), meta.resolve("CONTRACT.md"),
                StandardCopyOption.REPLACE_EXISTING);
        KnowledgeBase empty = new KnowledgeBase(UUID.randomUUID(), KnowledgeBaseKind.INTERNAL_DIR,
                fresh.toString(), "40_Knowledge/.meta/taxonomy.yaml", "40_Knowledge/_Inbox");

        ExportBundle bundle = new ExportArchiver(new ContractValidator(),
                root.resolve("exports-empty"), LIMIT, 5000, CLOCK).export(empty);

        assertThat(bundle.parts()).isEmpty();
        assertThat(bundle.fileCount()).isZero();
        assertThat(bundle.tempDirectory()).isNull();
    }

    @Test
    void fileWithBackslashInNameIsSkippedWithWarning() throws IOException {
        write("40_Knowledge/Algorithms/Two_Pointers/Good.md", "# Заметка\n");
        write("40_Knowledge/Algorithms/Two_Pointers/bad\\name.md", "# плохой путь\n");

        ExportBundle bundle = archiver.export(base);

        assertThat(bundle.warnings()).anyMatch(warning ->
                warning.contains("недопустимый путь в архиве"));
        for (var part : bundle.parts()) {
            assertThat(entries(part.path())).noneMatch(entry -> entry.contains("bad\\name"));
        }
        archiver.cleanup(bundle);
    }

    @Test
    void tooManyFilesFailsWithContractViolation() {
        ExportArchiver limited = new ExportArchiver(new ContractValidator(),
                root.resolve("exports-limited"), LIMIT, 2, CLOCK);

        assertThatThrownBy(() -> limited.export(base))
                .isInstanceOf(ContractViolation.class)
                .hasMessageContaining("слишком много файлов для экспорта")
                .hasMessageContaining("> 2");
    }

    @Test
    void symbolicLinkToFileIsNotArchived() throws IOException {
        String targetRelative = "40_Knowledge/Algorithms/Two_Pointers/Target.md";
        write(targetRelative, "# Цель\n");
        Path link = vaultRoot.resolve("40_Knowledge/Algorithms/Two_Pointers/Link.md");
        Files.createSymbolicLink(link, vaultRoot.resolve(targetRelative));

        ExportBundle bundle = archiver.export(base);

        List<String> entries = entries(bundle.parts().getFirst().path());
        assertThat(entries).contains(targetRelative);
        assertThat(entries).noneMatch(entry -> entry.endsWith("Link.md"));
        archiver.cleanup(bundle);
    }

    @Test
    void cleanupRemovesTemporaryDirectory() throws IOException {
        write("40_Knowledge/Algorithms/Two_Pointers/Note.md", "# Заметка\n");

        ExportBundle bundle = archiver.export(base);
        Path temporary = bundle.tempDirectory();
        assertThat(temporary).isDirectory();

        archiver.cleanup(bundle);

        assertThat(temporary).doesNotExist();
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
