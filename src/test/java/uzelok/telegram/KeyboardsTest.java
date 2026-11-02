package uzelok.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import uzelok.domain.model.InlineButton;
import uzelok.domain.model.InlineKeyboard;

class KeyboardsTest {

    @Test
    void previewHasFourButtonsInTwoRows() {
        String[] tokens = {"a".repeat(32), "b".repeat(32), "c".repeat(32), "d".repeat(32)};

        InlineKeyboard keyboard = Keyboards.preview(tokens[0], tokens[1], tokens[2], tokens[3]);

        assertThat(keyboard.rows()).hasSize(2);
        List<InlineButton> buttons = keyboard.rows().stream().flatMap(List::stream).toList();
        assertThat(buttons).hasSize(4);
        assertThat(buttons).extracting(InlineButton::text)
                .containsExactly("✅ Сохранить", "📥 В _Inbox", "🔄 Перегенерировать", "❌ Отмена");
        assertThat(buttons).extracting(InlineButton::callbackData)
                .containsExactly("act:" + tokens[0], "act:" + tokens[2],
                        "act:" + tokens[1], "act:" + tokens[3]);
        for (InlineButton button : buttons) {
            assertThat(button.callbackData().getBytes(StandardCharsets.UTF_8).length)
                    .isLessThanOrEqualTo(64);
        }
    }
}
