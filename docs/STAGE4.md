# Этап 4 — внутренняя база знаний и `/export`

Замыкает второй режим хранения: пользователь без внешней Obsidian-базы получает внутреннюю,
созданную сервисом из шаблона, и выгружает её архивом по команде.

Источник требований — `docs/TZ_MVP.md` v1.7: UC-1 (шаги 3–5), FR-18 (`.uzelok-tmp`), FR-24 (экспорт),
§13 (`/var/lib/uzelok` — артефакты и внутренние базы), §15.5 (база создаётся и проходит линтер),
§16.5. Архитектурное решение — `docs/adr/0004-internal-vault-and-export.md`.

## Границы

Делаем:

- `knowledge`: `TemplateVault` (создание внутренней базы из шаблона), ресурсы `vault-template/**`,
  `InternalDirStore`, `KnowledgeStoreRouter`, `ExportArchiver` (zip + деление на части),
  `ContractValidator.violations(Path)`.
- `domain`: порты `VaultProvisioner`, `VaultInspector`, `VaultExporter`, `OnboardingSessionRepository`,
  `OnboardingHandler`; модели `ExportBundle`, `ExportPart`, `OnboardingResult`; расширение
  `KnowledgeBaseRepository.save(...)`, `TelegramGateway.sendDocumentFile(...)`.
- `pipeline`: `OnboardingService` (меню, создание, подключение по пути).
- `persistence`: `JdbcKnowledgeBaseRepository.save`, `JdbcOnboardingSessionRepository`, миграция
  `V5__onboarding_session.sql`.
- `telegram`: меню онбординга с кнопками, ожидание пути, команда `/export`, отправка zip файлом.
- `config`: сборка новых бинов, новые поля `UzelokProperties` и `application.yml`.
- тексты бота — только в `src/main/resources/strings/ru.properties`.

НЕ делаем: `/topic` (UC-4), выбор базы между двумя, переключение базы при уже подключённой,
TTL вложений, k8s-манифесты, бэкапы, чистку `note_version` (этап 5). Новых зависимостей нет.

## Жёсткие правила

- Только каталог `/mnt/hdd/workspace/uzelok-ai`. Git-команд не выполнять; `.git`, `README.md`,
  `build.gradle.kts`, `gradle/libs.versions.toml` не менять.
- `pipeline` — только `java..`, `org.slf4j..`, `uzelok.domain..`, `uzelok.pipeline..` (ArchUnit).
  Значит `OnboardingService` не видит `knowledge` и `telegram`: всё через порты домена.
- `telegram` не импортирует `pipeline`, `knowledge`, `persistence`, `llm`.
- `knowledge` не импортирует `telegram`, `llm`, `persistence`, `config` — поэтому
  `VaultProvisioner`/`ExportArchiver` получают параметры конструктором, а не `UzelokProperties`.
- Spring — только в `config`/`persistence`. Сеть — только в `telegram`/`llm`. Тесты без внешней сети.
- В логах и сообщениях об ошибках — только идентификаторы и пути, без содержимого заметок и токенов.
- Публичные имена по-английски, тексты пользователю по-русски (в `ru.properties`), 4 пробела,
  ≤ 110 символов, records.

## Конфиг

`UzelokProperties`:

```java
public record Storage(
        @DefaultValue("/var/lib/uzelok/artifacts") String artifactsDir,
        @DefaultValue("/var/lib/uzelok/vaults") String vaultsDir,
        @DefaultValue("/var/lib/uzelok/exports") String exportsDir) {}

public record Knowledge(
        ... существующие поля ...,
        @DefaultValue("50000000") long exportMaxBytes) {}
```

`application.yml`:

```yaml
  storage:
    artifacts-dir: ${UZELOK_ARTIFACTS_DIR:/var/lib/uzelok/artifacts}
    vaults-dir: ${UZELOK_VAULTS_DIR:/var/lib/uzelok/vaults}
    exports-dir: ${UZELOK_EXPORTS_DIR:/var/lib/uzelok/exports}
  knowledge:
    export-max-bytes: 50000000
```

Все места, где `new UzelokProperties.Knowledge(...)`/`Storage(...)` собираются вручную
(`src/test/java/uzelok/knowledge/VaultFixture.java`, `UzelokPropertiesTest`), обновить.

## Миграция `V5__onboarding_session.sql`

```sql
CREATE TABLE IF NOT EXISTS onboarding_session (
    chat_id    bigint PRIMARY KEY,
    tenant_id  uuid NOT NULL REFERENCES tenant(id),
    step       text NOT NULL CHECK (step IN ('AWAITING_PATH')),
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
```

Одна строка на чат; без истории. Состояние переживает рестарт пода — диалог не теряется.

## Домен

```java
// domain/model/OnboardingResult.java
/** messageKey — ключ из strings/ru.properties, args — подстановки MessageFormat (строки). */
public record OnboardingResult(String messageKey, List<String> args) {
    public OnboardingResult { args = args == null ? List.of() : List.copyOf(args); }
}

// domain/model/ExportPart.java
public record ExportPart(String fileName, Path path, long sizeBytes) {}

// domain/model/ExportBundle.java
public record ExportBundle(Path tempDirectory, List<ExportPart> parts, int fileCount,
                           List<String> warnings) {
    public ExportBundle {
        parts = parts == null ? List.of() : List.copyOf(parts);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}

// domain/port/VaultProvisioner.java
public interface VaultProvisioner {
    /** Создаёт внутреннюю базу в rootPath из шаблона, идемпотентно. Возвращает канонический корень. */
    Path create(Path rootPath) throws ContractViolation;
}

// domain/port/VaultInspector.java
public interface VaultInspector {
    /** Пусто — база валидна; иначе человекочитаемые нарушения контракта. */
    List<String> violations(Path rootPath);
}

// domain/port/VaultExporter.java
public interface VaultExporter {
    ExportBundle export(KnowledgeBase base) throws ContractViolation;
    /** Удаляет временные архивы; вызывается всегда, в том числе после ошибки отправки. */
    void cleanup(ExportBundle bundle);
}

// domain/port/OnboardingSessionRepository.java
public interface OnboardingSessionRepository {
    /** Перезаписывает ожидание пути для чата (step = AWAITING_PATH). */
    void awaitPath(UUID tenantId, long chatId, Instant expiresAt);
    /**
     * Атомарно забирает ожидание и удаляет строку:
     * DELETE FROM onboarding_session WHERE chat_id = :chatId AND expires_at > :now
     * RETURNING tenant_id
     */
    Optional<UUID> consumeAwaitPath(long chatId, Instant now);
    void clear(long chatId);
}

// domain/port/OnboardingHandler.java
public interface OnboardingHandler {
    boolean awaitingPath(long chatId);
    /** Запоминает ожидание пути. Если база уже подключена — вернуть ("base.already", [root]). */
    OnboardingResult expectPath(long chatId, UUID tenantId);
    OnboardingResult createInternal(long chatId, UUID tenantId);
    OnboardingResult connect(long chatId, UUID tenantId, String pathText);
    void reset(long chatId);
}

// domain/port/KnowledgeBaseRepository.java — добавить
KnowledgeBase save(KnowledgeBase base);

// domain/port/TelegramGateway.java — добавить (без default: явно реализовать во всех реализациях)
long sendDocumentFile(long chatId, String fileName, Path file, String caption);
```

`KnowledgeBaseRepository.save` — upsert по `tenant_id`:

```sql
INSERT INTO knowledge_base (id, tenant_id, kind, root_path, taxonomy_relpath, inbox_relpath)
VALUES (:id, :tenantId, :kind, :rootPath, :taxonomyRelPath, :inboxRelPath)
ON CONFLICT (tenant_id) DO UPDATE
SET kind = EXCLUDED.kind, root_path = EXCLUDED.root_path,
    taxonomy_relpath = EXCLUDED.taxonomy_relpath, inbox_relpath = EXCLUDED.inbox_relpath,
    active = true
RETURNING id, kind, root_path, taxonomy_relpath, inbox_relpath
```

`Instant` в JDBC-параметр не передавать: `OffsetDateTime.ofInstant(value, ZoneOffset.UTC)`.

## knowledge

### `TemplateVault implements VaultProvisioner`

Ресурсы: `src/main/resources/vault-template/` — копия файлов из базы `agora` на момент реализации:

```
40_Knowledge/_meta/CONTRACT.md
40_Knowledge/_meta/STRUCTURE.md
40_Knowledge/_meta/taxonomy.yaml
40_Knowledge/_meta/Note_Template.md
40_Knowledge/_meta/Problem_Template.md
```

Копировать побайтно (`cp`), не переписывать. Список путей — константа в классе; читать через
`TemplateVault.class.getResourceAsStream("/vault-template/" + relative)`. Каталоги
`40_Knowledge/_Inbox/` и `.uzelok-tmp/` создаются пустыми.

Поведение `create(root)`:

1. `root` → `toAbsolutePath().normalize()`; `Files.createDirectories(root)`; родительские каталоги
   (например `<vaults-dir>`) — `createDirectories`, права `rwx------` для корня баз, если POSIX доступен.
2. Для каждого файла шаблона:
   - нет ресурса → `ContractViolation("шаблон базы повреждён: " + relative)`;
   - файла нет → создать каталоги и записать байты;
   - файл есть и байты совпадают → пропустить (идемпотентность);
   - файл есть и отличается → `ContractViolation("в каталоге базы уже есть файл с другим содержимым: " + relative)`.
3. Создать `40_Knowledge/_Inbox` и `.uzelok-tmp` (если нет).
4. Вернуть канонический корень.

### `InternalDirStore` и `ExternalObsidianStore`

Общую логику вынести в базовый класс `DirVaultStore` (package-private, не `final`, implements
`KnowledgeStore`): текущее тело `ExternalObsidianStore` без изменений поведения, с одним хуком

```java
protected boolean gitAware() { return true; }
```

и единственным отличием: ожидание `.git/index.lock` выполняется только при `gitAware()`.
`NameUniqueness` уже корректно работает без `.git` (git-индекс пуст) — не дублировать обход дерева.

```java
public final class ExternalObsidianStore extends DirVaultStore {
    // те же два публичных конструктора, что сейчас
}

public final class InternalDirStore extends DirVaultStore {
    // те же два конструктора; gitAware() -> false
}
```

Публичный API `ExternalObsidianStore` (конструкторы) сохранить — на нём держатся существующие тесты.

### `KnowledgeStoreRouter implements KnowledgeStore`

Выбор по `base.kind()`: `EXTERNAL_OBSIDIAN` → внешний, `INTERNAL_DIR` → внутренний.
Конструктор `(KnowledgeStore external, KnowledgeStore internal)`. Метод `prepare`, `write`,
`existingNoteNames`, `taxonomy` делегируются. Именно этот бин внедряется в `DraftStep`/`RenderStep`/
`WriteStep` (имя бина `knowledgeStore`).

### `ContractValidator.violations(Path)`

`implements VaultInspector`. Собирает `KnowledgeBase(UUID.randomUUID(), INTERNAL_DIR, root, "40_Knowledge/_meta/taxonomy.yaml", "40_Knowledge/_Inbox")`
и вызывает `validate(...)`. `ContractViolation` → `List.of(message)`; успешная валидация →
пустой список (warnings сюда не входят). Относительный путь/не-каталог → одно нарушение с текстом.

### `ExportArchiver implements VaultExporter`

Конструктор: `(ContractValidator validator, Path exportsDir, long maxBytes, Clock clock)`.

`export(base)`:

1. `validator.validate(base)` → `knowledgeRoot` (`40_Knowledge`). Для `EXTERNAL_OBSIDIAN` —
   `ContractViolation("экспорт доступен только внутренней базе")` (страховка; команда проверяет вид раньше).
2. Собрать файлы: обход `knowledgeRoot`; включать только обычные файлы, символические ссылки и
   каталоги, чьё имя начинается с `.`, пропускать. Пути — относительные к корню базы
   (`40_Knowledge/_meta/taxonomy.yaml`), сортировать по строке (детерминизм).
3. Файл больше `maxBytes` → не включать, добавить предупреждение `файл больше лимита: <relPath>`.
4. Разбиение: жадный набор, пока сумма размеров файлов + 64 КиБ на запись ≤ `maxBytes * 4 / 5`;
   всегда минимум один файл в части. Один файл → одна часть.
5. Каждую часть писать в собственный временный каталог
   `Files.createTempDirectory(exportsDir, "export-")` (каталог `exportsDir` создать заранее,
   права `rwx------`), zip-энтри — относительный путь с `/`, `Files.copy` потоком.
6. Если готовая часть больше `maxBytes` — удалить её и разделить список файлов пополам (рекурсия,
   максимум 8 уровней). Один файл, который всё равно больше — пропустить с предупреждением.
7. Имя части: `uzelok-export-<kb8>-<yyyyMMdd-HHmmss>.zip` для одной части и
   `...-part<N>.zip` начиная с 1 при нескольких (`kb8` — первые 8 символов `knowledge_base.id`,
   время — `Clock` в UTC). Части нумеруются в порядке следования файлов.
8. Вернуть `ExportBundle(tempDirectory, parts, fileCount, warnings)`. Пустая база → `parts` пуст,
   `fileCount = 0`.

`cleanup(bundle)` — рекурсивно удалить `bundle.tempDirectory()`; ошибки удаления глотать.

## pipeline — `OnboardingService implements OnboardingHandler`

```java
public OnboardingService(KnowledgeBaseRepository bases, OnboardingSessionRepository sessions,
                         VaultProvisioner provisioner, VaultInspector inspector,
                         AuditRepository audit, Clock clock, Path vaultsRoot)
```

TTL ожидания пути — 15 минут от `clock.instant()`.

`expectPath(chatId, tenantId)`:
- `bases.findByTenantId(tenantId)` непусто → `OnboardingResult("base.already", List.of(rootPath))`,
  состояние не пишется;
- иначе `sessions.awaitPath(tenantId, chatId, now + 15m)` → `OnboardingResult("base.ask_path", [])`.

`createInternal(chatId, tenantId)`:
- `root = vaultsRoot.resolve(tenantId.toString())`; `boolean existed = Files.isDirectory(root.resolve("40_Knowledge"))`;
- `Path created = provisioner.create(root)`;
- `bases.save(new KnowledgeBase(UUID.randomUUID(), INTERNAL_DIR, created.toString(),
  "40_Knowledge/_meta/taxonomy.yaml", "40_Knowledge/_Inbox"))`;
- `sessions.clear(chatId)`;
- `audit.record(... "vault_created", tenantId, chatId, Map.of("root", created.toString(), "existed", Boolean.toString(existed)))`;
- результат: `existed ? ("base.exists", [path]) : ("base.created", [path])`;
- `ContractViolation` → audit `vault_onboarding_failed` (reason = message) → `("base.create_failed", [message])`.

`connect(chatId, tenantId, pathText)`:
- `pathText` обрезать; пусто/не начинается с `/` → `("base.path_relative", [])`;
- `Path.of` бросил `InvalidPathException` → `("base.path_missing", [text])`;
- нет каталога → `("base.path_missing", [abs])`;
- `canonical = toRealPath()` (симлинк-корень допустим, но хранится реальный путь);
- `violations = inspector.violations(canonical)`; непусто → audit `vault_onboarding_failed`
  (reason = первые 3 нарушения через `; `) → `("base.connect_failed", [join])`;
- вид базы: есть каталог `.git` → `EXTERNAL_OBSIDIAN`, иначе `INTERNAL_DIR`;
- `bases.save(...)`, `sessions.clear(chatId)`, audit `vault_connected`
  (`root`, `kind`);
- `("base.connected", [canonical])`.

`awaitingPath(chatId)` — `sessions.consumeAwaitPath(chatId, now).isPresent()` использовать нельзя
(иначе проверка съедает состояние). Добавить в порт `boolean isAwaitingPath(long chatId, Instant now)`
с запросом `SELECT 1 FROM onboarding_session WHERE chat_id = :chatId AND expires_at > :now`.
`reset(chatId)` — `sessions.clear(chatId)`.

## telegram

### Меню и ожидание пути

`Keyboards.onboarding()` → одна строка: `Создать новую` (`onb:create`), `Подключить мою базу`
(`onb:connect`).

`MessageHandler`:
- после проверок доступа и `commands.handle(...)`:
  - база найдена → прежний путь приёма материала;
  - базы нет и `onboarding.awaitingPath(chatId)` → `result = onboarding.connect(chatId, tenantId, text)`,
    отправить `messages.text(result.messageKey(), result.args().toArray())`, выйти;
  - базы нет → отправить `messages.text("base.prompt")` с `Keyboards.onboarding()`, выйти.
- текст команды (`text.startsWith("/")`) при ожидании пути: вызвать `onboarding.reset(chatId)` и
  отдать команду обычному обработчику (команды уже обработаны выше — значит просто сбросить и выйти).
- запись `source_item` при отсутствии базы не создаётся (как и раньше).

`CallbackHandler`: data с префиксом `onb:` обрабатывать до `pending_action`:
- `onb:create` → `onboarding.createInternal(...)`; `onb:connect` → `onboarding.expectPath(...)`;
- ответ — сообщение с текстом по ключу результата; при `base.ask_path` клавиатуру не прикладывать;
- неизвестный `onb:` → audit `callback_rejected` (`reason=onboarding_action`), молча выйти.
Проверки доступа (`chatId`, `allowed`, `from.id`) — те же, что для `act:`.

`Messages.text(key, args...)` уже принимает varargs; `List<String>` → `toArray()`.

### `/export`

`CommandHandler` получает `KnowledgeBaseRepository`, `VaultExporter`, `AuditRepository`.

```
/export:
  нет доступа → access.denied
  базы нет → base.none
  kind != INTERNAL_DIR → export.external
  bundle = exporter.export(base)
  parts пуст → export.empty (+ предупреждения отдельным сообщением export.warnings, если есть)
  parts > 1 → export.parts_note (число частей)
  для каждой части: sendDocumentFile(chatId, part.fileName(), part.path(), null)
  export.sent (файлов, частей)
  audit vault_exported {parts, files, bytes}
  ContractViolation → export.failed (message), audit export_failed
  finally → exporter.cleanup(bundle)
```

`TelegramBotGateway.sendDocumentFile` — `SendDocument` с `new InputFile(file.toFile(), fileName)`
(потоковая отправка, без чтения в `byte[]`); ошибки → `TelegramDeliveryException`.
`UnavailableTelegramGateway` — `TelegramDeliveryException`. Тестовый
`InMemoryTelegramGateway` — записывать отправленные файлы (имя + размер + содержимое не обязательно).

### Тексты (`ru.properties`)

```
base.prompt=База знаний не подключена. Создать новую или подключить существующую?
base.button.create=Создать новую
base.button.connect=Подключить мою базу
base.ask_path=Отправьте абсолютный путь к каталогу базы на сервере, например /mnt/hdd/workspace/my-vault
base.created=База создана и подключена: {0}
base.exists=База уже создана и подключена: {0}
base.connected=База подключена: {0}
base.already=База уже подключена: {0}
base.connect_failed=Не удалось подключить базу: {0}
base.create_failed=Не удалось создать базу: {0}
base.path_relative=Нужен абсолютный путь к каталогу базы (начинается с /).
base.path_missing=Каталог не найден: {0}
export.external=Экспорт нужен только для внутренней базы: внешняя уже лежит в репозитории.
export.empty=В базе пока нет файлов для экспорта.
export.sent=Архив отправлен: файлов {0}, частей {1}.
export.parts_note=Архив больше лимита Telegram — отправляю {0} частями.
export.warnings=Предупреждения экспорта:\n{0}
export.failed=Не удалось собрать архив: {0}
```

`base.none` оставить (используется при отсутствии базы в других местах). `/help` и `/start`
дополнить: как подключить базу и что делает `/export`.

## Тесты (обязательные)

Юнит-тесты без сети и без БД, кроме помеченных `IT`:

1. `knowledge/TemplateVaultTest` — создаёт дерево из шаблона; `taxonomy.yaml` и `CONTRACT.md` на месте;
   повторный `create` не меняет файлы; изменённый файл → `ContractViolation`.
2. `knowledge/InternalDirStoreTest` — на базе, созданной `TemplateVault` в `@TempDir`: `prepare` →
   `write` создаёт файл в теме; повтор того же `PreparedNote` → `created=false`, второго файла нет;
   `existingNoteNames` видит записанную заметку без `.git`.
3. `knowledge/KnowledgeStoreRouterTest` — фейковые сторы, выбор по `kind`.
4. `knowledge/ExportArchiverTest` — zip содержит `40_Knowledge/_meta/taxonomy.yaml` и файл заметки;
   `.uzelok-tmp` и скрытые каталоги не попадают; при `maxBytes=64 КиБ` несколько частей, каждая
   ≤ лимита, имена с `-partN`; один файл больше лимита → предупреждение, часть без него;
   пустая база → `parts` пуст; `cleanup` удаляет каталог.
5. `knowledge/ContractValidatorViolationsTest` — валидная база → пусто; нет `CONTRACT.md` /
   относительный путь → одно нарушение.
6. `pipeline/OnboardingServiceTest` — `expectPath` пишет сессию; `createInternal` создаёт каталог,
   сохраняет `INTERNAL_DIR`, пишет audit, второй вызов даёт `base.exists`; `connect` с валидной базой →
   `EXTERNAL_OBSIDIAN`/`INTERNAL_DIR` по наличию `.git`; относительный путь, несуществующий каталог,
   база без контракта → соответствующие ключи и отсутствие записи в БД; `reset` чистит сессию.
7. `telegram/OnboardingFlowTest` — сообщение без базы → `base.prompt` + клавиатура `onb:*`;
   после `onb:connect` следующий текст уходит в `connect`, а не в приём материала; команда сбрасывает
   ожидание; после подключения обычный текст снова создаёт `source_item`.
8. `telegram/ExportCommandTest` — внешняя база → `export.external`; внутренняя → отправлен документ,
   `cleanup` вызван; ошибка экспорта → `export.failed`, `cleanup` вызван.
9. `persistence/OnboardingSessionRepositoryIT` (`@EnabledIfEnvironmentVariable UZELOK_TEST_DB_URL`,
   своя схема как в `JdbcPersistenceIT`) — `awaitPath` → `isAwaitingPath` true; `consumeAwaitPath`
   второй раз пуст; истёкшая сессия не видна; `clear` удаляет; `save` базы дважды не создаёт вторую строку
   и обновляет `root_path`.
10. `knowledge/InternalVaultAcceptanceTest` (`@EnabledIfEnvironmentVariable AGORA_ROOT`) — база,
    созданная `TemplateVault` во временном каталоге, проходит настоящий линтер (`AGORA_LINT`, как в
    `AgoraVaultAcceptanceTest`): 0 ошибок до записи и после записи заметки через `InternalDirStore`.

Существующие тесты обязаны остаться зелёными; ожидания не подгонять под новое поведение.

## Приёмка этапа

- `./gradlew cleanTest test` — зелёный, число тестов из `build/test-results/test/*.xml`.
- `AGORA_ROOT=/mnt/hdd/workspace/agora AGORA_LINT="python3 $HOME/.hermes/scripts/agora_lint.py"
  ./gradlew test --tests '*InternalVaultAcceptanceTest*'` — зелёный.
- `UZELOK_TEST_DB_URL` → `OnboardingSessionRepositoryIT` выполняется (не skipped).
- `scripts/smoke-boot.sh` — контекст Spring поднимается с новыми бинами.
- Живой прогон: бот без базы предлагает меню; «Создать новую» создаёт каталог и базу; `/export`
  присылает zip, распаковка содержит `40_Knowledge/_meta/taxonomy.yaml` и заметки; линтер на
  созданной базе — 0.
