package uzelok.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uzelok.domain.exception.ContractViolation;
import uzelok.domain.model.KnowledgeBase;
import uzelok.domain.model.KnowledgeBaseKind;
import uzelok.domain.model.NoteDraft;
import uzelok.domain.model.NoteType;
import uzelok.domain.model.RenderedNote;
import uzelok.domain.model.Taxonomy;

class NoteRendererTest {

    private static final UUID NOTE_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final String JOB_REF = "12345678-aaaa-bbbb-cccc-dddddddddddd";
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 30);

    @TempDir
    Path root;

    private NoteRenderer renderer;
    private Taxonomy taxonomy;
    private KnowledgeBase base;

    @BeforeEach
    void setUp() {
        renderer = new NoteRenderer(new FileNamePolicy(60), new ContentSanitizer(), 0.55);
        taxonomy = new TaxonomyLoader().load(
                VaultFixture.resourcePath().resolve("40_Knowledge/_meta/taxonomy.yaml"));
        base = new KnowledgeBase(
                UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"),
                KnowledgeBaseKind.EXTERNAL_OBSIDIAN,
                root.toString(),
                VaultFixture.TAXONOMY_REL_PATH,
                VaultFixture.INBOX_REL_PATH);
    }

    @Test
    void rendersKnownTopicIntoDomainFolder() {
        NoteDraft draft = draft("Two Pointers Basics", NoteType.concept, "two_pointers",
                "Базовый обзор метода двух указателей",
                "## Идея\n\n$$E=mc^2$$\n\n| a | b |\n|---|---|\n| 1 | 2 |");

        RenderedNote note = renderer.render(taxonomy, base, NOTE_ID, JOB_REF, draft,
                false, TODAY, Set.of());

        assertThat(note.inbox()).isFalse();
        assertThat(note.relPath())
                .isEqualTo("40_Knowledge/Algorithms/Two_Pointers/Two_Pointers_Basics.md");
        assertThat(note.fileName()).isEqualTo("Two_Pointers_Basics.md");
        assertThat(note.noteId()).isEqualTo(NOTE_ID);
        assertThat(note.markdown()).startsWith("---\n");
        assertThat(note.markdown()).contains("$$E=mc^2$$", "| a | b |");
        assertThat(note.markdown()).endsWith("\n");

        String frontmatter = note.frontmatter();
        assertThat(frontmatter).contains(
                "domain: Algorithms",
                "topic: Two_Pointers",
                "status: raw",
                "source: telegram",
                "id: " + NOTE_ID);
        assertThat(frontmatter).containsPattern("created: '?2026-10-30'?");
        assertThat(frontmatter).containsPattern("updated: '?2026-10-30'?");
        assertThat(frontmatter.indexOf("type:"))
                .isLessThan(frontmatter.indexOf("domain:"))
                .isLessThan(frontmatter.indexOf("topic:"))
                .isLessThan(frontmatter.indexOf("status:"))
                .isLessThan(frontmatter.indexOf("source:"))
                .isLessThan(frontmatter.indexOf("id:"))
                .isLessThan(frontmatter.indexOf("created:"))
                .isLessThan(frontmatter.indexOf("updated:"))
                .isLessThan(frontmatter.indexOf("summary:"));
    }

    @Test
    void normalizesWindowsLineEndingsAndTrailingSpaces() {
        NoteDraft draft = draft("Line Breaks", NoteType.concept, "Graphs", "Переносы строк",
                "Первая строка   \r\nВторая строка\n\n\n");

        RenderedNote note = renderer.render(taxonomy, base, NOTE_ID, JOB_REF, draft,
                false, TODAY, Set.of());

        assertThat(note.markdown()).doesNotContain("\r", "   \n");
        assertThat(note.markdown()).endsWith("Вторая строка\n");
    }

    @Test
    void truncatesLongSummary() {
        NoteDraft draft = draft("Long Summary", NoteType.concept, "Graphs", "слово ".repeat(60),
                "Тело");

        RenderedNote note = renderer.render(taxonomy, base, NOTE_ID, JOB_REF, draft,
                false, TODAY, Set.of());

        assertThat(note.frontmatter()).contains("…");
        assertThat(note.frontmatter()).doesNotContain("слово ".repeat(60));
    }

    @Test
    void sendsUnknownTopicToInbox() {
        NoteDraft draft = draft("Неизвестная тема", NoteType.concept, "unknown_topic",
                "Тема не найдена", "Тело");

        RenderedNote note = renderer.render(taxonomy, base, NOTE_ID, JOB_REF, draft,
                false, TODAY, Set.of());

        assertThat(note.inbox()).isTrue();
        assertThat(note.relPath()).isEqualTo("40_Knowledge/_Inbox/Neizvestnaya_Tema.md");
        assertThat(note.warnings()).anyMatch(warning -> warning.contains("не найдена"));
        assertThat(note.frontmatter()).doesNotContain("domain:", "topic:");
    }

    @Test
    void forceInboxKeepsResolvedDomainInFrontmatter() {
        NoteDraft draft = draft("Forced Inbox", NoteType.concept, "Two_Pointers",
                "Принудительно во входящие", "Тело");

        RenderedNote note = renderer.render(taxonomy, base, NOTE_ID, JOB_REF, draft,
                true, TODAY, Set.of());

        assertThat(note.inbox()).isTrue();
        assertThat(note.relPath()).isEqualTo("40_Knowledge/_Inbox/Forced_Inbox.md");
        assertThat(note.frontmatter()).contains("domain: Algorithms", "topic: Two_Pointers");
    }

    @Test
    void sendsProblemWithoutProblemFieldsToInbox() {
        NoteDraft draft = draft("Problem Note", NoteType.problem, "Graphs",
                "Задача без полей", "Тело");

        RenderedNote note = renderer.render(taxonomy, base, NOTE_ID, JOB_REF, draft,
                false, TODAY, Set.of());

        assertThat(note.inbox()).isTrue();
        assertThat(note.relPath()).isEqualTo("40_Knowledge/_Inbox/Problem_Note.md");
        assertThat(note.warnings()).anyMatch(warning -> warning.contains("problem_*"));
    }

    @Test
    void resolvesNameCollisionWithJobSuffixInInbox() {
        NoteDraft draft = draft("Two Pointers Basics", NoteType.concept, "Two_Pointers",
                "Коллизия имени", "Тело");

        RenderedNote note = renderer.render(taxonomy, base, NOTE_ID, JOB_REF, draft,
                false, TODAY, Set.of("two_pointers_basics"));

        assertThat(note.inbox()).isTrue();
        assertThat(note.relPath())
                .isEqualTo("40_Knowledge/_Inbox/Two_Pointers_Basics_12345678.md");
        assertThat(note.warnings()).anyMatch(warning -> warning.contains("имя занято"));
    }

    @Test
    void sanitizesCollisionSuffixFromJobRef() {
        NoteDraft draft = draft("Two Pointers Basics", NoteType.concept, "Two_Pointers",
                "Коллизия имени", "Тело");

        RenderedNote note = renderer.render(taxonomy, base, NOTE_ID, "ab cd/ef", draft,
                false, TODAY, Set.of("two_pointers_basics"));

        assertThat(note.inbox()).isTrue();
        assertThat(note.fileName()).isEqualTo("Two_Pointers_Basics_ab_cd_ef.md");
        assertThat(note.fileName().replace(".md", "")).matches("[A-Za-z0-9_]+");
    }

    @Test
    void fallsBackToNoteIdWhenJobRefSuffixIsEmpty() {
        NoteDraft draft = draft("Two Pointers Basics", NoteType.concept, "Two_Pointers",
                "Коллизия имени", "Тело");

        RenderedNote note = renderer.render(taxonomy, base, NOTE_ID, "....", draft,
                false, TODAY, Set.of("two_pointers_basics"));

        assertThat(note.inbox()).isTrue();
        assertThat(note.fileName()).isEqualTo("Two_Pointers_Basics_aaaaaaaa.md");
        assertThat(note.fileName().replace(".md", "")).matches("[A-Za-z0-9_]+");
    }

    @Test
    void removesUnknownWikilinksAndKeepsKnownOnes() {
        NoteDraft draft = draft("Links", NoteType.concept, "Graphs", "Ссылки",
                "См. [[Missing Note]] и [[Known_Note|известная]]. Ещё [[Two_Pointers]].");

        RenderedNote note = renderer.render(taxonomy, base, NOTE_ID, JOB_REF, draft,
                false, TODAY, Set.of("known_note", "two_pointers"));

        assertThat(note.markdown()).contains(
                "См. Missing Note и [[Known_Note|известная]]",
                "Ещё [[Two_Pointers]]");
        assertThat(note.warnings()).anyMatch(warning ->
                warning.contains("[[Missing Note]] вырезана: заметки нет"));
    }

    @Test
    void warnsWhenConfidenceBelowConstructorThreshold() {
        NoteDraft draft = new NoteDraft("Confidence", NoteType.concept, "Graphs",
                "Проверка порога", "Тело", 0.8);
        NoteRenderer strict = new NoteRenderer(
                new FileNamePolicy(60), new ContentSanitizer(), 0.9);

        RenderedNote strictNote = strict.render(taxonomy, base, NOTE_ID, JOB_REF, draft,
                false, TODAY, Set.of());

        assertThat(strictNote.warnings()).anyMatch(warning ->
                warning.contains("низкая уверенность"));

        NoteRenderer lenient = new NoteRenderer(
                new FileNamePolicy(60), new ContentSanitizer(), 0.5);
        RenderedNote lenientNote = lenient.render(taxonomy, base, NOTE_ID, JOB_REF, draft,
                false, TODAY, Set.of());

        assertThat(lenientNote.warnings()).noneMatch(warning ->
                warning.contains("низкая уверенность"));
    }

    @Test
    void rejectsAbsolutePathInProse() {
        NoteDraft draft = draft("Absolute Path", NoteType.concept, "Graphs", "Путь в прозе",
                "Файл лежит в /mnt/hdd/workspace/agora/40_Knowledge.");

        assertThatThrownBy(() -> renderer.render(taxonomy, base, NOTE_ID, JOB_REF, draft,
                false, TODAY, Set.of()))
                .isInstanceOf(ContractViolation.class)
                .hasMessageContaining("абсолютный путь");
    }

    @Test
    void allowsAbsolutePathInsideCodeBlocksAndInlineCode() {
        NoteDraft draft = draft("Code Path", NoteType.concept, "Graphs", "Путь в коде",
                "Файл:\n\n```\n/mnt/hdd/workspace/agora/40_Knowledge\n```\n\n"
                        + "Инлайн `/home/roman/notes` тоже допустим.");

        RenderedNote note = renderer.render(taxonomy, base, NOTE_ID, JOB_REF, draft,
                false, TODAY, Set.of());

        assertThat(note.markdown()).contains("/mnt/hdd/workspace/agora/40_Knowledge",
                "/home/roman/notes");
    }

    @Test
    void rejectsMissingType() {
        NoteDraft draft = new NoteDraft("No Type", null, "Graphs", "Нет типа", "Тело", 0.9);

        assertThatThrownBy(() -> renderer.render(taxonomy, base, NOTE_ID, JOB_REF, draft,
                false, TODAY, Set.of()))
                .isInstanceOf(ContractViolation.class)
                .hasMessageContaining("тип");
    }

    @Test
    void rejectsEmptySummary() {
        NoteDraft draft = draft("No Summary", NoteType.concept, "Graphs", "  ", "Тело");

        assertThatThrownBy(() -> renderer.render(taxonomy, base, NOTE_ID, JOB_REF, draft,
                false, TODAY, Set.of()))
                .isInstanceOf(ContractViolation.class)
                .hasMessageContaining("summary");
    }

    private static NoteDraft draft(
            String title, NoteType type, String topic, String summary, String body) {
        return new NoteDraft(title, type, topic, summary, body, 0.9);
    }
}
