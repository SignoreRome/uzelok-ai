package uzelok.telegram;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import uzelok.domain.model.AuditRecord;
import uzelok.domain.port.AuditRepository;
import uzelok.domain.port.TelegramUpdateRepository;

public final class UpdateRouter implements LongPollingUpdateConsumer {

    private static final Logger log = LoggerFactory.getLogger(UpdateRouter.class);

    private final TelegramUpdateRepository updates;
    private final MessageHandler messages;
    private final CallbackHandler callbacks;
    private final AuditRepository audit;

    public UpdateRouter(TelegramUpdateRepository updates, MessageHandler messages,
                        CallbackHandler callbacks, AuditRepository audit) {
        this.updates = updates;
        this.messages = messages;
        this.callbacks = callbacks;
        this.audit = audit;
    }

    @Override
    public void consume(List<Update> updates) {
        if (updates == null) {
            return;
        }
        for (Update update : updates) {
            route(update);
        }
    }

    private void route(Update update) {
        if (update == null || update.getUpdateId() == null) {
            log.warn("апдейт без update_id пропущен");
            return;
        }

        int updateId = update.getUpdateId();
        String type = typeOf(update);
        Long chatId = chatIdOf(update);

        try {
            if (update.getMessage() != null) {
                messages.handle(update);
            } else if (update.getCallbackQuery() != null) {
                callbacks.handle(update.getCallbackQuery());
            }
        } catch (RuntimeException exception) {
            log.error("ошибка обработки update_id={} type={} exception={}",
                    updateId, type, exception.getClass().getSimpleName());
            audit.record(new AuditRecord(null, chatId, "update_failed", null, null,
                    Map.of("reason", exception.getClass().getSimpleName())));
            return;
        }

        try {
            boolean fresh = updates.register(updateId, chatId, type);
            if (!fresh) {
                log.debug("повторный апдейт update_id={} type={}", updateId, type);
            }
        } catch (RuntimeException exception) {
            log.error("не удалось зарегистрировать update_id={} type={} exception={}",
                    updateId, type, exception.getClass().getSimpleName());
        }
    }

    private static String typeOf(Update update) {
        if (update.getMessage() != null) {
            return "message";
        }
        if (update.getCallbackQuery() != null) {
            return "callback_query";
        }
        if (update.getEditedMessage() != null) {
            return "edited_message";
        }
        return "other";
    }

    private static Long chatIdOf(Update update) {
        Message message = update.getMessage();
        if (message != null) {
            return message.getChatId();
        }
        CallbackQuery callback = update.getCallbackQuery();
        if (callback != null && callback.getMessage() != null) {
            return callback.getMessage().getChatId();
        }
        Message edited = update.getEditedMessage();
        return edited == null ? null : edited.getChatId();
    }
}
