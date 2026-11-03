package uzelok.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uzelok.domain.exception.ContractViolation;

class PlainVaultTest {

    @TempDir
    Path root;

    @Test
    void createsNotesDirectoryAndReadmeWithoutContract() {
        Path created = new PlainVault(root).create(root.resolve("vault"));

        assertThat(created.resolve("notes")).isDirectory();
        assertThat(created.resolve("README.md")).isRegularFile();
        assertThat(created.resolve("README.md")).content().contains("notes/", "/export");
        assertThat(created.resolve("40_Knowledge")).doesNotExist();
        assertThat(created.resolve("40_Knowledge/_meta")).doesNotExist();
        assertThat(created.resolve("40_Knowledge/_Inbox")).doesNotExist();
        assertThat(created.resolve("40_Knowledge/_meta/taxonomy.yaml")).doesNotExist();
    }

    @Test
    void repeatedCreateDoesNotChangeFiles() throws IOException {
        PlainVault vault = new PlainVault(root);
        Path created = vault.create(root.resolve("vault"));
        byte[] readme = Files.readAllBytes(created.resolve("README.md"));

        Path second = vault.create(created);

        assertThat(second).isEqualTo(created);
        assertThat(Files.readAllBytes(created.resolve("README.md"))).isEqualTo(readme);
    }

    @Test
    void rejectsModifiedReadme() throws IOException {
        PlainVault vault = new PlainVault(root);
        Path created = vault.create(root.resolve("vault"));
        Files.writeString(created.resolve("README.md"), "изменено");

        assertThatThrownBy(() -> vault.create(created))
                .isInstanceOf(ContractViolation.class)
                .hasMessageContaining("другим содержимым");
    }

    @Test
    void createsVaultsRootTenantAndNotesDirectoriesWithPrivatePermissions() throws IOException {
        Assumptions.assumeTrue(
                root.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path vaultsRoot = root.resolve("vaults");

        Path created = new PlainVault(vaultsRoot).create(vaultsRoot.resolve("tenant-1"));

        assertThat(Files.getPosixFilePermissions(vaultsRoot))
                .isEqualTo(PosixFilePermissions.fromString("rwx------"));
        assertThat(Files.getPosixFilePermissions(created))
                .isEqualTo(PosixFilePermissions.fromString("rwx------"));
        assertThat(Files.getPosixFilePermissions(created.resolve("notes")))
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

        assertThatThrownBy(() -> new PlainVault(vaultsRoot).create(link))
                .isInstanceOf(ContractViolation.class)
                .hasMessageContaining("указывает не туда");
    }
}
