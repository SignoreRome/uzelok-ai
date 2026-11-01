package uzelok.telegram;

import java.util.Locale;
import uzelok.config.UzelokProperties;
import uzelok.domain.port.TelegramGateway;

public final class CommandHandler {

    private final TelegramGateway telegram;
    private final Messages messages;
    private final String botUsername;

    public CommandHandler(TelegramGateway telegram, Messages messages, UzelokProperties.Telegram props) {
        this.telegram = telegram;
        this.messages = messages;
        this.botUsername = props.botUsername();
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

        if (command.equals("start")) {
            telegram.sendMessage(chatId, messages.text("start.text"), null);
        } else if (command.equals("help")) {
            telegram.sendMessage(chatId, messages.text("help.text"), null);
        } else {
            telegram.sendMessage(chatId, messages.text("command.later"), null);
        }
        return true;
    }

    private boolean targetIsAnotherBot(String target) {
        if (target == null || target.isBlank() || botUsername == null || botUsername.isBlank()) {
            return false;
        }
        return !target.equalsIgnoreCase(botUsername);
    }
}
