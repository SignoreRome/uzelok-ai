package uzelok.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uzelok.config.UzelokProperties;
import uzelok.domain.model.ActionOutcome;
import uzelok.domain.model.ChatAccess;
import uzelok.domain.model.KnowledgeBaseKind;
import uzelok.knowledge.ContractValidator;
import uzelok.knowledge.PlainVault;
import uzelok.pipeline.OnboardingService;
import uzelok.pipeline.StatsService;
import uzelok.support.InMemoryAccessRepository;
import uzelok.support.InMemoryAttachmentRepository;
import uzelok.support.InMemoryJobRepository;
import uzelok.support.InMemoryKnowledgeBaseRepository;
import uzelok.support.InMemoryLlmCallRepository;
import uzelok.support.InMemoryNoteRepository;
import uzelok.support.InMemoryOnboardingSessionRepository;
import uzelok.support.InMemoryPendingActionRepository;
import uzelok.support.InMemorySourceItemRepository;
import uzelok.support.InMemoryTelegramGateway;
import uzelok.support.RecordingAuditRepository;
import uzelok.support.StubVaultExporter;
import uzelok.support.TelegramUpdates;

class OnboardingFlowTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID APP_USER_ID = UUID.randomUUID();
    private static final long CHAT_ID = 100L;
    private static final long FROM_ID = 200L;
    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");

    @TempDir
    Path root;

    private InMemorySourceItemRepository source;
    private InMemoryJobRepository jobs;
    private InMemoryTelegramGateway telegram;
    private InMemoryKnowledgeBaseRepository bases;
    private InMemoryOnboardingSessionRepository sessions;
    private RecordingAuditRepository audit;
    private Messages messages;
    private CallbackHandler callbacks;
    private MessageHandler handler;
    private Path externalVault;

    @BeforeEach
    void setUp() throws IOException {
        InMemoryAttachmentRepository attachments = new InMemoryAttachmentRepository();
        jobs = new InMemoryJobRepository(source = new InMemorySourceItemRepository());
        telegram = new InMemoryTelegramGateway();
        audit = new RecordingAuditRepository();
        bases = new InMemoryKnowledgeBaseRepository();
        sessions = new InMemoryOnboardingSessionRepository();
        messages = new Messages();
        InMemoryAccessRepository access = new InMemoryAccessRepository()
                .add(CHAT_ID, new ChatAccess(TENANT_ID, APP_USER_ID, FROM_ID, true));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        UzelokProperties.Telegram telegramProps = new UzelokProperties.Telegram(
                "token", "test_bot", "", 30, 2000, 8000, 15_728_640L, 1_048_576L, 30_000_000L);
        UzelokProperties.Limits limits = new UzelokProperties.Limits(20, 5, 72);

        OnboardingService onboarding = new OnboardingService(bases, sessions,
                new PlainVault(root.resolve("vaults")), new ContractValidator(), audit, clock,
                root.resolve("vaults"));
        CommandHandler commands = new CommandHandler(telegram, messages, "test_bot", Map.of(),
                access, jobs, new StatsService(new InMemoryNoteRepository(), jobs,
                        new InMemoryLlmCallRepository()), bases, new StubVaultExporter(), audit,
                clock);
        callbacks = new CallbackHandler(access, new InMemoryPendingActionRepository(),
                action -> ActionOutcome.ignored(), onboarding, telegram, messages, audit, clock);
        AttachmentReceiver receiver = new AttachmentReceiver(telegramProps,
                new UzelokProperties.Storage(root.resolve("artifacts").toString(),
                        "/var/lib/uzelok/vaults", "/var/lib/uzelok/exports"),
                attachments, telegram, messages);
        handler = new MessageHandler(access, bases, source, attachments, jobs, audit, telegram,
                commands, receiver, clock, limits, telegramProps, messages, onboarding);

        externalVault = copyVault(root.resolve("external"));
    }

    @Test
    void messageWithoutBaseSendsPromptWithOnboardingKeyboard() {
        handler.handle(TelegramUpdates.text(1, CHAT_ID, 500, FROM_ID, "заметка"));

        var sent = telegram.lastSent().orElseThrow();
        assertThat(sent.text()).isEqualTo(messages.text("base.prompt"));
        assertThat(sent.keyboard()).isNotNull();
        assertThat(sent.keyboard().rows().getFirst())
                .extracting(button -> button.callbackData())
                .containsExactly("onb:create", "onb:connect");
        assertThat(source.size()).isZero();
    }

    @Test
    void afterConnectCallbackNextTextGoesToConnectNotIngest() {
        callbacks.handle(TelegramUpdates.callback(1, CHAT_ID, 500, FROM_ID, "onb:connect")
                .getCallbackQuery());

        assertThat(telegram.lastSent().orElseThrow().text())
                .isEqualTo(messages.text("base.ask_path"));
        assertThat(telegram.lastSent().orElseThrow().keyboard().rows()).isEmpty();
        assertThat(sessions.isAwaitingPath(CHAT_ID, NOW)).isTrue();

        handler.handle(TelegramUpdates.text(2, CHAT_ID, 501, FROM_ID, "relative/path"));

        assertThat(telegram.lastSent().orElseThrow().text())
                .isEqualTo(messages.text("base.path_relative"));
        assertThat(source.size()).isZero();
    }

    @Test
    void commandWhileAwaitingPathResetsExpectation() {
        callbacks.handle(TelegramUpdates.callback(1, CHAT_ID, 500, FROM_ID, "onb:connect")
                .getCallbackQuery());
        assertThat(sessions.size()).isEqualTo(1);

        handler.handle(TelegramUpdates.text(2, CHAT_ID, 501, FROM_ID, "/help"));

        assertThat(sessions.size()).isZero();
        assertThat(telegram.lastSent().orElseThrow().text()).contains("Отправьте");
    }

    @Test
    void afterSuccessfulConnectOrdinaryTextCreatesSourceItemAgain() {
        callbacks.handle(TelegramUpdates.callback(1, CHAT_ID, 500, FROM_ID, "onb:connect")
                .getCallbackQuery());
        handler.handle(TelegramUpdates.text(2, CHAT_ID, 501, FROM_ID, externalVault.toString()));

        assertThat(telegram.lastSent().orElseThrow().text())
                .contains("База подключена");
        assertThat(bases.findByTenantId(TENANT_ID).orElseThrow().kind())
                .isEqualTo(KnowledgeBaseKind.EXTERNAL_OBSIDIAN);

        handler.handle(TelegramUpdates.text(3, CHAT_ID, 502, FROM_ID, "конспект"));

        assertThat(source.size()).isEqualTo(1);
        assertThat(jobs.size()).isEqualTo(1);
    }

    private static Path copyVault(Path target) throws IOException {
        Path source;
        try {
            source = Path.of(OnboardingFlowTest.class.getResource("/vault-fixture").toURI());
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("фикстура vault-fixture недоступна", exception);
        }
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
}
