package uzelok.knowledge;

import java.time.Clock;

public final class ExternalObsidianStore extends DirVaultStore {

    public ExternalObsidianStore(
            VaultOptions options,
            ContractValidator validator,
            NameUniqueness uniqueness,
            NoteRenderer renderer,
            LinterRunner linter,
            VaultLockRegistry locks,
            Clock clock) {
        super(options, validator, uniqueness, renderer, linter, locks, clock);
    }

    public ExternalObsidianStore(
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
}
