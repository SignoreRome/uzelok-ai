package uzelok.domain.port;

/**
 * Тексты интерфейса по ключу. Реализация живёт в telegram, но pipeline
 * зависит только от порта.
 */
public interface MessageCatalog {

    String text(String key, Object... args);
}
