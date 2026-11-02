package uzelok.config;

import org.springframework.context.SmartLifecycle;
import uzelok.telegram.TelegramPoller;

public final class TelegramPollerLifecycle implements SmartLifecycle {

    private final TelegramPoller poller;
    private volatile boolean running;

    public TelegramPollerLifecycle(TelegramPoller poller) {
        this.poller = poller;
    }

    @Override
    public void start() {
        poller.start();
        running = poller.isRunning();
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
