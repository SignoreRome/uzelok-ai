package uzelok.knowledge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import uzelok.domain.exception.ContractViolation;
import uzelok.domain.port.VaultProvisioner;

public final class PlainVault implements VaultProvisioner {

    public static final String NOTES_DIRECTORY = "notes";
    private static final String README_FILE = "README.md";

    private final Path vaultsRoot;

    public PlainVault(Path vaultsRoot) {
        this.vaultsRoot = vaultsRoot.toAbsolutePath().normalize();
    }

    @Override
    public Path create(Path rootPath) throws ContractViolation {
        createVaultsRoot();
        Path root = rootPath.toAbsolutePath().normalize();
        if (!vaultsRoot.equals(root.getParent())) {
            throw new ContractViolation("каталог базы указывает не туда: " + root);
        }
        try {
            Files.createDirectories(root);
        } catch (IOException exception) {
            throw new ContractViolation("не удалось создать каталог базы: " + exception.getMessage());
        }

        Path realRoot;
        try {
            realRoot = root.toRealPath();
        } catch (IOException exception) {
            throw new ContractViolation("не удалось определить канонический корень базы: "
                    + exception.getMessage());
        }
        if (!realRoot.equals(vaultsRoot.resolve(root.getFileName()).normalize())) {
            throw new ContractViolation("каталог базы указывает не туда: " + root);
        }
        restrictPermissions(root);

        Path notes = root.resolve(NOTES_DIRECTORY);
        try {
            Files.createDirectories(notes);
        } catch (IOException exception) {
            throw new ContractViolation("не удалось создать каталог заметок: "
                    + exception.getMessage());
        }
        restrictPermissions(notes);
        writeReadme(root);
        return realRoot;
    }

    private void createVaultsRoot() {
        try {
            Files.createDirectories(vaultsRoot);
        } catch (IOException exception) {
            throw new ContractViolation("не удалось создать каталог баз: "
                    + exception.getMessage());
        }
        restrictPermissions(vaultsRoot);
    }

    private static void writeReadme(Path root) {
        byte[] content = readme();
        Path target = root.resolve(README_FILE);
        if (Files.isRegularFile(target)) {
            if (!Arrays.equals(read(target), content)) {
                throw new ContractViolation(
                        "в каталоге базы уже есть файл с другим содержимым: " + README_FILE);
            }
            return;
        }
        if (Files.exists(target)) {
            throw new ContractViolation(
                    "в каталоге базы уже есть файл с другим содержимым: " + README_FILE);
        }
        try {
            Files.write(target, content);
        } catch (IOException exception) {
            throw new ContractViolation("не удалось записать README базы: "
                    + exception.getMessage());
        }
    }

    private static byte[] readme() {
        try (InputStream input = PlainVault.class.getResourceAsStream(
                "/vault-template/" + README_FILE)) {
            if (input == null) {
                throw new ContractViolation("шаблон базы повреждён: " + README_FILE);
            }
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new ContractViolation("шаблон базы повреждён: " + README_FILE);
        }
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException exception) {
            throw new ContractViolation("не удалось прочитать файл базы: " + file);
        }
    }

    private static void restrictPermissions(Path path) {
        try {
            if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
            }
        } catch (IOException | UnsupportedOperationException exception) {
            // не POSIX-файловая система: права выставляются по умолчанию
        }
    }
}
