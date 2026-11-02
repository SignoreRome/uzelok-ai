package uzelok.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uzelok.domain.exception.ContractViolation;

class TemplateVaultTest {

    private static final List<String> TEMPLATE_FILES = List.of(
            "40_Knowledge/_meta/CONTRACT.md",
            "40_Knowledge/_meta/STRUCTURE.md",
            "40_Knowledge/_meta/taxonomy.yaml",
            "40_Knowledge/_meta/Note_Template.md",
            "40_Knowledge/_meta/Problem_Template.md");

    @TempDir
    Path root;

    @Test
    void createsVaultFromTemplateWithEmptyInboxAndTmp() {
        Path created = new TemplateVault(root).create(root.resolve("vault"));

        for (String relative : TEMPLATE_FILES) {
            assertThat(created.resolve(relative)).isRegularFile();
        }
        assertThat(created.resolve("40_Knowledge/_Inbox")).isDirectory();
        assertThat(created.resolve(".uzelok-tmp")).isDirectory();
        assertThat(created.resolve("40_Knowledge/_meta/taxonomy.yaml"))
                .content().contains("contract_version: 2");
        assertThat(created.resolve("40_Knowledge/_meta/CONTRACT.md"))
                .content().contains("Контракт");
    }

    @Test
    void repeatedCreateDoesNotChangeFiles() throws IOException {
        TemplateVault vault = new TemplateVault(root);
        Path created = vault.create(root.resolve("vault"));
        byte[] taxonomy = Files.readAllBytes(created.resolve("40_Knowledge/_meta/taxonomy.yaml"));

        Path second = vault.create(created);

        assertThat(second).isEqualTo(created);
        assertThat(Files.readAllBytes(created.resolve("40_Knowledge/_meta/taxonomy.yaml")))
                .isEqualTo(taxonomy);
    }

    @Test
    void rejectsModifiedTemplateFile() throws IOException {
        TemplateVault vault = new TemplateVault(root);
        Path created = vault.create(root.resolve("vault"));
        Files.writeString(created.resolve("40_Knowledge/_meta/CONTRACT.md"), "изменено");

        assertThatThrownBy(() -> vault.create(created))
                .isInstanceOf(ContractViolation.class)
                .hasMessageContaining("другим содержимым");
    }

    @Test
    void createsVaultsRootAndTenantDirectoryWithPrivatePermissions() throws IOException {
        Assumptions.assumeTrue(
                root.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path vaultsRoot = root.resolve("vaults");

        Path created = new TemplateVault(vaultsRoot).create(vaultsRoot.resolve("tenant-1"));

        assertThat(Files.getPosixFilePermissions(vaultsRoot))
                .isEqualTo(PosixFilePermissions.fromString("rwx------"));
        assertThat(Files.getPosixFilePermissions(created))
                .isEqualTo(PosixFilePermissions.fromString("rwx------"));
    }

    @Test
    void rejectsSymlinkInsteadOfTenantDirectory() throws IOException {
        Path vaultsRoot = root.resolve("vaults");
        Files.createDirectories(vaultsRoot);
        Path other = root.resolve("other");
        Files.createDirectories(other);
        Path link = vaultsRoot.resolve("tenant-1");
        Files.createSymbolicLink(link, other);

        assertThatThrownBy(() -> new TemplateVault(vaultsRoot).create(link))
                .isInstanceOf(ContractViolation.class)
                .hasMessageContaining("указывает не туда");
    }
}
