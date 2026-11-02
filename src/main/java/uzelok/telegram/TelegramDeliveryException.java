package uzelok.telegram;

public class TelegramDeliveryException extends RuntimeException {

    public TelegramDeliveryException(String message) {
        super(message);
    }

    public TelegramDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
