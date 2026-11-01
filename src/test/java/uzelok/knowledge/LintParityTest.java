package uzelok.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uzelok.domain.model.KnowledgeBase;
import uzelok.domain.model.NoteDraft;
import uzelok.domain.model.NoteType;
import uzelok.domain.model.RenderedNote;
import uzelok.domain.model.Taxonomy;

class LintParityTest {

    @TempDir
    Path root;

    @Test
    void renderedNotePassesExternalLinter() throws IOException {
        String command = lintCommand();
        Assumptions.assumeTrue(command != null, "внешний линтер не задан");

        VaultFixture.copyVault(root);
        KnowledgeBase base = VaultFixture.externalBase(root);
        Taxonomy taxonomy = new TaxonomyLoader().load(
                root.resolve(VaultFixture.TAXONOMY_REL_PATH));
        Set<String> existingNames = new NameUniqueness(new GitIndex())
                .existingNames(root.resolve("40_Knowledge"), root);

        RenderedNote note = new NoteRenderer(
                new FileNamePolicy(60), new ContentSanitizer(), 0.55)
                .render(taxonomy, base, UUID.randomUUID(), "12345678-0000-0000-0000-000000000000",
                        new NoteDraft("Lint Parity Note", NoteType.concept, "Two_Pointers",
                                "Заметка для проверки паритета линтера",
                                "## Тело\n\nТекст без исполняемых блоков.", 0.9),
                        false, VaultFixture.FIXED_CLOCK.instant()
                                .atZone(VaultFixture.FIXED_CLOCK.getZone()).toLocalDate(),
                        existingNames);
        Path target = root.resolve(note.relPath());
        Files.createDirectories(target.getParent());
        Files.writeString(target, note.markdown(), StandardCharsets.UTF_8);

        LinterRunner.LintResult result = new LinterRunner(command, 60).run(root);

        assertThat(result.ok()).as("линтер: %s", result.output()).isTrue();
    }

    @Test
    void noteWithoutDomainFailsExternalLinter() throws IOException {
        String command = lintCommand();
        Assumptions.assumeTrue(command != null, "внешний линтер не задан");

        VaultFixture.copyVault(root);
        Path target = root.resolve("40_Knowledge/Algorithms/Two_Pointers/Broken_Note.md");
        Files.createDirectories(target.getParent());
        Files.writeString(target, """
                ---
                type: concept
                topic: Two_Pointers
                status: raw
                source: telegram
                id: 33333333-3333-3333-3333-333333333333
                created: 2026-10-30
                updated: 2026-10-30
                summary: "Заметка без домена"
                ---

                ## Тело

                Текст.
                """, StandardCharsets.UTF_8);

        LinterRunner.LintResult result = new LinterRunner(command, 60).run(root);

        assertThat(result.ok()).as("линтер должен отклонить заметку без domain").isFalse();
    }

    @Test
    void inboxProblemNotePassesExternalLinter() throws IOException {
        String command = lintCommand();
        Assumptions.assumeTrue(command != null, "внешний линтер не задан");

        VaultFixture.copyVault(root);
        Path target = root.resolve("40_Knowledge/_Inbox/Problem_Sample.md");
        Files.createDirectories(target.getParent());
        Files.writeString(target, """
                ---
                type: problem
                domain: Algorithms
                topic: Graphs
                status: raw
                summary: "Задача во входящих"
                ---

                ## Условие

                Текст задачи.
                """, StandardCharsets.UTF_8);

        LinterRunner.LintResult result = new LinterRunner(command, 60).run(root);

        assertThat(result.ok()).as("линтер: %s", result.output()).isTrue();
    }

    private static String lintCommand() {
        String property = System.getProperty("agora.lint.command");
        if (property != null && !property.isBlank()) {
            return property;
        }
        String environment = System.getenv("AGORA_LINT");
        return environment == null || environment.isBlank() ? null : environment;
    }
}
