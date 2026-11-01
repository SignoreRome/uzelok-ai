# Этап 3 — Telegram, LLM, pipeline

Этап разбит на две части, каждая — отдельная ветка и отдельный PR:

- **3a (этот документ, раздел «Часть 3a»)** — persistence-адаптеры, приём апдейтов, авторизация,
  дедупликация, вложения, создание `source_item` + `processing_job`, квоты, аудит. LLM и воркер — заглушки
  нет, их просто ещё нет: задача остаётся в `RECEIVED`.
- **3b** — HTTP-клиент LLM (EXTRACT/DRAFT), схемы, промпты, `JobStateMachine`/`JobWorker`/шаги,
  превью, кнопки, `pending_action`, запись заметки, бюджет. Пишется отдельным брифом после приёмки 3a.

Источник требований — `docs/TZ_MVP.md` v1.5 (FR-1..FR-5, FR-21, FR-23, §7, §9, §11, §12, §13) и
`docs/adr/0002-spring-wiring-in-config.md`. Где бриф расходится с ТЗ — прав этот файл.

## Часть 3a: приём материала

### Границы

Делаем:

- `domain`: новые порты и модели для приёма (см. ниже). Существующие модели не переписываются, кроме
  `SourceItem.kind: String → SourceKind` и добавления `InlineKeyboard`/`InlineButton`.
- `persistence`: JDBC-адаптеры портов (`NamedParameterJdbcTemplate`, `@Repository`).
- `telegram`: клиент Bot API, поллер, роутер апдейтов, обработчик сообщений, приём вложений, тексты.
- `config`: сборка всех бинов приёма, запуск/остановка поллера.
- `resources/db/migration/V2__ingest.sql` — индексы, которых требует приём.
- `resources/strings/ru.properties` — тексты бота.
- тесты к перечисленному.

НЕ делаем: LLM-клиент, промпты, воркер, шаги, превью, кнопки, `pending_action`, запись заметки,
`InternalDirStore`, `/export`, k8s, чтение/запись реальной базы знаний.

### Жёсткие правила

- Работать только в каталоге `/mnt/hdd/workspace/uzelok-ai`.
- Не выполнять git-команд, не коммитить, не менять `.git`, `docs/TZ_MVP.md`, `docs/STAGE2.md`, `README.md`.
- **Spring-аннотации только в `config` и `persistence`** (`@Repository`). `telegram` — обычные классы,
  зависимости в конструктор. Причина: ArchUnit-правило «pipeline зависит только от domain» и
  предсказуемая тестируемость без контекста (ADR 0002).
- Никакой сети, кроме вызовов Bot API через `TelegramGateway` (он инжектируется; в тестах — фейк).
- Логирование: SLF4J, только идентификаторы (`update_id`, `chat_id`, `job_id`, `source_item_id`, размер,
  mime, длительность). Никакого `System.out`, никакого текста сообщений пользователя и содержимого файлов.
- Код и сообщения об ошибках — по-русски; публичные типы и методы — по-английски, как в существующем коде.
- Стиль: 4 пробела, строки ≤ 110 символов, `final` где уместно, records для значений, без Lombok.
- Новые зависимости не добавлять. JSON — `tools.jackson.databind.json.JsonMapper` (Jackson 3 из BOM
  Spring Boot), в `telegram` дополнительно `com.fasterxml.jackson.databind.ObjectMapper` (Jackson 2,
  нужен библиотеке telegrambots) — смешивать их в одном классе нельзя.

### Конфигурация

`UzelokProperties.Telegram` дополняется:

```java
public record Telegram(
        String botToken,
        String botUsername,
        @DefaultValue("30") int pollTimeoutSeconds,
        @DefaultValue("2000") int mediaGroupWindowMs,
        @DefaultValue("8000") int maxTextChars,
        @DefaultValue("15728640") long maxPhotoBytes,     // 15 MiB
        @DefaultValue("1048576") long maxDocumentBytes,   // 1 MiB
        @DefaultValue("30000000") long maxImagePixels) {} // 30 Мп
```

Новые записи верхнего уровня:

```java
public record Storage(@DefaultValue("/var/lib/uzelok/artifacts") String artifactsDir) {}
public record Limits(
        @DefaultValue("20") int jobsPerDay,
        @DefaultValue("5") int jobsPerHour,
        @DefaultValue("72") int attachmentTtlHours) {}
```

`@DefaultValue String storage`/`String limits` в `UzelokProperties`. `application.yml` — те же значения
(в т.ч. лимиты телеграма). Тест `UzelokPropertiesTest` дополнить.

### Домен

Новые файлы `domain/model`:

```java
public enum SourceKind { TEXT, PHOTO, DOCUMENT, TOPIC }

public record ChatAccess(UUID tenantId, UUID appUserId, long telegramUserId, boolean allowed) {}

public record InlineButton(String text, String callbackData) {}

public record InlineKeyboard(List<List<InlineButton>> rows) {
    public InlineKeyboard { rows = rows == null ? List.of() : rows.stream().map(List::copyOf).toList(); }
    public static InlineKeyboard empty() { return new InlineKeyboard(List.of()); }
}

public record AuditRecord(UUID tenantId, Long chatId, String action, String entityType, UUID entityId,
                          Map<String, Object> detail) {}
```

`SourceItem` — поле `kind` меняется на `SourceKind`.

Новые файлы `domain/port`:

```java
public interface AccessRepository {
    Optional<ChatAccess> findByChatId(long chatId);
}

public interface KnowledgeBaseRepository {
    Optional<KnowledgeBase> findByTenantId(UUID tenantId);
}

public interface TelegramUpdateRepository {
    boolean register(int updateId, Long chatId, String updateType);   // true — апдейт новый
}

public interface SourceItemRepository {
    /** Находит существующий по (chat_id, message_id) или по (chat_id, media_group_id), иначе создаёт. */
    SourceItem ensure(SourceItem candidate);
    Optional<SourceItem> findByChatAndMessage(long chatId, long telegramMessageId);
}

public interface AttachmentRepository {
    /** position выделяется атомарно (max+1), повтор при конфликте уникальности — один раз. */
    SourceAttachment append(UUID sourceItemId, UUID tenantId, String telegramFileId, String filePath,
                            String mime, long byteSize, String sha256);
    List<SourceAttachment> listBySourceItem(UUID sourceItemId);
    int countBySourceItem(UUID sourceItemId);
}

public record JobCreation(ProcessingJob job, boolean created) {}

public interface JobRepository {
    Optional<ProcessingJob> findBySourceItemId(UUID sourceItemId);
    JobCreation createIfAbsent(ProcessingJob candidate);   // UNIQUE(source_item_id)
    boolean attachStatusMessage(UUID jobId, long messageId);
    boolean postpone(UUID jobId, Instant nextRunAt);       // только для RECEIVED
    int countForChatSince(long chatId, Instant since);
}

public interface AuditRepository {
    void record(AuditRecord record);
}
```

`TelegramGateway` (порт для Bot API; в 3b добавится `answerCallbackQuery`):

```java
public interface TelegramGateway {
    /** @return telegram message_id отправленного сообщения */
    long sendMessage(long chatId, String text, InlineKeyboard keyboard);
    void editMessage(long chatId, long messageId, String text, InlineKeyboard keyboard);
    byte[] download(String fileId);
}
```

`keyboard` может быть `null` — тогда кнопок нет.

### Persistence

Пакет `uzelok/persistence`, каждый класс — `@Repository`, конструктор принимает `NamedParameterJdbcTemplate`.
Транзакции — на уровне методов адаптера (`@Transactional`), сеть внутри транзакций не вызывается никогда.
`RowMapper` — приватные статические константы в том же классе.

- `JdbcAccessRepository` — `telegram_account JOIN app_user`, возвращает `ChatAccess` (tenant, user, telegramUserId, allowed).
- `JdbcKnowledgeBaseRepository` — `knowledge_base WHERE tenant_id = ? AND active`.
- `JdbcTelegramUpdateRepository` — `INSERT INTO telegram_update (update_id, chat_id, update_type) VALUES (?,?,?) ON CONFLICT (update_id) DO NOTHING`, результат — `updateCount() == 1`.
- `JdbcSourceItemRepository`:
  - `ensure`: при непустом `media_group_id` — `SELECT ... WHERE chat_id=? AND media_group_id=?` иначе
    `SELECT ... WHERE chat_id=? AND telegram_message_id=?`; найдено → вернуть его (поля `kind`/`text_body`
    не перезаписывать); иначе `INSERT ... ON CONFLICT DO NOTHING`, затем повторный `SELECT` (гонка двух
    апдейтов альбома: победитель один, второй читает его строку).
  - `findByChatAndMessage` — простой `SELECT`.
- `JdbcAttachmentRepository.append`: одна инструкция
  `INSERT INTO source_attachment (source_item_id, tenant_id, position, telegram_file_id, file_path, mime, byte_size, sha256) SELECT ?,?,COALESCE(MAX(position)+1,0),?,?,?,?,? FROM source_attachment WHERE source_item_id=? RETURNING ...`;
  на `DuplicateKeyException` — повторить один раз (гонка), затем пробросить.
- `JdbcJobRepository`:
  - `createIfAbsent` — `INSERT ... status='RECEIVED' ON CONFLICT (source_item_id) DO NOTHING RETURNING *`;
    пусто → `SELECT` существующей и `created=false`.
  - `countForChatSince` — `JOIN source_item` по `source_item_id`, `source_item.created_at >= ?`.
  - `postpone` — `UPDATE ... SET next_run_at=?, updated_at=now() WHERE id=? AND status='RECEIVED'`.
- `JdbcAuditRepository` — `detail` сериализуется `JsonMapper` в `jsonb` (`?::jsonb`), `null` допустим.

### `V2__ingest.sql`

```sql
CREATE UNIQUE INDEX IF NOT EXISTS source_item_media_group_uniq
    ON source_item (chat_id, media_group_id) WHERE media_group_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS source_item_chat_created_idx ON source_item (chat_id, created_at);
```

Ничего не удалять и не менять в V1.

### `telegram`

Проверенные API (телеграм-библиотека 10.3.0; не выдумывать другие сигнатуры):

```java
var app = new TelegramBotsLongPollingApplication();
app.registerBot(botToken, consumer);            // LongPollingUpdateConsumer
app.close();
var client = new OkHttpTelegramClient(botToken); // implements org.telegram.telegrambots.meta.generics.TelegramClient
client.execute(SendMessage.builder().chatId(chatId).text(text).build());   // -> Message
client.execute(GetFile.builder().fileId(id).build());                      // -> org.telegram.telegrambots.meta.api.objects.File
file.getFileUrl(botToken);                                                  // готовый URL для скачивания
update.getUpdateId() -> Integer; update.getMessage() -> Message; update.getCallbackQuery() -> CallbackQuery
message.getChatId() -> Long; message.getMessageId() -> Integer; message.getFrom() -> User (getId() -> Long)
message.getText()/getCaption()/getMediaGroupId(); message.getPhoto() -> List<PhotoSize>;
message.getDocument() -> Document (getFileId/getFileName/getMimeType/getFileSize -> Long)
```

Классы (обычные, без аннотаций):

- `TelegramBotGateway implements TelegramGateway` — обёртка над `TelegramClient`: `sendMessage` (parseMode
  не задаём, текст экранировать не нужно), `editMessage` (`EditMessageText`), `download` через
  `java.net.http.HttpClient` по `file.getFileUrl(token)`; ошибки Bot API → `TelegramDeliveryException`
  (runtime, в `telegram`), текст ошибки без токена.
- `UpdateRouter implements LongPollingUpdateConsumer`:
  ```java
  public UpdateRouter(TelegramUpdateRepository updates, MessageHandler messages,
                      CallbackHandler callbacks, AuditRepository audit)
  public void consume(List<Update> updates)
  ```
  Для каждого апдейта: `register(updateId, chatId, type)`; не новый → пропустить и залогировать
  `update_id`/`type`. Исключение одного обработчика не должно убивать цикл: `catch (RuntimeException)` →
  лог `error` с `update_id` и классом исключения.
  `type`: `message` | `callback_query` | `edited_message` | `other`. `edited_message` и `other` — только
  регистрация, без действий.
- `CallbackHandler` (заготовка 3b): `handle(CallbackQuery)` — пока только `audit.record(action="callback_rejected",
  detail={reason: "callback-действия появятся на этапе 3b"})`, пользователю не отвечает.
- `MessageHandler`:
  ```java
  public MessageHandler(AccessRepository access, KnowledgeBaseRepository bases, SourceItemRepository source,
                        AttachmentRepository attachments, JobRepository jobs, AuditRepository audit,
                        TelegramGateway telegram, CommandHandler commands, AttachmentReceiver receiver,
                        Clock clock, UzelokProperties.Limits limits, UzelokProperties.Telegram telegramProps)
  public void handle(Update update)
  ```
  Порядок:
  1. `msg = update.getMessage()`, `chatId`, `fromId`.
  2. `access.findByChatId(chatId)` пусто или `!allowed` → ответ «Доступ не выдан.» одним сообщением
     (`chatId` из апдейта), `audit.record("auth_denied", detail={reason})`, выход. Ответа нет только если
     `chatId` не найден вовсе: такой чат неизвестен, писать ему небезопасно — только аудит.
  3. `fromId != access.telegramUserId()` → тишина, `audit.record("auth_denied", detail={reason:"user_mismatch"})`.
  4. Команда (`/start`, `/help` с необязательным `@botUsername`) → `CommandHandler`, выход. Прочие команды
     (`/stats`, `/ai_mode`, `/cancel`, `/export`, `/topic`) → ответ «Команда появится позже.» —
     они в 3b/этапе 4.
  5. `bases.findByTenantId(tenantId)` пусто → ответ «База знаний не подключена.» + аудит
     `onboarding_required`, выход (онбординг — этап 4).
  6. Квота: `jobs.countForChatSince(chatId, now-24h) >= jobsPerDay` или `>= jobsPerHour` за час → ответ
     «Дневной лимит задач исчерпан…», аудит `quota_exceeded`, выход.
  7. Тип входа: текст (непустой `getText`, ≤ `maxTextChars`, иначе вежливый отказ с фактической длиной);
     фото (`getPhoto` не пуст — берём последний элемент, у него максимальный размер); документ
     (`getDocument`, только `.txt`/`.md` и ≤ `maxDocumentBytes`); иначе — отказ «Поддерживаются текст,
     фото и документы .txt/.md.»
  8. `source.ensure(candidate)`; для фото/документа — `receiver.receive(sourceItem, telegramFileId, mime,
     declaredSize, fileName)` (см. ниже), для документа дополнительно прочитать сохранённый файл как
     UTF-8 текст и записать его в `source_item.text_body`, если там пусто (метод
     `SourceItemRepository.setTextBody(UUID id, String text)` — обновляет только при `text_body IS NULL`).
  9. `jobs.createIfAbsent(candidate)`; если `created` — отправить статусное сообщение
     «Принял, обрабатываю…» и `jobs.attachStatusMessage(job.id(), messageId)`, аудит `job_created`.
     Если не `created` (повторная доставка) — сообщение не отправлять повторно, только аудит `duplicate_delivery`.
  10. Альбом: если `mediaGroupId != null` — `jobs.postpone(job.id(), now + mediaGroupWindowMs)`.
  11. Исключение приёма вложения (`AttachmentRejected`) → статус не создавать, ответить причиной,
      аудит `attachment_rejected`; `source_item` при этом остаётся (без него нет второго шанса дослать).
- `AttachmentReceiver`:
  ```java
  public record StoredAttachment(Path path, String mime, long byteSize, String sha256) {}
  public AttachmentReceiver(UzelokProperties.Telegram props, UzelokProperties.Storage storage,
                            AttachmentRepository repository)
  public StoredAttachment receive(SourceItem item, String fileId, String declaredMime, long declaredSize,
                                  String fileName) throws AttachmentRejected
  ```
  Правила:
  - проверка размера **до** скачивания по `declaredSize` (> лимита → `AttachmentRejected` с лимитом);
  - скачивание через `TelegramGateway.download`;
  - MIME по содержимому (magic bytes), не по расширению: JPEG `FF D8 FF`, PNG `89 50 4E 47 0D 0A 1A 0A`,
    WebP `RIFF....WEBP`; для документа — валидный UTF-8 без `\0` (иначе отказ);
  - фактический размер → повторная проверка лимита;
  - лимит пикселей для изображений: `width*height <= maxImagePixels`, размеры из заголовка файла
    (JPEG — SOFn-маркер, PNG — IHDR, WebP — VP8/VP8L/VP8X). Не удалось прочитать размеры → отказ
    «не удалось определить размер изображения»;
  - `sha256` по фактическим байтам;
  - файл пишется в `<artifacts>/<tenantId>/<yyyy>/<MM>/<sha256>.<ext>` (`createDirectories`, `ATOMIC_MOVE`
    из `.tmp` файла в том же каталоге; файл с тем же именем уже есть → не перезаписывать);
  - после записи — `repository.append(...)`; возвращается `StoredAttachment`.
- `ImageHeader` (`telegram/ImageHeader.java`): `static Optional<Dimension> read(byte[] bytes, String mime)`.
- `Messages` + `resources/strings/ru.properties`: все тексты бота — ключами (`access.denied`,
  `access.unknown_chat`, `text.too_long`, `input.unsupported`, `document.too_big`, `photo.too_big`,
  `photo.bad_format`, `photo.bad_pixels`, `job.accepted`, `job.duplicate`, `quota.day`, `quota.hour`,
  `base.none`, `command.later`, `help.text`, `start.text`). Плейсхолдеры — `{0}`, `{1}` через
  `java.text.MessageFormat`. Класс читает `ResourceBundle.getBundle("strings.ru")`.
- `StatusMessage` (нужен в 3b, здесь — минимальный): `send(job, text)`, `edit(job, text)` с троттлингом
  «не чаще одного раза в 2 с» (проверка по `System.nanoTime`, последнее значение в поле).
- `TelegramPoller`:
  ```java
  public TelegramPoller(UzelokProperties.Telegram props, UpdateRouter router)
  public void start()   // пустой bot-token → warn «поллер не запущен: пустой токен» и выход
  public void stop()
  ```
  Держит `TelegramBotsLongPollingApplication` и `BotSession`; `start()` идемпотентен, `stop()` безопасен
  при непройденном старте.

### `config`

`TelegramConfig` (`@Configuration(proxyBeanMethods = false)`):

- `TelegramClient` — `OkHttpTelegramClient(botToken)`; бин создаётся только при непустом `botToken`
  (`@ConditionalOnProperty` не подходит — токен может быть пустым при тестах; использовать
  `@Bean @ConditionalOnExpression` нельзя, проще `ObjectProvider`); при пустом токене — `TelegramGateway`,
  который бросает `TelegramDeliveryException("bot-token не задан")`.
- `TelegramGateway`, `UpdateRouter`, `MessageHandler`, `CallbackHandler`, `CommandHandler`,
  `AttachmentReceiver`, `StatusMessage`, `TelegramPoller`.
- Запуск поллера — `SmartLifecycle`-обёртка в `config` (`TelegramPollerLifecycle`), `start()`/`stop()`
  делегируют в поллер. `@Component` не использовать: пакет `config` и так сканируется.
- `JsonMapper` (Jackson 3) — бин в `JacksonConfig` для `persistence` и 3b.

`MessageHandler` и остальные классы `telegram` остаются без аннотаций, все зависимости — конструктором.

### Тесты (JUnit 5 + AssertJ, без Spring-контекста)

Фейки портов — в `src/test/java/uzelok/support/` (`InMemoryJobRepository`, `InMemorySourceItemRepository`,
`InMemoryAttachmentRepository`, `InMemoryAccessRepository`, `InMemoryTelegramGateway`,
`RecordingAuditRepository`, `FixedKnowledgeBaseRepository`). Фейки должны вести себя как адаптеры:
`createIfAbsent` по `UNIQUE(source_item_id)`, `ensure` по message id и media group.

Обязательные тесты:

1. `UpdateRouterTest` — новый апдейт обрабатывается, повторный с тем же `update_id` — нет (счётчик вызовов
   обработчика), падение обработчика не прерывает цикл, `edited_message` не создаёт работу.
2. `MessageHandlerTest` — разрешённый чат: текст создаёт `source_item` + `job` в `RECEIVED`, статусное
   сообщение отправлено и `status_message_id` заполнен; чужой `chatId` → «Доступ не выдан», ничего не
   создано; `from.id` не совпадает → сообщений нет, есть аудит; текст длиннее 8000 → отказ и нет `job`;
   нет базы знаний → отказ; повторное сообщение с тем же `message_id` → второго `job` нет.
3. `AlbumIngestTest` — три апдейта с одним `media_group_id` → один `source_item`, три вложения,
   `next_run_at` сдвигается вперёд на каждом.
4. `AttachmentReceiverTest` — JPEG/PNG/WebP проходят (MIME по magic bytes), PDF с расширением `.jpg`
   отклоняется, файл больше лимита отклоняется **без** вызова `download`, изображение 40 Мп отклоняется,
   `sha256` совпадает с `sha256sum` байтов, файл лежит по ожидаемому пути, повторная запись того же файла
   не падает.
5. `ImageHeaderTest` — JPEG (SOF0), PNG (IHDR), WebP (VP8X), мусор → `Optional.empty()`.
6. `CommandHandlerTest` — `/start`, `/help`, `/start@bot_username`, неизвестная команда, `/stats` → «позже».
7. `StatusMessageTest` — вторая правка в пределах 2 с не выполняется, после — выполняется.
8. `JdbcPersistenceIT` — `@EnabledIfEnvironmentVariable(named = "UZELOK_TEST_DB_URL", matches = ".+")`,
   Flyway `migrate` на пустую схему, затем: `findByChatId` (allowed/unknown), `register` нового и повторного
   `update_id`, `ensure` по message id и по media group, атомарность `append` (два потока → позиции 0 и 1),
   `createIfAbsent` дважды → один job, `countForChatSince`, `postpone`, каскад `audit_event.detail` → `jsonb`.
   Без переменной тест пропускается и не мешает зелёной сборке.
9. `LayerRulesTest` — добавить правило «`@Component`/`@Scheduled` только в `uzelok.config..`, `@Repository`
   только в `uzelok.persistence..`» (существующее правило оставить).

## Критерий готовности 3a

1. `./gradlew cleanTest test` — зелёный; число тестов в `build/test-results/test/*.xml` посчитать и
   написать в отчёте `tests=N failures=0 errors=0 skipped=M`.
2. `UZELOK_TEST_DB_URL=... ./gradlew test --tests '*JdbcPersistenceIT*'` — зелёный на реальном Postgres.
3. `git status --porcelain` — только ожидаемые файлы; `docs/TZ_MVP.md`, `README.md`, `.git` не изменены.
4. Отчёт: список изменённых файлов, номер миграции, число тестов, что осталось за границей этапа.
