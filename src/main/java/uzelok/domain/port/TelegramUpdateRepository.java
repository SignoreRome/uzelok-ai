package uzelok.domain.port;

public interface TelegramUpdateRepository {

    /**
     * Регистрирует апдейт.
     *
     * @return {@code true}, если апдейт новый
     */
    boolean register(int updateId, Long chatId, String updateType);
}
