package uzelok.telegram;

import java.text.MessageFormat;
import java.util.ResourceBundle;
import uzelok.domain.port.MessageCatalog;

public final class Messages implements MessageCatalog {

    private final ResourceBundle bundle;

    public Messages() {
        this.bundle = ResourceBundle.getBundle("strings.ru");
    }

    @Override
    public String text(String key, Object... args) {
        String pattern = bundle.getString(key);
        if (args == null || args.length == 0) {
            return pattern;
        }
        return MessageFormat.format(pattern, args);
    }
}
