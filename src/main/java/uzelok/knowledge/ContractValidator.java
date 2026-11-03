package uzelok.knowledge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import uzelok.domain.exception.ContractViolation;
import uzelok.domain.model.KnowledgeBase;
import uzelok.domain.model.KnowledgeBaseKind;
import uzelok.domain.model.Taxonomy;
import uzelok.domain.port.VaultInspector;

public final class ContractValidator implements VaultInspector {

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

        return new ContractReport(knowledgeRoot, taxonomy, warnings);
    }

    @Override
    public List<String> violations(Path rootPath) {
        if (rootPath == null) {
            return List.of("путь базы не задан");
        }
        if (!rootPath.isAbsolute()) {
            return List.of("нужен абсолютный путь к базе: " + rootPath);
        }
        Path root = rootPath.normalize();
        if (!Files.isDirectory(root)) {
            return List.of("каталог базы не найден: " + root);
        }
        KnowledgeBase base = new KnowledgeBase(
                UUID.randomUUID(),
                KnowledgeBaseKind.INTERNAL_DIR,
                root.toString(),
                "40_Knowledge/_meta/taxonomy.yaml",
                "40_Knowledge/_Inbox");
        try {
            validate(base);
            return List.of();
        } catch (ContractViolation exception) {
            return List.of(exception.getMessage());
        }
    }

    private static Path resolveInside(Path root, String relativePath) {
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root)) {
            throw new ContractViolation("путь выходит за корень базы: " + relativePath);
        }
        return resolved;
    }

}
