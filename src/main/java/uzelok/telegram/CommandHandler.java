package uzelok.telegram;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import uzelok.domain.model.AuditRecord;
import uzelok.domain.model.BotCommandEntry;
import uzelok.domain.model.ChatAccess;
import uzelok.domain.model.ExportBundle;
import uzelok.domain.model.ExportPart;
import uzelok.domain.model.JobStatus;
import uzelok.domain.model.KnowledgeBase;
import uzelok.domain.model.KnowledgeBaseKind;
import uzelok.domain.model.ProcessingJob;
import uzelok.domain.model.TenantStats;
import uzelok.domain.port.AccessRepository;
import uzelok.domain.port.AuditRepository;
import uzelok.domain.port.JobRepository;
import uzelok.domain.port.KnowledgeBaseRepository;
import uzelok.domain.port.StatsProvider;
import uzelok.domain.port.TelegramGateway;
import uzelok.domain.port.VaultExporter;

public final class CommandHandler {

    public record LlmStep(String provider, String model, String fallbackModel) {
    }

    private static final List<String> COMMAND_ORDER =
            List.of("start", "help", "stats", "ai_mode", "cancel", "export");
    private static final Set<String> COMMANDS = Set.copyOf(COMMAND_ORDER);

    private final TelegramGateway telegram;
    private final Messages messages;
    private final String botUsername;
    private final Map<String, LlmStep> llmSteps;
    private final AccessRepository access;
    private final JobRepository jobs;
    private final StatsProvider stats;
    private final KnowledgeBaseRepository bases;
    private final VaultExporter exporter;
    private final AuditRepository audit;
    private final Clock clock;

    public CommandHandler(TelegramGateway telegram, Messages messages, String botUsername,
                          Map<String, LlmStep> llmSteps, AccessRepository access,
                          JobRepository jobs, StatsProvider stats, KnowledgeBaseRepository bases,
                          VaultExporter exporter, AuditRepository audit, Clock clock) {
        this.telegram = telegram;
        this.messages = messages;
        this.botUsername = botUsername;
        this.llmSteps = llmSteps == null ? Map.of() : Map.copyOf(llmSteps);
        this.access = access;
        this.jobs = jobs;
        this.stats = stats;
        this.bases = bases;
        this.exporter = exporter;
        this.audit = audit;
        this.clock = clock;
    }

    public boolean handles(String text) {
        ParsedCommand parsed = parse(text);
        return parsed != null && COMMANDS.contains(parsed.command());
    }

    public List<BotCommandEntry> menu() {
        return COMMAND_ORDER.stream()
                .map(name -> new BotCommandEntry(name, messages.text("command.menu." + name)))
                .toList();
    }

    public boolean handle(long chatId, String text) {
        ParsedCommand parsed = parse(text);
        if (parsed == null) {
            return false;
        }

        if (targetIsAnotherBot(parsed.target())) {
            telegram.sendMessage(chatId, messages.text("command.later"), null);
            return true;
        }

        switch (parsed.command()) {
            case "start" -> telegram.sendMessage(chatId, messages.text("start.text"), null);
            case "help" -> telegram.sendMessage(chatId, messages.text("help.text"), null);
            case "stats" -> stats(chatId);
            case "ai_mode" -> aiMode(chatId);
            case "cancel" -> cancel(chatId);
            case "export" -> export(chatId);
            default -> telegram.sendMessage(chatId, messages.text("command.later"), null);
        }
        return true;
    }

    private void stats(long chatId) {
        Optional<ChatAccess> found = access.findByChatId(chatId);
        if (found.isEmpty()) {
            telegram.sendMessage(chatId, messages.text("access.denied"), null);
            return;
        }
        TenantStats tenantStats = stats.forTenant(found.get().tenantId(), clock.instant());
        telegram.sendMessage(chatId, messages.text("command.stats",
                String.valueOf(tenantStats.notes()),
                String.valueOf(tenantStats.jobs24h()),
                money(tenantStats.spent24h()),
                money(tenantStats.spentMonth())), null);
    }

    private void aiMode(long chatId) {
        StringBuilder text = new StringBuilder(messages.text("command.ai_mode.title"));
        for (String step : new String[]{"extract", "draft"}) {
            LlmStep configured = llmSteps.get(step);
            text.append('\n');
            if (configured == null) {
                text.append(messages.text("command.ai_mode.missing", step));
            } else {
                text.append(messages.text("command.ai_mode.step", step, configured.provider(),
                        configured.model(), configured.fallbackModel() == null
                                ? "—"
                                : configured.fallbackModel()));
            }
        }
        telegram.sendMessage(chatId, text.toString(), null);
    }

    private void cancel(long chatId) {
        Optional<ProcessingJob> active = jobs.findActiveByChat(chatId);
        if (active.isEmpty()) {
            telegram.sendMessage(chatId, messages.text("command.cancel.none"), null);
            return;
        }
        ProcessingJob job = active.get();
        if (job.status() == JobStatus.FAILED) {
            telegram.sendMessage(chatId, messages.text("command.cancel.failed"), null);
            return;
        }
        jobs.transition(job.id(), job.status(), JobStatus.CANCELLED);
        telegram.sendMessage(chatId, messages.text("command.cancel.done"), null);
    }

    private void export(long chatId) {
        Optional<ChatAccess> found = access.findByChatId(chatId);
        if (found.isEmpty()) {
            telegram.sendMessage(chatId, messages.text("access.denied"), null);
            return;
        }
        Optional<KnowledgeBase> foundBase = bases.findByTenantId(found.get().tenantId());
        if (foundBase.isEmpty()) {
            telegram.sendMessage(chatId, messages.text("base.none"), null);
            return;
        }
        KnowledgeBase base = foundBase.get();
        if (base.kind() != KnowledgeBaseKind.INTERNAL_DIR) {
            telegram.sendMessage(chatId, messages.text("export.external"), null);
            return;
        }

        ExportBundle bundle = null;
        try {
            bundle = exporter.export(base);
            if (bundle.warnings() != null && !bundle.warnings().isEmpty()) {
                telegram.sendMessage(chatId, messages.text("export.warnings",
                        String.join("\n", bundle.warnings())), null);
            }
            if (bundle.parts().isEmpty()) {
                telegram.sendMessage(chatId, messages.text("export.empty"), null);
                return;
            }
            if (bundle.parts().size() > 1) {
                telegram.sendMessage(chatId, messages.text("export.parts_note",
                        String.valueOf(bundle.parts().size())), null);
            }
            long bytes = 0;
            for (ExportPart part : bundle.parts()) {
                bytes += part.sizeBytes();
                telegram.sendDocumentFile(chatId, part.fileName(), part.path(), null);
            }
            telegram.sendMessage(chatId, messages.text("export.sent",
                    String.valueOf(bundle.fileCount()),
                    String.valueOf(bundle.parts().size())), null);
            audit.record(new AuditRecord(found.get().tenantId(), chatId, "vault_exported",
                    "knowledge_base", base.id(), Map.of(
                            "parts", String.valueOf(bundle.parts().size()),
                            "files", String.valueOf(bundle.fileCount()),
                            "bytes", String.valueOf(bytes))));
        } catch (RuntimeException exception) {
            String reason = truncate(exception.getMessage(), 200);
            telegram.sendMessage(chatId, messages.text("export.failed", reason), null);
            audit.record(new AuditRecord(found.get().tenantId(), chatId, "export_failed",
                    "knowledge_base", base.id(), Map.of("reason", reason)));
        } finally {
            exporter.cleanup(bundle);
        }
    }

    private static String money(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private static ParsedCommand parse(String text) {
        if (text == null || !text.startsWith("/")) {
            return null;
        }
        String token = text.split("\\s+", 2)[0];
        String withoutSlash = token.substring(1);
        int atIndex = withoutSlash.indexOf('@');
        String command = (atIndex >= 0 ? withoutSlash.substring(0, atIndex) : withoutSlash)
                .toLowerCase(Locale.ROOT);
        String target = atIndex >= 0 ? withoutSlash.substring(atIndex + 1) : null;
        return new ParsedCommand(command, target);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private boolean targetIsAnotherBot(String target) {
        if (target == null || target.isBlank() || botUsername == null || botUsername.isBlank()) {
            return false;
        }
        return !target.equalsIgnoreCase(botUsername);
    }

    private record ParsedCommand(String command, String target) {
    }
}
