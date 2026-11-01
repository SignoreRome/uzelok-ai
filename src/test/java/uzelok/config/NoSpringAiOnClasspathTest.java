package uzelok.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Страховка от возврата Spring AI «как было»: стартер поднимает автоконфигурации всех моделей
 * (chat, audio, embedding) и валит старт приложения без ключа — проверено запуском jar (ADR 0003).
 * Если Spring AI понадобится, тест нужно осознанно переписать вместе с настройкой автоконфигураций.
 */
class NoSpringAiOnClasspathTest {

    @Test
    void springAiIsNotOnClasspath() {
        assertThat(isPresent("org.springframework.ai.chat.client.ChatClient")).isFalse();
        assertThat(isPresent("org.springframework.ai.openai.OpenAiChatModel")).isFalse();
    }

    private static boolean isPresent(String className) {
        try {
            Class.forName(className, false, NoSpringAiOnClasspathTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException expected) {
            return false;
        }
    }
}
