package uzelok.knowledge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import uzelok.domain.exception.ContractViolation;
import uzelok.domain.model.KnowledgeBase;
import uzelok.domain.model.Taxonomy;

public final class ContractValidator {

    private final TaxonomyLoader taxonomyLoader;

    public ContractValidator() {
        this(new TaxonomyLoader());
    }

    public ContractValidator(TaxonomyLoader taxonomyLoader) {
        this.taxonomyLoader = taxonomyLoader;
    }

    public record ContractReport(Path knowledgeRoot, Taxonomy taxonomy, List<String> warnings) {

        public ContractReport {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    public ContractReport validate(KnowledgeBase base) throws ContractViolation {
        if (base.taxonomyRelPath() == null || base.taxonomyRelPath().isBlank()
                || base.inboxRelPath() == null || base.inboxRelPath().isBlank()) {
            throw new ContractViolation("в записи базы не задан путь словаря/входящих");
        }
        Path root = Path.of(base.rootPath()).toAbsolutePath().normalize();
        if (!Files.exists(root)) {
            throw new ContractViolation("корень базы знаний не существует: " + root);
        }
        if (!Files.isDirectory(root)) {
            throw new ContractViolation("корень базы знаний не является каталогом: " + root);
        }
        if (!Files.isReadable(root)) {
            throw new ContractViolation("корень базы знаний недоступен на чтение: " + root);
        }

        Path taxonomyFile = resolveInside(root, base.taxonomyRelPath());
        if (!Files.exists(taxonomyFile)) {
            throw new ContractViolation("файл словаря таксономии не найден: " + taxonomyFile);
        }
        if (!Files.isRegularFile(taxonomyFile) || !Files.isReadable(taxonomyFile)) {
            throw new ContractViolation("файл словаря таксономии недоступен на чтение: " + taxonomyFile);
        }
        Taxonomy taxonomy = taxonomyLoader.load(taxonomyFile);

        Path metaFolder = taxonomyFile.getParent();
        Path knowledgeRoot = metaFolder != null && metaFolder.getParent() != null
                ? metaFolder.getParent()
                : metaFolder != null ? metaFolder : root;
        Path contractFile = metaFolder == null
                ? knowledgeRoot.resolve("CONTRACT.md")
                : metaFolder.resolve("CONTRACT.md");
        if (!Files.exists(contractFile)) {
            throw new ContractViolation("в базе нет контракта " + contractFile);
        }

        List<String> warnings = new ArrayList<>();
        Path inbox = resolveInside(root, base.inboxRelPath());
        if (!Files.isDirectory(inbox)) {
            warnings.add("нет каталога _Inbox, будет создан при записи");
        }

        Path gitDirectory = root.resolve(".git");
        if (Files.isDirectory(gitDirectory)) {
            if (!gitignoreHasTmpDirectory(root.resolve(".gitignore"))) {
                warnings.add("добавьте .uzelok-tmp/ в .gitignore базы");
            }
            if (!gitAvailable()) {
                warnings.add("git недоступен, проверка индекса базы пропущена");
            }
        }

        return new ContractReport(knowledgeRoot, taxonomy, warnings);
    }

    private static Path resolveInside(Path root, String relativePath) {
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root)) {
            throw new ContractViolation("путь выходит за корень базы: " + relativePath);
        }
        return resolved;
    }

    private static boolean gitignoreHasTmpDirectory(Path gitignore) {
        if (!Files.isRegularFile(gitignore)) {
            return false;
        }
        try {
            return Files.readAllLines(gitignore, StandardCharsets.UTF_8).stream()
                    .map(String::trim)
                    .anyMatch(".uzelok-tmp/"::equals);
        } catch (IOException exception) {
            return false;
        }
    }

    private static boolean gitAvailable() {
        try {
            Process process = new ProcessBuilder("git", "--version")
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException exception) {
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
