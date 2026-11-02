package uzelok.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uzelok.domain.exception.ContractViolation;
import uzelok.domain.model.ChatAccess;
import uzelok.domain.model.ExportBundle;
import uzelok.domain.model.ExportPart;
import uzelok.domain.model.KnowledgeBase;
import uzelok.domain.model.KnowledgeBaseKind;
import uzelok.pipeline.StatsService;
import uzelok.support.InMemoryAccessRepository;
import uzelok.support.InMemoryJobRepository;
import uzelok.support.InMemoryKnowledgeBaseRepository;
import uzelok.support.InMemoryLlmCallRepository;
import uzelok.support.InMemoryNoteRepository;
import uzelok.support.InMemorySourceItemRepository;
import uzelok.support.InMemoryTelegramGateway;
import uzelok.support.MutableClock;
import uzelok.support.RecordingAuditRepository;
import uzelok.support.StubVaultExporter;

class ExportCommandTest {

    private static final long CHAT_ID = 100L;
    private static final long FROM_ID = 200L;
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID APP_USER_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");

    @TempDir
    Path root;

    private InMemoryTelegramGateway telegram;
    private InMemoryKnowledgeBaseRepository bases;
    private StubVaultExporter exporter;
    private RecordingAuditRepository audit;
    private Messages messages;
    private CommandHandler handler;

    @BeforeEach
    void setUp() {
        telegram = new InMemoryTelegramGateway();
        bases = new InMemoryKnowledgeBaseRepository();
        exporter = new StubVaultExporter();
        audit = new RecordingAuditRepository();
        messages = new Messages();
        InMemoryAccessRepository access = new InMemoryAccessRepository()
                .add(CHAT_ID, new ChatAccess(TENANT_ID, APP_USER_ID, FROM_ID, true));
        InMemorySourceItemRepository source = new InMemorySourceItemRepository();
        InMemoryJobRepository jobs = new InMemoryJobRepository(source);
        handler = new CommandHandler(telegram, messages, "test_bot", Map.of(), access, jobs,
                new StatsService(new InMemoryNoteRepository(), jobs, new InMemoryLlmCallRepository()),
                bases, exporter, audit, new MutableClock(NOW));
    }

    @Test
    void externalBaseIsNotExported() {
        bases.save(TENANT_ID, base(KnowledgeBaseKind.EXTERNAL_OBSIDIAN));

        handler.handle(CHAT_ID, "/export");

        assertThat(telegram.lastSent().orElseThrow().text())
                .isEqualTo(messages.text("export.external"));
        assertThat(exporter.lastBase()).isNull();
        assertThat(exporter.cleaned()).isFalse();
    }

    @Test
    void internalBaseSendsDocumentAndCleansUp() throws IOException {
        bases.save(TENANT_ID, base(KnowledgeBaseKind.INTERNAL_DIR));
        Path zip = root.resolve("uzelok-export-abcdef12-20261102-100000.zip");
        Files.writeString(zip, "zip-bytes");
        ExportPart part = new ExportPart(zip.getFileName().toString(), zip, Files.size(zip));
        exporter.bundle(new ExportBundle(root.resolve("temp"), List.of(part), 3, List.of()));

        handler.handle(CHAT_ID, "/export");

        assertThat(telegram.files()).hasSize(1);
        assertThat(telegram.files().getFirst().fileName())
                .isEqualTo(zip.getFileName().toString());
        assertThat(telegram.files().getFirst().sizeBytes()).isEqualTo(Files.size(zip));
        assertThat(telegram.lastSent().orElseThrow().text())
                .isEqualTo(messages.text("export.sent", "3", "1"));
        assertThat(exporter.cleaned()).isTrue();
        assertThat(audit.actions()).contains("vault_exported");
    }

    @Test
    void exportFailureIsReportedAndCleanedUp() {
        bases.save(TENANT_ID, base(KnowledgeBaseKind.INTERNAL_DIR));
        exporter.failWith(new ContractViolation("сбой архива"));

        handler.handle(CHAT_ID, "/export");

        assertThat(telegram.lastSent().orElseThrow().text())
                .isEqualTo(messages.text("export.failed", "сбой архива"));
        assertThat(exporter.cleaned()).isTrue();
        assertThat(audit.actions()).contains("export_failed");
    }

    private static KnowledgeBase base(KnowledgeBaseKind kind) {
        return new KnowledgeBase(UUID.randomUUID(), kind, "/tmp/vault",
                "40_Knowledge/_meta/taxonomy.yaml", "40_Knowledge/_Inbox");
    }
}
