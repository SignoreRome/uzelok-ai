package uzelok.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NameUniquenessTest {

    private final NameUniqueness uniqueness = new NameUniqueness(new GitIndex());

    @TempDir
    Path root;

    @Test
    void collectsTrackedAndUntrackedNamesAndSkipsServiceDirectories() throws IOException {
        VaultFixture.copyVault(root);
        Path knowledge = root.resolve("40_Knowledge");

        write(knowledge.resolve("Java/Collections/tWo_PoInTeRs.md"));
        write(knowledge.resolve("Java/Collections/Untracked_Note.md"));
        write(knowledge.resolve("_meta/Should_Not_Count.md"));
        write(knowledge.resolve("Algorithms/Graphs/Template_Thing_Template.md"));
        write(knowledge.resolve(".obsidian/Hidden.md"));
        write(knowledge.resolve(".trash/Trashed.md"));
        write(knowledge.resolve(".uzelok-tmp/Temp.md"));
        write(knowledge.resolve("Algorithms/Graphs/.hidden.md"));
        write(knowledge.resolve("Algorithms/Graphs/plain.txt"));

        Set<String> names = uniqueness.existingNames(knowledge, root);

        assertThat(names).contains(
                "two_pointers",
                "window_basics",
                "untracked_note");
        assertThat(names).doesNotContain(
                "should_not_count",
                "template_thing_template",
                "hidden",
                "trashed",
                "temp",
                "plain");
    }

    @Test
    void countsTrackedFileDeletedFromWorkingTreeWhenGitIsAvailable() throws Exception {
        Assumptions.assumeTrue(gitAvailable(), "git недоступен");

        Path knowledge = root.resolve("40_Knowledge");
        Path file = knowledge.resolve("Tracked_Note.md");
        write(file);
        assertThat(run(root, "git", "init", "-q")).isZero();
        assertThat(run(root, "git", "add", "40_Knowledge/Tracked_Note.md")).isZero();
        Files.delete(file);

        Set<String> names = uniqueness.existingNames(knowledge, root);

        assertThat(names).contains("tracked_note");
    }

    private static void write(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "---\ntype: concept\n---\n");
    }

    private static boolean gitAvailable() {
        try {
            Process process = new ProcessBuilder("git", "--version").start();
            return process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException exception) {
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static int run(Path directory, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .start();
        process.getInputStream().readAllBytes();
        return process.waitFor();
    }
}
