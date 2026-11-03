package uzelok.knowledge;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import uzelok.domain.exception.ContractViolation;
import uzelok.domain.model.KnowledgeBase;
import uzelok.domain.model.Taxonomy;

public final class PlainVaultStore extends DirVaultStore {

    public PlainVaultStore(
            VaultOptions options,
            ContractValidator validator,
            NameUniqueness uniqueness,
            FileNamePolicy names,
            ContentSanitizer sanitizer,
            LinterRunner linter,
            VaultLockRegistry locks,
            Clock clock) {
        super(options, validator, uniqueness, names, sanitizer, linter, locks, clock);
    }

    @Override
    protected Layout layout(KnowledgeBase base) {
        Path root = Path.of(base.rootPath()).toAbsolutePath().normalize();
        return new Layout(root.resolve(PlainVault.NOTES_DIRECTORY), Taxonomy.free(), List.of());
    }

    @Override
    protected boolean lintRequired() {
        return false;
    }

    @Override
    protected void verifyParent(KnowledgeBase base, Layout layout, Path relativeParent) {
        String folder = relativeParent.getNameCount() == 1
                ? relativeParent.getFileName().toString()
                : null;
        if (folder == null || !names.isValid(folder)) {
            throw new ContractViolation(
                    "запись разрешена только в каталог темы внутри notes: " + relativeParent);
        }
    }
}
