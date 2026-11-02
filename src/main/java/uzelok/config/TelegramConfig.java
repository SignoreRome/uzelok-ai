package uzelok.config;

import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import uzelok.domain.port.AccessRepository;
import uzelok.domain.port.AttachmentRepository;
import uzelok.domain.port.AuditRepository;
import uzelok.domain.port.JobRepository;
import uzelok.domain.port.KnowledgeBaseRepository;
import uzelok.domain.port.SourceItemRepository;
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

    @Bean
    public TelegramClient telegramClient(UzelokProperties properties) {
        String token = properties.telegram().botToken();
        if (token == null || token.isBlank()) {
            return null;
        }
        return new OkHttpTelegramClient(token);
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
                                         UzelokProperties properties) {
        return new CommandHandler(telegram, messages, properties.telegram());
    }

    @Bean
    public CallbackHandler callbackHandler(AuditRepository audit, AccessRepository access) {
        return new CallbackHandler(audit, access);
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
    public TelegramPoller telegramPoller(UzelokProperties properties, UpdateRouter router) {
        return new TelegramPoller(properties.telegram(), router);
    }

    @Bean
    public TelegramPollerLifecycle telegramPollerLifecycle(TelegramPoller poller) {
        return new TelegramPollerLifecycle(poller);
    }
}
