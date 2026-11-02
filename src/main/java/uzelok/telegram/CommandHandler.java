package uzelok.telegram;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import uzelok.domain.model.ChatAccess;
import uzelok.domain.model.JobStatus;
import uzelok.domain.model.ProcessingJob;
import uzelok.domain.model.TenantStats;
import uzelok.domain.port.AccessRepository;
import uzelok.domain.port.JobRepository;
import uzelok.domain.port.StatsProvider;
import uzelok.domain.port.TelegramGateway;

public final class CommandHandler {

    public record LlmStep(String provider, String model, String fallbackModel) {
    }

    private final TelegramGateway telegram;
    private final Messages messages;
    private final String botUsername;
    private final Map<String, LlmStep> llmSteps;
    private final AccessRepository access;
    private final JobRepository jobs;
    private final StatsProvider stats;
    private final Clock clock;

    public CommandHandler(TelegramGateway telegram, Messages messages, String botUsername,
                          Map<String, LlmStep> llmSteps, AccessRepository access,
                          JobRepository jobs, StatsProvider stats, Clock clock) {
        this.telegram = telegram;
        this.messages = messages;
        this.botUsername = botUsername;
        this.llmSteps = llmSteps == null ? Map.of() : Map.copyOf(llmSteps);
        this.access = access;
        this.jobs = jobs;
        this.stats = stats;
        this.clock = clock;
    }

    public boolean handle(long chatId, String text) {
        if (text == null || !text.startsWith("/")) {
            return false;
        }

        String token = text.split("\\s+", 2)[0];
        String withoutSlash = token.substring(1);
        int atIndex = withoutSlash.indexOf('@');
        String command = (atIndex >= 0 ? withoutSlash.substring(0, atIndex) : withoutSlash)
                .toLowerCase(Locale.ROOT);
        String target = atIndex >= 0 ? withoutSlash.substring(atIndex + 1) : null;

        if (targetIsAnotherBot(target)) {
            telegram.sendMessage(chatId, messages.text("command.later"), null);
            return true;
        }

        switch (command) {
            case "start" -> telegram.sendMessage(chatId, messages.text("start.text"), null);
            case "help" -> telegram.sendMessage(chatId, messages.text("help.text"), null);
            case "stats" -> stats(chatId);
            case "ai_mode" -> aiMode(chatId);
            case "cancel" -> cancel(chatId);
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

    private static String money(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private boolean targetIsAnotherBot(String target) {
        if (target == null || target.isBlank() || botUsername == null || botUsername.isBlank()) {
            return false;
        }
        return !target.equalsIgnoreCase(botUsername);
    }
}
