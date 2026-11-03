package uzelok.knowledge;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.stream.Stream;
import uzelok.domain.model.KnowledgeBase;
import uzelok.domain.model.KnowledgeBaseKind;

final class VaultFixture {

    static final String TAXONOMY_REL_PATH = "40_Knowledge/_meta/taxonomy.yaml";
    static final String INBOX_REL_PATH = "40_Knowledge/_Inbox";
    static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-10-30T12:00:00Z"), ZoneOffset.UTC);

    private VaultFixture() {
    }

    static Path copyVault(Path target) throws IOException {
        Path source = resourcePath();
        try (Stream<Path> walk = Files.walk(source)) {
            walk.forEach(path -> {
                Path destination = target.resolve(source.relativize(path).toString());
                try {
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(destination);
                    } else {
                        Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
        return target;
    }

    static Path resourcePath() {
        try {
            return Path.of(VaultFixture.class.getResource("/vault-fixture").toURI());
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("фикстура vault-fixture недоступна", exception);
        }
    }

    static KnowledgeBase externalBase(Path root) {
        return new KnowledgeBase(
                UUID.randomUUID(),
                KnowledgeBaseKind.EXTERNAL_OBSIDIAN,
                root.toString(),
                TAXONOMY_REL_PATH,
                INBOX_REL_PATH);
    }

    static VaultOptions options() {
        return new VaultOptions(0.55);
    }

    static ExternalObsidianStore store(String lintCommand) {
        return store(lintCommand, FIXED_CLOCK);
    }

    static ExternalObsidianStore store(String lintCommand, Clock clock) {
        return new ExternalObsidianStore(
                options(),
                new ContractValidator(),
                new NameUniqueness(),
                new FileNamePolicy(60),
                new ContentSanitizer(),
                new LinterRunner(lintCommand, 60),
                new VaultLockRegistry(),
                clock);
    }
}
