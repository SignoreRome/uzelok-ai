package uzelok.telegram;

import java.util.List;
import uzelok.domain.model.InlineButton;
import uzelok.domain.model.InlineKeyboard;

public final class Keyboards {

    private Keyboards() {
    }

    public static InlineKeyboard preview(String saveToken, String regenerateToken, String inboxToken,
                                         String cancelToken) {
        return new InlineKeyboard(List.of(
                List.of(button("✅ Сохранить", saveToken), button("📥 В _Inbox", inboxToken)),
                List.of(button("🔄 Перегенерировать", regenerateToken),
                        button("❌ Отмена", cancelToken))));
    }

    public static InlineKeyboard duplicate(String regenerateToken, Messages messages) {
        return new InlineKeyboard(List.of(
                List.of(button(messages.text("duplicate.button.regenerate"), regenerateToken))));
    }

    public static InlineKeyboard onboarding(Messages messages) {
        return new InlineKeyboard(List.of(
                List.of(new InlineButton(messages.text("base.button.create"), "onb:create"),
                        new InlineButton(messages.text("base.button.connect"), "onb:connect"))));
    }

    private static InlineButton button(String text, String token) {
        return new InlineButton(text, "act:" + token);
    }
}
