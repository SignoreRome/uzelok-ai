package uzelok.domain.port;

/**
 * Кодирование доменных объектов в JSON для артефактов. Реализация живёт в config,
 * чтобы pipeline не зависел от библиотеки JSON.
 */
public interface JsonCodec {

    String write(Object value);

    <T> T read(String json, Class<T> type);
}
