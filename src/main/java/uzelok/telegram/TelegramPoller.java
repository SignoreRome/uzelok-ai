package uzelok.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.longpolling.BotSession;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.telegram.telegrambots.meta.TelegramUrl;
import org.telegram.telegrambots.meta.api.methods.updates.GetUpdates;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import uzelok.config.UzelokProperties;

public final class TelegramPoller {

    private static final Logger log = LoggerFactory.getLogger(TelegramPoller.class);

    private final UzelokProperties.Telegram props;
    private final UpdateRouter router;
    private final OkHttpClient httpClient;

    private TelegramBotsLongPollingApplication application;
    private BotSession session;

    public TelegramPoller(UzelokProperties.Telegram props, UpdateRouter router) {
        this(props, router, null);
    }

    /**
     * {@code httpClient} нужен, чтобы long polling ходил через тот же прокси, что и отправка:
     * библиотека создаёт собственный клиент, если его не передать, и в сетях без прямого доступа
     * к api.telegram.org падает с {@code Network is unreachable}.
     */
    public TelegramPoller(UzelokProperties.Telegram props, UpdateRouter router, OkHttpClient httpClient) {
        this.props = props;
        this.router = router;
        this.httpClient = httpClient;
    }

    public synchronized void start() {
        if (props.botToken() == null || props.botToken().isBlank()) {
            log.warn("поллер не запущен: пустой токен");
            return;
        }
        if (application != null) {
            return;
        }
        TelegramBotsLongPollingApplication candidate = httpClient == null
                ? new TelegramBotsLongPollingApplication()
                : new TelegramBotsLongPollingApplication(ObjectMapper::new, () -> httpClient);
        try {
            session = candidate.registerBot(
                    props.botToken(),
                    () -> TelegramUrl.DEFAULT_URL,
                    lastUpdateId -> GetUpdates.builder()
                            .limit(100)
                            .timeout(props.pollTimeoutSeconds())
                            .offset(lastUpdateId + 1)
                            .build(),
                    router);
            application = candidate;
            log.info("поллер запущен");
        } catch (TelegramApiException exception) {
            closeQuietly(candidate);
            throw new TelegramDeliveryException("не удалось запустить поллер", exception);
        }
    }

    public synchronized void stop() {
        if (application == null) {
            return;
        }
        closeQuietly(application);
        application = null;
        session = null;
    }

    public synchronized boolean isRunning() {
        return application != null && session != null && session.isRunning();
    }

    private static void closeQuietly(TelegramBotsLongPollingApplication target) {
        try {
            target.close();
        } catch (Exception exception) {
            log.warn("ошибка остановки поллера: {}", exception.getClass().getSimpleName());
        }
    }
}
