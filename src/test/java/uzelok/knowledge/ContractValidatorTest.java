package uzelok.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uzelok.domain.exception.ContractViolation;
import uzelok.domain.model.KnowledgeBase;

class ContractValidatorTest {

    private final ContractValidator validator = new ContractValidator();

    @TempDir
    Path root;

    @Test
    void validatesFixtureVault() throws IOException {
        VaultFixture.copyVault(root);
        KnowledgeBase base = VaultFixture.externalBase(root);

        ContractValidator.ContractReport report = validator.validate(base);

        assertThat(report.knowledgeRoot().getFileName().toString()).isEqualTo("40_Knowledge");
        assertThat(report.taxonomy().domainIds()).contains("Algorithms", "Java", "Networks");
        assertThat(report.warnings()).isEmpty();
    }

    @Test
    void rejectsBaseWithoutTaxonomyPath() throws IOException {
        VaultFixture.copyVault(root);
        KnowledgeBase base = new KnowledgeBase(
                VaultFixture.externalBase(root).id(),
                VaultFixture.externalBase(root).kind(),
                root.toString(),
                null,
                VaultFixture.INBOX_REL_PATH);

        assertThatThrownBy(() -> validator.validate(base))
                .isInstanceOf(ContractViolation.class)
                .hasMessageContaining("путь словаря/входящих");
    }

    @Test
    void rejectsBaseWithoutInboxPath() throws IOException {
        VaultFixture.copyVault(root);
        KnowledgeBase base = new KnowledgeBase(
                VaultFixture.externalBase(root).id(),
                VaultFixture.externalBase(root).kind(),
                root.toString(),
                VaultFixture.TAXONOMY_REL_PATH,
                " ");

        assertThatThrownBy(() -> validator.validate(base))
                .isInstanceOf(ContractViolation.class)
                .hasMessageContaining("путь словаря/входящих");
    }

    @Test
    void rejectsVaultWithoutKnowledgeDirectory() {
        KnowledgeBase base = VaultFixture.externalBase(root);

        assertThatThrownBy(() -> validator.validate(base))
                .isInstanceOf(ContractViolation.class)
                .hasMessageContaining("словар");
    }

    @Test
    void rejectsVaultWithoutMetaFolder() throws IOException {
        VaultFixture.copyVault(root);
        deleteRecursively(root.resolve("40_Knowledge/_meta"));
        KnowledgeBase base = VaultFixture.externalBase(root);

        assertThatThrownBy(() -> validator.validate(base))
                .isInstanceOf(ContractViolation.class)
                .hasMessageContaining("словар");
    }

    @Test
    void rejectsVaultWithoutContract() throws IOException {
        VaultFixture.copyVault(root);
        Files.delete(root.resolve("40_Knowledge/_meta/CONTRACT.md"));
        KnowledgeBase base = VaultFixture.externalBase(root);

        assertThatThrownBy(() -> validator.validate(base))
                .isInstanceOf(ContractViolation.class)
                .hasMessageContaining("контракт");
    }

    @Test
    void rejectsUnsupportedTaxonomyVersion() throws IOException {
        VaultFixture.copyVault(root);
        Files.writeString(root.resolve("40_Knowledge/_meta/taxonomy.yaml"), """
                contract_version: 1
                domains:
                  - id: Algorithms
                """);
        KnowledgeBase base = VaultFixture.externalBase(root);

        assertThatThrownBy(() -> validator.validate(base))
                .isInstanceOf(ContractViolation.class)
                .hasMessageContaining("версия");
    }

    @Test
    void warnsAboutMissingInbox() throws IOException {
        VaultFixture.copyVault(root);
        deleteRecursively(root.resolve("40_Knowledge/_Inbox"));
        KnowledgeBase base = VaultFixture.externalBase(root);

        ContractValidator.ContractReport report = validator.validate(base);

        assertThat(report.warnings()).anyMatch(warning -> warning.contains("_Inbox"));
    }

    @Test
    void warnsWhenGitignoreHasNoTmpDirectory() throws IOException {
        VaultFixture.copyVault(root);
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve(".gitignore"), "other/\n");
        KnowledgeBase base = VaultFixture.externalBase(root);

        ContractValidator.ContractReport report = validator.validate(base);

        assertThat(report.warnings()).anyMatch(warning -> warning.contains(".uzelok-tmp/"));
    }

    private static void deleteRecursively(Path directory) throws IOException {
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.sorted((left, right) -> right.getNameCount() - left.getNameCount())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException exception) {
                            throw new IllegalStateException(exception);
                        }
                    });
        }
    }
}
