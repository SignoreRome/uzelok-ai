package uzelok.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContractValidatorViolationsTest {

    @TempDir
    Path root;

    private final ContractValidator validator = new ContractValidator();

    @Test
    void validInternalVaultHasNoViolations() {
        Path vault = new TemplateVault(root).create(root.resolve("valid"));

        assertThat(validator.violations(vault)).isEmpty();
    }

    @Test
    void missingContractProducesSingleViolation() throws IOException {
        Path vault = new TemplateVault(root).create(root.resolve("broken"));
        Files.delete(vault.resolve("40_Knowledge/_meta/CONTRACT.md"));

        assertThat(validator.violations(vault))
                .hasSize(1)
                .allMatch(violation -> violation.contains("контракт"));
    }

    @Test
    void relativePathProducesSingleViolation() {
        assertThat(validator.violations(Path.of("relative-vault")))
                .hasSize(1)
                .allMatch(violation -> violation.contains("абсолютный"));
    }

    @Test
    void nonDirectoryProducesSingleViolation() throws IOException {
        Path file = root.resolve("file.txt");
        Files.writeString(file, "не каталог");

        assertThat(validator.violations(file))
                .hasSize(1)
                .allMatch(violation -> violation.contains("каталог"));
    }
}
