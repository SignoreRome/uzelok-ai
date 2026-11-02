package uzelok.knowledge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.List;
import uzelok.domain.exception.ContractViolation;
import uzelok.domain.port.VaultProvisioner;

public final class TemplateVault implements VaultProvisioner {

    private static final List<String> TEMPLATE_FILES = List.of(
            "40_Knowledge/_meta/CONTRACT.md",
            "40_Knowledge/_meta/STRUCTURE.md",
            "40_Knowledge/_meta/taxonomy.yaml",
            "40_Knowledge/_meta/Note_Template.md",
            "40_Knowledge/_meta/Problem_Template.md");

    private static final List<String> EMPTY_DIRECTORIES = List.of(
            "40_Knowledge/_Inbox",
            ".uzelok-tmp");

    private final Path vaultsRoot;

    public TemplateVault(Path vaultsRoot) {
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

        for (String relative : TEMPLATE_FILES) {
            writeTemplateFile(root, relative);
        }
        for (String relative : EMPTY_DIRECTORIES) {
            try {
                Files.createDirectories(root.resolve(relative));
            } catch (IOException exception) {
                throw new ContractViolation("не удалось создать каталог базы: "
                        + relative + ": " + exception.getMessage());
            }
        }
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

    private static void writeTemplateFile(Path root, String relative) {
        byte[] content = template(relative);
        Path target = root.resolve(relative);
        if (Files.isRegularFile(target)) {
            byte[] existing = read(target);
            if (!Arrays.equals(existing, content)) {
                throw new ContractViolation(
                        "в каталоге базы уже есть файл с другим содержимым: " + relative);
            }
            return;
        }
        if (Files.exists(target)) {
            throw new ContractViolation(
                    "в каталоге базы уже есть файл с другим содержимым: " + relative);
        }
        Path temporary = null;
        try {
            Files.createDirectories(target.getParent());
            temporary = Files.createTempFile(target.getParent(), ".uzelok-template-", ".tmp");
            Files.write(temporary, content);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            temporary = null;
        } catch (IOException exception) {
            throw new ContractViolation("не удалось записать шаблон базы: "
                    + relative + ": " + exception.getMessage());
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // недописанный шаблон не должен блокировать повторный create
                }
            }
        }
    }

    private static byte[] template(String relative) {
        try (InputStream input = TemplateVault.class.getResourceAsStream(
                "/vault-template/" + relative)) {
            if (input == null) {
                throw new ContractViolation("шаблон базы повреждён: " + relative);
            }
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new ContractViolation("шаблон базы повреждён: " + relative);
        }
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException exception) {
            throw new ContractViolation("не удалось прочитать файл базы: " + file);
        }
    }

    private static void restrictPermissions(Path root) {
        try {
            if (root.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(root,
                        PosixFilePermissions.fromString("rwx------"));
            }
        } catch (IOException | UnsupportedOperationException exception) {
            // не POSIX-файловая система: права выставляются по умолчанию
        }
    }
}
