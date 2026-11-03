package uzelok.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import uzelok.config.UzelokProperties;
import uzelok.domain.model.ActionOutcome;
import uzelok.domain.model.ChatAccess;
import uzelok.domain.model.JobStatus;
import uzelok.domain.model.KnowledgeBase;
import uzelok.domain.model.KnowledgeBaseKind;
import uzelok.domain.model.ProcessingJob;
import uzelok.pipeline.StatsService;
import uzelok.support.FixedKnowledgeBaseRepository;
import uzelok.support.InMemoryAccessRepository;
import uzelok.support.InMemoryAttachmentRepository;
import uzelok.support.InMemoryJobRepository;
import uzelok.support.InMemoryLlmCallRepository;
import uzelok.support.InMemoryNoteRepository;
import uzelok.support.InMemoryPendingActionRepository;
import uzelok.support.InMemorySourceItemRepository;
import uzelok.support.InMemoryTelegramGateway;
import uzelok.support.InMemoryTelegramUpdateRepository;
import uzelok.support.MutableClock;
import uzelok.support.RecordingAuditRepository;
import uzelok.support.TelegramUpdates;
import uzelok.support.StubOnboardingHandler;
import uzelok.support.StubVaultExporter;

class MessageHandlerTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID APP_USER_ID = UUID.randomUUID();
    private static final UUID KNOWLEDGE_BASE_ID = UUID.randomUUID();
    private static final long CHAT_ID = 100L;
    private static final long FROM_ID = 200L;

    @TempDir
    Path artifacts;

    private InMemorySourceItemRepository source;
    private InMemoryAttachmentRepository attachments;
    private InMemoryJobRepository jobs;
    private RecordingAuditRepository audit;
    private InMemoryTelegramGateway telegram;
    private InMemoryAccessRepository access;
    private InMemorySourceItemRepository sharedSource;
    private UzelokProperties.Telegram telegramProps;
    private UzelokProperties.Limits limits;
    private Clock clock;
    private MessageHandler handler;

    @BeforeEach
    void setUp() {
        source = new InMemorySourceItemRepository();
        sharedSource = source;
        attachments = new InMemoryAttachmentRepository();
        jobs = new InMemoryJobRepository(sharedSource);
        audit = new RecordingAuditRepository();
        telegram = new InMemoryTelegramGateway();
        access = new InMemoryAccessRepository()
                .add(CHAT_ID, new ChatAccess(TENANT_ID, APP_USER_ID, FROM_ID, true));
        telegramProps = new UzelokProperties.Telegram(
                "token", "test_bot", "", 30, 2000, 8000, 15_728_640L, 1_048_576L, 30_000_000L);
        limits = new UzelokProperties.Limits(20, 5, 72);
        clock = Clock.fixed(Instant.parse("2026-01-15T10:00:00Z"), ZoneOffset.UTC);
        handler = handlerWith(new FixedKnowledgeBaseRepository(knowledgeBase()));
    }

    @Test
    void allowedTextCreatesSourceItemAndJobAndSendsStatus() {
        handler.handle(TelegramUpdates.text(1, CHAT_ID, 500, FROM_ID, "конспект"));

        assertThat(source.size()).isEqualTo(1);
        assertThat(jobs.size()).isEqualTo(1);
        ProcessingJob job = jobs.all().get(0);
        assertThat(job.status()).isEqualTo(JobStatus.RECEIVED);
        assertThat(job.statusMessageId()).isNotNull();
        assertThat(telegram.sent()).hasSize(1);
        assertThat(telegram.lastSent().orElseThrow().text()).contains("Принял");
        assertThat(audit.actions()).contains("job_created");
    }

    @Test
    void notAllowedChatGetsDeniedAndNothingIsCreated() {
        access.add(999L, new ChatAccess(TENANT_ID, APP_USER_ID, FROM_ID, false));

        handler.handle(TelegramUpdates.text(1, 999L, 500, FROM_ID, "текст"));

        assertThat(telegram.lastSent().orElseThrow().text()).isEqualTo("Доступ не выдан.");
        assertThat(source.size()).isZero();
        assertThat(jobs.size()).isZero();
        assertThat(audit.actions()).contains("auth_denied");
    }

    @Test
    void unknownChatIsSilentlyAudited() {
        handler.handle(TelegramUpdates.text(1, 777L, 500, FROM_ID, "текст"));

        assertThat(telegram.sent()).isEmpty();
        assertThat(source.size()).isZero();
        assertThat(audit.actions()).contains("auth_denied");
    }

    @Test
    void foreignUserGetsNoReplyButAudit() {
        handler.handle(TelegramUpdates.text(1, CHAT_ID, 500, 999L, "текст"));

        assertThat(telegram.sent()).isEmpty();
        assertThat(source.size()).isZero();
        assertThat(audit.actions()).contains("auth_denied");
        assertThat(audit.records().get(0).detail()).containsEntry("reason", "user_mismatch");
    }

    @Test
    void tooLongTextIsRejectedWithoutJob() {
        String text = "а".repeat(8001);

        handler.handle(TelegramUpdates.text(1, CHAT_ID, 500, FROM_ID, text));

        assertThat(source.size()).isZero();
        assertThat(jobs.size()).isZero();
        assertThat(telegram.lastSent().orElseThrow().text()).contains("слишком длинный");
    }

    @Test
    void missingKnowledgeBaseStopsIngest() {
        handler = handlerWith(new FixedKnowledgeBaseRepository(null));

        handler.handle(TelegramUpdates.text(1, CHAT_ID, 500, FROM_ID, "текст"));

        assertThat(source.size()).isZero();
        assertThat(jobs.size()).isZero();
        assertThat(telegram.lastSent().orElseThrow().text())
                .isEqualTo(new Messages().text("base.prompt"));
        assertThat(audit.actions()).contains("onboarding_required");
    }

    @Test
    void repeatedMessageDoesNotCreateSecondJob() {
        handler.handle(TelegramUpdates.text(1, CHAT_ID, 500, FROM_ID, "текст"));
        handler.handle(TelegramUpdates.text(2, CHAT_ID, 500, FROM_ID, "текст"));

        assertThat(source.size()).isEqualTo(1);
        assertThat(jobs.size()).isEqualTo(1);
        assertThat(telegram.sent()).hasSize(1);
        assertThat(audit.actions()).contains("duplicate_delivery");
    }

    @Test
    void downloadDeliveryFailureIsAuditedAndReportedToUser() {
        telegram.failDownload(new TelegramDeliveryException("сбой сети"));

        handler.handle(TelegramUpdates.photo(1, CHAT_ID, 500, FROM_ID, null, "photo", 100));

        assertThat(audit.actions()).contains("ingest_failed");
        assertThat(audit.records()).anySatisfy(record -> {
            if ("ingest_failed".equals(record.action())) {
                assertThat(record.detail()).containsEntry("reason", "TelegramDeliveryException");
            }
        });
        assertThat(telegram.lastSent().orElseThrow().text())
                .isEqualTo(new Messages().text("attachment.failed"));
        assertThat(jobs.size()).isZero();
    }

    @Test
    void dailyQuotaRejectsSecondMessageWithinSameDay() {
        MutableClock mutableClock = new MutableClock(Instant.parse("2026-01-15T10:00:00Z"));
        handler = handlerWith(new FixedKnowledgeBaseRepository(knowledgeBase()),
                new UzelokProperties.Limits(1, 5, 72), mutableClock);

        handler.handle(TelegramUpdates.text(1, CHAT_ID, 500, FROM_ID, "первое"));
        mutableClock.setInstant(Instant.parse("2026-01-15T12:00:00Z"));
        handler.handle(TelegramUpdates.text(2, CHAT_ID, 501, FROM_ID, "второе"));

        assertThat(source.size()).isEqualTo(1);
        assertThat(jobs.size()).isEqualTo(1);
        assertThat(audit.actions()).contains("quota_exceeded");
        assertThat(audit.records()).anySatisfy(record -> {
            if ("quota_exceeded".equals(record.action())) {
                assertThat(record.detail()).containsEntry("reason", "jobs_per_day");
            }
        });
        assertThat(telegram.lastSent().orElseThrow().text())
                .isEqualTo(new Messages().text("quota.day"));
    }

    @Test
    void quotaRejectionLeavesNoSourceItemOrJobButRegistersUpdate() {
        MutableClock mutableClock = new MutableClock(Instant.parse("2026-01-15T10:00:00Z"));
        handler = handlerWith(new FixedKnowledgeBaseRepository(knowledgeBase()),
                new UzelokProperties.Limits(1, 5, 72), mutableClock);
        InMemoryTelegramUpdateRepository updates = new InMemoryTelegramUpdateRepository();
        CallbackHandler callbacks = new CallbackHandler(access,
                new InMemoryPendingActionRepository(), action -> ActionOutcome.ignored(),
                new StubOnboardingHandler(), telegram, new Messages(), audit, mutableClock);
        UpdateRouter router = new UpdateRouter(updates, handler, callbacks, audit);

        router.consume(List.of(TelegramUpdates.text(1, CHAT_ID, 500, FROM_ID, "первое")));
        int sourceAfterFirst = source.size();
        int jobsAfterFirst = jobs.size();
        assertThat(sourceAfterFirst).isEqualTo(1);
        assertThat(jobsAfterFirst).isEqualTo(1);

        mutableClock.setInstant(Instant.parse("2026-01-15T12:00:00Z"));
        router.consume(List.of(TelegramUpdates.text(2, CHAT_ID, 501, FROM_ID, "второе")));

        assertThat(source.size()).isEqualTo(sourceAfterFirst);
        assertThat(jobs.size()).isEqualTo(jobsAfterFirst);
        assertThat(source.findByChatAndMessage(CHAT_ID, 501)).isEmpty();
        assertThat(audit.actions()).contains("quota_exceeded");
        assertThat(updates.size()).isEqualTo(2);
        assertThat(updates.typeOf(2)).isEqualTo("message");
    }

    private MessageHandler handlerWith(uzelok.domain.port.KnowledgeBaseRepository bases) {
        return handlerWith(bases, limits, clock);
    }

    private MessageHandler handlerWith(uzelok.domain.port.KnowledgeBaseRepository bases,
                                       UzelokProperties.Limits usedLimits, Clock usedClock) {
        Messages messages = new Messages();
        CommandHandler commands = new CommandHandler(telegram, messages, "test_bot", Map.of(),
                access, jobs, new StatsService(new InMemoryNoteRepository(), jobs,
                        new InMemoryLlmCallRepository()), bases, new StubVaultExporter(), audit,
                usedClock);
        AttachmentReceiver receiver = new AttachmentReceiver(
                telegramProps,
                new UzelokProperties.Storage(artifacts.toString(),
                        "/var/lib/uzelok/vaults", "/var/lib/uzelok/exports"),
                attachments,
                telegram,
                messages);
        return new MessageHandler(access, bases, source, attachments, jobs, audit, telegram, commands,
                receiver, usedClock, usedLimits, telegramProps, messages,
                new StubOnboardingHandler());
    }


    private static KnowledgeBase knowledgeBase() {
        return new KnowledgeBase(
                KNOWLEDGE_BASE_ID,
                KnowledgeBaseKind.EXTERNAL_OBSIDIAN,
                artifactsPath(),
                "40_Knowledge/_meta/taxonomy.yaml",
                "40_Knowledge/_Inbox");
    }

    private static String artifactsPath() {
        return "/tmp/uzelok-test-vault";
    }
}
