package uzelok.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NameUniquenessTest {

    private final NameUniqueness uniqueness = new NameUniqueness();

    @TempDir
    Path root;

    @Test
    void collectsNamesAndSkipsServiceDirectories() throws IOException {
        VaultFixture.copyVault(root);
        Path knowledge = root.resolve("40_Knowledge");

        write(knowledge.resolve("Java/Collections/tWo_PoInTeRs.md"));
        write(knowledge.resolve("Java/Collections/Untracked_Note.md"));
        write(knowledge.resolve("_meta/Should_Not_Count.md"));
        write(knowledge.resolve("Algorithms/Graphs/Template_Thing_Template.md"));
        write(knowledge.resolve(".obsidian/Hidden.md"));
        write(knowledge.resolve(".trash/Trashed.md"));
        write(knowledge.resolve("Algorithms/Graphs/.hidden.md"));
        write(knowledge.resolve("Algorithms/Graphs/plain.txt"));

        Set<String> names = uniqueness.existingNames(knowledge);

        assertThat(names).contains(
                "two_pointers",
                "window_basics",
                "untracked_note");
        assertThat(names).doesNotContain(
                "should_not_count",
                "template_thing_template",
                "hidden",
                "trashed",
                "plain");
    }

    @Test
    void canonicalNamesKeepActualCaseAndMapLowercaseKeys() throws IOException {
        Path knowledge = root.resolve("40_Knowledge");

        write(knowledge.resolve("Algorithms/Khesh_Tablitsa.md"));
        write(knowledge.resolve("Algorithms/Otsenka_Vychislitelnoy_Slozhnosti_Big_O.md"));
        write(knowledge.resolve("_meta/Ignored.md"));

        Map<String, String> canonical = uniqueness.canonicalNames(knowledge);

        assertThat(canonical)
                .containsEntry("khesh_tablitsa", "Khesh_Tablitsa")
                .containsEntry("otsenka_vychislitelnoy_slozhnosti_big_o",
                        "Otsenka_Vychislitelnoy_Slozhnosti_Big_O")
                .doesNotContainKey("ignored");
    }

    @Test
    void deletedFileNoLongerOccupiesName() throws IOException {
        Path knowledge = root.resolve("notes");
        Path file = knowledge.resolve("Kafka_Streams/Exactly_Once.md");
        write(file);
        assertThat(uniqueness.existingNames(knowledge)).contains("exactly_once");

        Files.delete(file);

        assertThat(uniqueness.existingNames(knowledge)).doesNotContain("exactly_once");
    }

    private static void write(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "---\ntype: concept\n---\n");
    }
}
