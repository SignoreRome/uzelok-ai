package uzelok.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import uzelok.domain.model.KnowledgeBase;
import uzelok.domain.model.NoteDraft;
import uzelok.domain.model.NoteType;
import uzelok.domain.port.PreparedNote;
import uzelok.domain.port.WrittenNote;

@EnabledIfEnvironmentVariable(named = "AGORA_ROOT", matches = ".+")
class AgoraVaultAcceptanceTest {

    private static final String DEFAULT_LINTER = "/home/roman/.hermes/scripts/agora_lint.py";
    private static final String JOB_REF = "abcdef12-0000-0000-0000-000000000000";

    @TempDir
    Path root;

    @Test
    void writesNoteIntoCopyOfRealAgoraVaultAndPassesLinter() throws IOException {
        Path agoraRoot = Path.of(System.getenv("AGORA_ROOT"));
        Path source = agoraRoot.resolve("40_Knowledge");
        assertThat(source).as("в AGORA_ROOT нет 40_Knowledge").isDirectory();

        copyTree(source, root.resolve("40_Knowledge"));
        Path gitignore = agoraRoot.resolve(".gitignore");
        if (Files.isRegularFile(gitignore)) {
            Files.copy(gitignore, root.resolve(".gitignore"), StandardCopyOption.REPLACE_EXISTING);
        }

        String lintCommand = linterCommand();
        KnowledgeBase base = VaultFixture.externalBase(root);
        ExternalObsidianStore store = VaultFixture.store(lintCommand, Clock.systemUTC());

        PreparedNote prepared = store.prepare(base, JOB_REF, new NoteDraft(
                "Acceptance Two Pointers",
                NoteType.concept,
                "Two_Pointers",
                "Приёмочная заметка для проверки записи в реальную базу",
                "## Идея\n\nДва указателя двигаются по массиву.",
                0.93), false);

        assertThat(prepared.relPath())
                .startsWith("40_Knowledge/Algorithms/Two_Pointers/")
                .endsWith(".md");
        assertThat(prepared.markdown()).contains("domain: Algorithms", "topic: Two_Pointers");

        WrittenNote written = store.write(base, prepared);
        Path file = root.resolve(written.relPath());
        assertThat(written.created()).isTrue();
        assertThat(file).isRegularFile();
        assertThat(Files.readString(file)).isEqualTo(prepared.markdown());

        LinterRunner.LintResult lint = new LinterRunner(lintCommand, 60).run(root);
        assertThat(lint.ok()).as("линтер на копии базы: %s", lint.output()).isTrue();

        WrittenNote repeated = store.write(base, prepared);
        assertThat(repeated.created()).isFalse();
        assertThat(repeated.noteId()).isEqualTo(written.noteId());
    }

    private static String linterCommand() {
        String environment = System.getenv("AGORA_LINT");
        if (environment != null && !environment.isBlank()) {
            return environment;
        }
        Path defaultScript = Path.of(DEFAULT_LINTER);
        if (Files.isRegularFile(defaultScript) && !Files.isExecutable(defaultScript)) {
            return "python3 " + DEFAULT_LINTER;
        }
        return DEFAULT_LINTER;
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path path : walk.toList()) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else if (Files.isRegularFile(path)) {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
