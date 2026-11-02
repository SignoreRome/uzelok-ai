package uzelok.config;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import okhttp3.Credentials;
import okhttp3.Protocol;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import uzelok.domain.port.AccessRepository;
import uzelok.domain.port.ActionHandler;
import uzelok.domain.port.AttachmentRepository;
import uzelok.domain.port.AuditRepository;
import uzelok.domain.port.JobRepository;
import uzelok.domain.port.KnowledgeBaseRepository;
import uzelok.domain.port.PendingActionRepository;
import uzelok.domain.port.SourceItemRepository;
import uzelok.domain.port.StatsProvider;
import uzelok.domain.port.TelegramGateway;
import uzelok.telegram.AttachmentReceiver;
import uzelok.telegram.CallbackHandler;
import uzelok.telegram.CommandHandler;
import uzelok.telegram.MessageHandler;
import uzelok.telegram.Messages;
import uzelok.telegram.StatusMessage;
import uzelok.telegram.TelegramBotGateway;
import uzelok.telegram.TelegramPoller;
import uzelok.telegram.UnavailableTelegramGateway;
import uzelok.telegram.UpdateRouter;

@Configuration(proxyBeanMethods = false)
public class TelegramConfig {

    @Bean
    public Messages messages() {
        return new Messages();
    }

    /**
     * Один клиент на отправку и на long polling: без прямого доступа к api.telegram.org
     * прокси обязан быть у обоих, иначе поллер падает с "Network is unreachable".
     */
    /**
     * Один клиент на отправку и на long polling. Read timeout обязан быть больше poll-timeout,
     * иначе getUpdates обрывается раньше ответа Telegram (дефолт OkHttp — 10 с). HTTP/2 принудительно
     * понижен до 1.1: часть HTTP-прокси ломает длинные CONNECT-стримы.
     */
    @Bean
    public OkHttpClient telegramHttpClient(UzelokProperties properties) {
        Duration readTimeout = Duration.ofSeconds(properties.telegram().pollTimeoutSeconds() + 30L);
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(20))
                .readTimeout(readTimeout)
                .writeTimeout(Duration.ofSeconds(30))
                .protocols(List.of(Protocol.HTTP_1_1))
                .retryOnConnectionFailure(true);
        ProxySupport.ProxyConfig proxy = ProxySupport.parse(properties.telegram().proxyUrl())
                .orElse(null);
        if (proxy != null) {
            builder.proxy(proxy.proxy());
            if (proxy.hasCredentials()) {
                String user = proxy.user();
                char[] password = proxy.password();
                builder.proxyAuthenticator((route, response) -> response.request().newBuilder()
                        .header("Proxy-Authorization", Credentials.basic(user, new String(password)))
                        .build());
            }
        }
        return builder.build();
    }

    @Bean
    public TelegramClient telegramClient(UzelokProperties properties, OkHttpClient telegramHttpClient) {
        String token = properties.telegram().botToken();
        if (token == null || token.isBlank()) {
            return null;
        }
        return new OkHttpTelegramClient(telegramHttpClient, token);
    }

    @Bean
    public TelegramGateway telegramGateway(ObjectProvider<TelegramClient> clients,
                                           UzelokProperties properties) {
        TelegramClient client = clients.getIfAvailable();
        if (client == null) {
            return new UnavailableTelegramGateway();
        }
        return new TelegramBotGateway(client, properties.telegram().botToken());
    }

    @Bean
    public CommandHandler commandHandler(TelegramGateway telegram, Messages messages,
                                         UzelokProperties properties, AccessRepository access,
                                         JobRepository jobs, StatsProvider stats, Clock clock) {
        java.util.Map<String, CommandHandler.LlmStep> steps = new java.util.LinkedHashMap<>();
        properties.llm().steps().forEach((name, step) -> steps.put(name,
                new CommandHandler.LlmStep(step.provider(), step.model(),
                        step.fallbackModel())));
        return new CommandHandler(telegram, messages, properties.telegram().botUsername(),
                steps, access, jobs, stats, clock);
    }

    @Bean
    public CallbackHandler callbackHandler(AccessRepository access,
                                           PendingActionRepository actions,
                                           ActionHandler handler, TelegramGateway telegram,
                                           AuditRepository audit, Clock clock) {
        return new CallbackHandler(access, actions, handler, telegram, audit, clock);
    }

    @Bean
    public AttachmentReceiver attachmentReceiver(UzelokProperties properties,
                                                 AttachmentRepository attachments,
                                                 TelegramGateway telegram, Messages messages) {
        return new AttachmentReceiver(properties.telegram(), properties.storage(), attachments, telegram,
                messages);
    }

    @Bean
    public MessageHandler messageHandler(AccessRepository access, KnowledgeBaseRepository bases,
                                         SourceItemRepository source, AttachmentRepository attachments,
                                         JobRepository jobs, AuditRepository audit,
                                         TelegramGateway telegram, CommandHandler commands,
                                         AttachmentReceiver receiver, Clock clock,
                                         UzelokProperties properties, Messages messages) {
        return new MessageHandler(access, bases, source, attachments, jobs, audit, telegram, commands,
                receiver, clock, properties.limits(), properties.telegram(), messages);
    }

    @Bean
    public UpdateRouter updateRouter(uzelok.domain.port.TelegramUpdateRepository updates,
                                     MessageHandler messages, CallbackHandler callbacks,
                                     AuditRepository audit) {
        return new UpdateRouter(updates, messages, callbacks, audit);
    }

    @Bean
    public StatusMessage statusMessage(TelegramGateway telegram) {
        return new StatusMessage(telegram);
    }

    @Bean
    public TelegramPoller telegramPoller(UzelokProperties properties, UpdateRouter router,
                                         OkHttpClient telegramHttpClient) {
        return new TelegramPoller(properties.telegram(), router, telegramHttpClient);
    }

    @Bean
    public TelegramPollerLifecycle telegramPollerLifecycle(TelegramPoller poller) {
        return new TelegramPollerLifecycle(poller);
    }
}
