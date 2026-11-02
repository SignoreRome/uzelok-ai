package uzelok.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uzelok.domain.model.ChatAccess;
import uzelok.domain.model.JobStatus;
import uzelok.domain.model.LlmCallRecord;
import uzelok.domain.model.NoteRecord;
import uzelok.domain.model.NoteType;
import uzelok.domain.model.ProcessingJob;
import uzelok.domain.model.SourceItem;
import uzelok.domain.model.SourceKind;
import uzelok.support.InMemoryAccessRepository;
import uzelok.support.InMemoryJobRepository;
import uzelok.support.InMemoryLlmCallRepository;
import uzelok.support.InMemoryNoteRepository;
import uzelok.support.InMemorySourceItemRepository;
import uzelok.pipeline.StatsService;
import uzelok.support.InMemoryTelegramGateway;
import uzelok.support.MutableClock;
import uzelok.support.FixedKnowledgeBaseRepository;
import uzelok.support.RecordingAuditRepository;
import uzelok.support.StubVaultExporter;

class CommandHandlerTest {

    private static final long CHAT_ID = 100L;
    private static final long FROM_ID = 200L;
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID APP_USER_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-05-01T12:00:00Z");

    private InMemoryTelegramGateway telegram;
    private InMemorySourceItemRepository source;
    private InMemoryJobRepository jobs;
    private InMemoryNoteRepository notes;
    private InMemoryLlmCallRepository calls;
    private CommandHandler handler;

    @BeforeEach
    void setUp() {
        telegram = new InMemoryTelegramGateway();
        source = new InMemorySourceItemRepository();
        jobs = new InMemoryJobRepository(source);
        notes = new InMemoryNoteRepository();
        calls = new InMemoryLlmCallRepository();
        InMemoryAccessRepository access = new InMemoryAccessRepository()
                .add(CHAT_ID, new ChatAccess(TENANT_ID, APP_USER_ID, FROM_ID, true));
        handler = new CommandHandler(telegram, new Messages(), "test_bot",
                Map.of(
                        "extract", new CommandHandler.LlmStep("opencode-go", "claude-haiku-5-5",
                                "gemini-4-argon"),
                        "draft", new CommandHandler.LlmStep("opencode-go", "gemini-4-argon",
                                "deepseek-v4.1-flash")),
                access, jobs, new StatsService(notes, jobs, calls),
                new FixedKnowledgeBaseRepository(null), new StubVaultExporter(),
                new RecordingAuditRepository(), new MutableClock(NOW));
    }

    @Test
    void startCommandSendsInstructions() {
        assertThat(handler.handle(CHAT_ID, "/start")).isTrue();
        assertThat(telegram.lastSent().orElseThrow().text()).contains("Привет");
    }

    @Test
    void helpCommandSendsInstructions() {
        assertThat(handler.handle(CHAT_ID, "/help")).isTrue();
        assertThat(telegram.lastSent().orElseThrow().text()).contains("Отправьте");
    }

    @Test
    void startWithBotUsernameIsAccepted() {
        assertThat(handler.handle(CHAT_ID, "/start@test_bot")).isTrue();
        assertThat(telegram.lastSent().orElseThrow().text()).contains("Привет");
    }

    @Test
    void commandForAnotherBotSaysLater() {
        assertThat(handler.handle(CHAT_ID, "/start@other_bot")).isTrue();
        assertThat(telegram.lastSent().orElseThrow().text()).contains("позже");
    }

    @Test
    void unknownCommandSaysLater() {
        assertThat(handler.handle(CHAT_ID, "/unknown")).isTrue();
        assertThat(telegram.lastSent().orElseThrow().text()).contains("позже");
    }

    @Test
    void statsCommandReportsNotesJobsAndSpend() {
        notes.upsert(new NoteRecord(UUID.randomUUID(), TENANT_ID, UUID.randomUUID(),
                UUID.randomUUID(), NoteType.concept, "Заметка", "Algorithms", "Graphs",
                "Test.md", "40_Knowledge/Algorithms/Graphs/Test.md", "hash"));
        source.ensure(new SourceItem(UUID.randomUUID(), TENANT_ID, UUID.randomUUID(), CHAT_ID,
                500L, SourceKind.TEXT, null, "текст", NOW));
        ProcessingJob job = jobs.createIfAbsent(new ProcessingJob(UUID.randomUUID(), TENANT_ID,
                source.all().getFirst().id(), UUID.randomUUID(), JobStatus.RECEIVED, null, 0,
                NOW, null, null, null, null, null, NOW, NOW, 0, false)).job();
        calls.recordAt(new LlmCallRecord(TENANT_ID, job.id(), "EXTRACT", "p", "m", "v1",
                "key", 1, 1, new BigDecimal("0.25"), 10, true, "{}"), NOW);

        handler.handle(CHAT_ID, "/stats");

        String text = telegram.lastSent().orElseThrow().text();
        assertThat(text).contains("Заметок: 1");
        assertThat(text).contains("Задач за 24 часа: 1");
        assertThat(text).contains("Потрачено за 24 часа: $0.25");
        assertThat(text).contains("Потрачено за месяц: $0.25");
    }

    @Test
    void aiModeReportsProviderModelAndFallback() {
        handler.handle(CHAT_ID, "/ai_mode");

        String text = telegram.lastSent().orElseThrow().text();
        assertThat(text).contains("extract: opencode-go / claude-haiku-5-5");
        assertThat(text).contains("draft: opencode-go / gemini-4-argon");
        assertThat(text).contains("fallback:");
    }

    @Test
    void cancelMovesActiveJobToCancelled() {
        SourceItem item = source.ensure(new SourceItem(UUID.randomUUID(), TENANT_ID,
                UUID.randomUUID(), CHAT_ID, 501L, SourceKind.TEXT, null, "текст", NOW)).item();
        ProcessingJob job = jobs.createIfAbsent(new ProcessingJob(UUID.randomUUID(), TENANT_ID,
                item.id(), item.knowledgeBaseId(), JobStatus.AWAITING_CONFIRM, null, 0, NOW,
                null, null, null, null, null, NOW, NOW, 0, false)).job();

        handler.handle(CHAT_ID, "/cancel");

        assertThat(jobs.find(job.id()).orElseThrow().status()).isEqualTo(JobStatus.CANCELLED);
        assertThat(telegram.lastSent().orElseThrow().text()).isEqualTo("Задача отменена.");
    }

    @Test
    void plainTextIsNotACommand() {
        assertThat(handler.handle(CHAT_ID, "просто текст")).isFalse();
        assertThat(telegram.sent()).isEmpty();
    }
}
