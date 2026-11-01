package uzelok.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uzelok.domain.exception.ContractViolation;
import uzelok.domain.model.Taxonomy;
import uzelok.domain.model.TopicRef;

class TaxonomyLoaderTest {

    private final TaxonomyLoader loader = new TaxonomyLoader();

    @Test
    void loadsFixtureTaxonomyWithAliases() {
        Taxonomy taxonomy = loader.load(VaultFixture.resourcePath().resolve(
                "40_Knowledge/_meta/taxonomy.yaml"));

        assertThat(taxonomy.contractVersion()).isEqualTo(Taxonomy.SUPPORTED_CONTRACT_VERSION);
        assertThat(taxonomy.domainIds()).containsExactly("Algorithms", "Java", "Networks");
        assertThat(taxonomy.topicIds()).contains("Two_Pointers", "Sliding_Window", "Collections");
        assertThat(taxonomy.resolve("two_pointers"))
                .contains(new TopicRef("Algorithms", "Two_Pointers"));
        assertThat(taxonomy.resolve("ДВА_УКАЗАТЕЛЯ"))
                .contains(new TopicRef("Algorithms", "Two_Pointers"));
        assertThat(taxonomy.resolveIn("java", "COLLECTIONS"))
                .contains(new TopicRef("Java", "Collections"));
        assertThat(taxonomy.resolve("unknown")).isEmpty();
        assertThat(taxonomy.hasTopic("Algorithms", "Graphs")).isTrue();
        assertThat(taxonomy.hasTopic("Java", "Graphs")).isFalse();
    }

    @Test
    void loadsTwoDomainsAndStringTopics(@TempDir Path directory) throws IOException {
        Path file = write(directory, """
                contract_version: 2
                domains:
                  - id: Alpha
                    aliases: [a]
                    topics:
                      - One
                      - id: Two
                        aliases: [second]
                  - id: Beta
                    topics:
                      - id: Three
                """);

        Taxonomy taxonomy = loader.load(file);

        assertThat(taxonomy.domainIds()).containsExactly("Alpha", "Beta");
        assertThat(taxonomy.resolve("one")).contains(new TopicRef("Alpha", "One"));
        assertThat(taxonomy.resolve("second")).contains(new TopicRef("Alpha", "Two"));
    }

    @Test
    void rejectsUnsupportedContractVersion(@TempDir Path directory) throws IOException {
        Path file = write(directory, """
                contract_version: 1
                domains:
                  - id: Alpha
                """);

        assertThatThrownBy(() -> loader.load(file))
                .isInstanceOf(ContractViolation.class)
                .hasMessageContaining("1");
    }

    @Test
    void rejectsMissingContractVersion(@TempDir Path directory) throws IOException {
        Path file = write(directory, """
                domains:
                  - id: Alpha
                """);

        assertThatThrownBy(() -> loader.load(file))
                .isInstanceOf(ContractViolation.class)
                .hasMessageContaining("contract_version");
    }

    @Test
    void rejectsTopicDeclaredInTwoDomains(@TempDir Path directory) throws IOException {
        Path file = write(directory, """
                contract_version: 2
                domains:
                  - id: Alpha
                    topics:
                      - id: Shared
                  - id: Beta
                    topics:
                      - id: Shared
                """);

        assertThatThrownBy(() -> loader.load(file))
                .isInstanceOf(ContractViolation.class)
                .hasMessageContaining("Shared");
    }

    @Test
    void rejectsDuplicateTopicInsideDomain(@TempDir Path directory) throws IOException {
        Path file = write(directory, """
                contract_version: 2
                domains:
                  - id: Alpha
                    topics:
                      - id: Same
                      - id: Same
                """);

        assertThatThrownBy(() -> loader.load(file))
                .isInstanceOf(ContractViolation.class)
                .hasMessageContaining("Same");
    }

    @Test
    void rejectsEmptyDomains(@TempDir Path directory) throws IOException {
        Path file = write(directory, """
                contract_version: 2
                domains: []
                """);

        assertThatThrownBy(() -> loader.load(file))
                .isInstanceOf(ContractViolation.class)
                .hasMessageContaining("domains");
    }

    @Test
    void wrapsBrokenYamlIntoContractViolation(@TempDir Path directory) throws IOException {
        Path file = write(directory, "contract_version: [2\n");

        assertThatThrownBy(() -> loader.load(file))
                .isInstanceOf(ContractViolation.class)
                .hasMessageContaining(file.toString());
    }

    private static Path write(Path directory, String content) throws IOException {
        Path file = directory.resolve("taxonomy.yaml");
        Files.writeString(file, content);
        return file;
    }
}
