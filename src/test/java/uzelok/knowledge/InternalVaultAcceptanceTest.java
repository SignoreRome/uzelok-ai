package uzelok.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import uzelok.domain.model.KnowledgeBase;
import uzelok.domain.model.KnowledgeBaseKind;
import uzelok.domain.model.NoteDraft;
import uzelok.domain.model.NoteType;
import uzelok.domain.port.PreparedNote;
import uzelok.domain.port.WrittenNote;

@EnabledIfEnvironmentVariable(named = "AGORA_ROOT", matches = ".+")
class InternalVaultAcceptanceTest {

    private static final String DEFAULT_LINTER = "/home/roman/.hermes/scripts/agora_lint.py";
    private static final String JOB_REF = "abcdef12-0000-0000-0000-000000000000";

    @TempDir
    Path root;

    @Test
    void createdInternalVaultPassesRealLinterBeforeAndAfterWrite() throws IOException {
        String lintCommand = linterCommand();
        Path vaultRoot = new TemplateVault(root).create(root.resolve("internal"));
        KnowledgeBase base = new KnowledgeBase(UUID.randomUUID(), KnowledgeBaseKind.INTERNAL_DIR,
                vaultRoot.toString(), VaultFixture.TAXONOMY_REL_PATH, VaultFixture.INBOX_REL_PATH);
        InternalDirStore store = new InternalDirStore(
                VaultFixture.options(),
                new ContractValidator(),
                new NameUniqueness(new GitIndex()),
                new FileNamePolicy(60),
                new ContentSanitizer(),
                new LinterRunner(lintCommand, 60),
                new VaultLockRegistry(),
                Clock.systemUTC());

        LinterRunner.LintResult before = new LinterRunner(lintCommand, 60).run(vaultRoot);
        assertThat(before.ok()).as("линтер на созданной базе: %s", before.output()).isTrue();

        PreparedNote prepared = store.prepare(base, JOB_REF, new NoteDraft(
                "Acceptance Internal Two Pointers",
                NoteType.concept,
                "Two_Pointers",
                "Приёмочная заметка для внутренней базы",
                "## Идея\n\nДва указателя двигаются по массиву.",
                0.93), false);
        WrittenNote written = store.write(base, prepared);

        assertThat(written.created()).isTrue();
        assertThat(Files.readString(vaultRoot.resolve(written.relPath())))
                .isEqualTo(prepared.markdown());
        LinterRunner.LintResult after = new LinterRunner(lintCommand, 60).run(vaultRoot);
        assertThat(after.ok()).as("линтер после записи: %s", after.output()).isTrue();
    }

    private static String linterCommand() {
        String environment = System.getenv("AGORA_LINT");
        if (environment != null && !environment.isBlank()) {
            return environment;
        }
        Path defaultScript = Path.of(DEFAULT_LINTER);
        if (Files.isRegularFile(defaultScript) && !Files.isExecutable(defaultScript)) {
            return "python3 " + DEFAULT_LINTER;
        }
        return DEFAULT_LINTER;
    }
}
