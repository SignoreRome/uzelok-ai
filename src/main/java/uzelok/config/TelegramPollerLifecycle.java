package uzelok.config;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import uzelok.domain.model.BotCommandEntry;
import uzelok.domain.port.TelegramGateway;
import uzelok.telegram.CommandHandler;
import uzelok.telegram.TelegramPoller;

public final class TelegramPollerLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TelegramPollerLifecycle.class);

    private final TelegramPoller poller;
    private final TelegramGateway telegram;
    private final CommandHandler commands;
    private volatile boolean running;

    public TelegramPollerLifecycle(TelegramPoller poller, TelegramGateway telegram,
                                   CommandHandler commands) {
        this.poller = poller;
        this.telegram = telegram;
        this.commands = commands;
    }

    @Override
    public void start() {
        poller.start();
        running = poller.isRunning();
        if (!running) {
            return;
        }
        try {
            List<BotCommandEntry> menu = commands.menu();
            telegram.setCommands(menu);
            log.info("меню команд установлено: {} команд", menu.size());
        } catch (RuntimeException exception) {
            log.warn("не удалось установить меню команд: {}: {}",
                    exception.getClass().getSimpleName(), exception.getMessage());
        }
    }

    @Override
    public void stop() {
        poller.stop();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
