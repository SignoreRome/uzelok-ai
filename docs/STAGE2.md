# Этап 2 — база знаний: таксономия, рендер, валидатор, запись

Рабочая записка к реализации. Источник требований — `docs/TZ_MVP.md` v1.4 (разделы 7.2, 10, 19) и
фактический контракт базы `40_Knowledge/_meta/CONTRACT.md` v2. Где записка и ТЗ расходятся — прав
этот файл, он новее и учитывает проверку линтером.

## Границы этапа

Делаем только пакет `uzelok/knowledge` и минимальные правки домена, которые он требует:

- `domain`: `Taxonomy` (реальный формат Agora), `TopicRef`, `RenderedNote`, `PreparedNote`, `WrittenNote`,
  сигнатура `KnowledgeStore`.
- `knowledge`: `TaxonomyLoader`, `FileNamePolicy`, `ContentSanitizer`, `NoteRenderer`, `ContractValidator`,
  `GitIndex`, `NameUniqueness`, `LinterRunner`, `VaultLockRegistry`, `ExternalObsidianStore`.
- `config`: `UzelokProperties.Knowledge` — `maxFileNameLength` (60) вместо `maxTitleLength`, `lintTimeoutSeconds`.
- тесты и фикстуры к перечисленному.

НЕ делаем: `InternalDirStore`, `VaultTemplate`, `/export`, telegram, llm, pipeline, persistence, k8s,
миграции БД. Никаких Spring-аннотаций в `knowledge` — это обычные классы, зависимости передаются
конструктором; Spring соберёт их позже в `config`.

## Жёсткие правила

- Работать только в каталоге проекта `/mnt/hdd/workspace/uzelok-ai`.
- Не выполнять git-команд, не коммитить, не трогать `.git`, не менять `docs/TZ_MVP.md` и `README.md`.
- Не добавлять зависимости, кроме `org.yaml:snakeyaml` (версия из BOM Spring Boot; в
  `gradle/libs.versions.toml` запись без версии).
- Никакой сети. Никаких обращений к реальной базе из production-кода: путь к базе приходит параметром.
- Пакет `knowledge` не импортирует `telegram`, `llm`, `persistence`, `pipeline` (ArchUnit уже это проверяет).
- Никаких `System.out`, никакого логирования содержимого заметок. Максимум — `java.util.logging` не нужен
  вообще: предупреждения возвращаются значениями, а не пишутся в лог.
- Детерминизм: дата и идентификаторы приходят снаружи (`LocalDate`, `UUID`), кроме `LinterRunner`
  (таймаут процесса) и `Files`-операций.
- Код и сообщения об ошибках — по-русски, публичные типы и методы — по-английски, как в существующем коде.
- Стиль: 4 пробела, строки ≤ 110 символов, `final` где уместно, records для значений, без Lombok.

## Домен

### `domain/model/Taxonomy.java`

Реальный `taxonomy.yaml` Agora:

```yaml
contract_version: 2
inbox_folder: _Inbox
meta_folder: _meta
domains:
  - id: Algorithms
    aliases: [algo, algorithms, алгоритмы]
    scope: "..."
    topics:
      - id: Two_Pointers
        aliases: [two_pointers, два_указателя]
        scope: "..."
```

Модель:

```java
public record Taxonomy(int contractVersion, List<Domain> domains) {
    public static final int SUPPORTED_CONTRACT_VERSION = 2;

    public record Domain(String id, List<String> aliases, List<Topic> topics) {}
    public record Topic(String id, List<String> aliases) {}

    List<String> domainIds();
    List<String> topicIds();
    Optional<TopicRef> resolve(String topicOrAlias);   // id, затем алиас; без учёта регистра
    boolean hasTopic(String domainId, String topicId);
    Optional<TopicRef> resolveIn(String domainId, String topicOrAlias);
}
```

Инварианты в компактном конструкторе: `domains` не пуст, `id` домена и темы соответствуют
`[A-Za-z0-9_]+`, списки `null` → `List.of()`, `Map.copyOf`-подобная неизменяемость (`List.copyOf`).
Никаких YAML-зависимостей в домене.

### `domain/model/TopicRef.java`

```java
public record TopicRef(String domainId, String topicId) {}
```

### `domain/model/RenderedNote.java`

```java
public record RenderedNote(
        UUID noteId, String relPath, String fileName, String frontmatter,
        String markdown, String contentHash, List<String> warnings, boolean inbox) {}
```

`markdown` — полное содержимое файла (frontmatter + тело), `frontmatter` — только YAML-блок для превью,
`relPath` — POSIX-путь относительно корня базы (например `40_Knowledge/Algorithms/Two_Pointers/Two_Pointers.md`).

### `domain/port/PreparedNote.java`, `WrittenNote.java`, `KnowledgeStore.java`

```java
public record PreparedNote(UUID noteId, String relPath, String fileName, String markdown,
                           String contentHash, List<String> warnings) {}

public record WrittenNote(UUID noteId, String relPath, String contentHash, boolean created) {}

public interface KnowledgeStore {
    PreparedNote prepare(KnowledgeBase base, String jobRef, NoteDraft draft, boolean forceInbox)
            throws ContractViolation;
    WrittenNote write(KnowledgeBase base, PreparedNote note) throws ContractViolation;
}
```

`jobRef` — UUID задачи строкой, из него берутся первые 8 символов для суффикса коллизии. `created=false`
означает идемпотентный повтор: файл уже лежал с тем же `sha256`.

## `knowledge`

### `TaxonomyLoader`

```java
public final class TaxonomyLoader {
    public Taxonomy load(Path taxonomyFile) throws ContractViolation;
}
```

- SnakeYAML 2.6: `new Yaml(new SafeConstructor(new LoaderOptions()))`, никаких кастомных конструкторов.
- `contract_version` обязателен и равен `Taxonomy.SUPPORTED_CONTRACT_VERSION`, иначе `ContractViolation`
  с версией в сообщении. Отсутствует — тоже нарушение.
- `domains` — непустой список объектов с `id`; `topics` — список объектов с `id` (строки тоже принимать).
- Дубликаты: id домена повторяется, id темы повторяется внутри домена, **id темы встречается в двух
  доменах** — `ContractViolation` (домен темы должен быть однозначен).
- `aliases` — список строк; алиас, совпадающий с чужим id/алиасом в другом домене, не ошибка, но при
  `resolve` приоритет у точного id.
- Ошибка YAML/IO → `ContractViolation` с путём файла, без стектрейса в сообщении.

### `FileNamePolicy`

```java
public final class FileNamePolicy {
    public FileNamePolicy(int maxLength);
    public String fromTitle(String title);
    public String withSuffix(String baseName, String suffix);
    public boolean isValid(String name);
}
```

- Транслитерация ГОСТ-подобная, таблица в коде: `а a, б b, в v, г g, д d, е e, ё e, ж zh, з z, и i, й y,
  к k, л l, м m, н n, о o, п p, р r, с s, т t, у u, ф f, х kh, ц ts, ч ch, ш sh, щ sch, ъ '', ы y, ь '',
  э e, ю yu, я ya`, заглавные — соответственно (`Ж → Zh`).
- Разделители: любой символ вне `[A-Za-z0-9_]` → `_`, серии `_` сжимаются, края обрезаются.
- Регистр: каждый токен (часть между `_`) — первая буква заглавная; если токен целиком в верхнем регистре
  и длиной ≤ 5 (`HTTP`, `JVM`, `LC`) — оставить как есть; если внутри токена уже есть заглавные
  (`3Sum`, `iOS`) — не ломать внутренний регистр, только первую букву поднять.
- Пустой результат → `Note`.
- `isValid`: длина ≤ `maxLength`, соответствует `^[A-Za-z0-9_]+$`, первый символ — заглавная латинская
  буква или цифра.
- `withSuffix`: суффикс добавляется как `_<suffix>`; если не влезает в `maxLength` — обрезать базу
  (по границе `_`, иначе по символу) так, чтобы `база + "_" + suffix` влез целиком; суффикс не режется.

### `ContentSanitizer`

```java
public record SanitizedBody(String body, List<String> warnings) {}
public SanitizedBody sanitize(String bodyMd);
```

Вырезать и заменить текстовой пометкой (без содержимого):

- fenced-блоки с языком `dataview`, `dataviewjs`, `templater`, `jsengine` → `> [удалён исполняемый блок Obsidian: <язык>]`;
- конструкции `<% ... %>` (в том числе многострочные) → `[удалён шаблон Templater]`;
- `<script ...> ... </script>` → `[удалён script]`;
- ссылки `[текст](javascript:...)` → оставить `текст`;
- каждое удаление добавляет предупреждение вида `удалён исполняемый блок: dataview (1)`, агрегируя по типу.

Обычные кодовые блоки, inline-код, LaTeX, таблицы и HTML-комментарии не трогать. Пометки должны быть
обычным текстом, а не HTML-тегами (иначе они попадут в тело заметки как разметка).

### `NoteRenderer`

```java
public final class NoteRenderer {
    public NoteRenderer(FileNamePolicy names, ContentSanitizer sanitizer, double confidenceThreshold);
    public RenderedNote render(Taxonomy taxonomy, KnowledgeBase base, UUID noteId, String jobRef,
                               NoteDraft draft, boolean forceInbox, LocalDate today,
                               Set<String> existingNames);
}
```

Порог уверенности передаётся конструктором; константы в классе нет. `ExternalObsidianStore` создаёт
рендерер с `properties.confidenceThreshold()`.

Порядок:

1. `body = sanitizer.sanitize(draft.bodyMd())`; предупреждения санитайзера копятся.
2. Проверить тело на абсолютные пути: после вырезания fenced-блоков (` ```...``` `) и inline-кода
   (`` `...` ``) искать `/mnt/`, `/home/`, `/srv/`, `/Users/`, `agora/`; совпадение →
   `ContractViolation` «в тексте заметки найден абсолютный путь: <фрагмент>».
3. Определить тему: `taxonomy.resolve(draft.topic())`. Пусто → `inbox = true`, предупреждение
   «тема '<topic>' не найдена в словаре, заметка уйдёт в _Inbox».
4. `draft.confidence() < confidenceThreshold` → не блокировать, но добавить предупреждение; порог
   приходит конструктором из `uzelok.knowledge.confidence-threshold`.
5. Тип: `concept` и `source_note` рендерятся как есть (`source: telegram`); `problem` уходит в `_Inbox`
   с предупреждением «тип problem требует problem_*, которых нет в черновике»; `null` → `ContractViolation`.
6. Имя: `names.fromTitle(draft.title())`. Если имя (без учёта регистра) уже есть в `existingNames` —
   `inbox = true`, имя = `names.withSuffix(base, jobRef.substring(0, 8))`, предупреждение
   «имя занято, сохраню во входящие как <Name>».
7. Если `inbox` — путь `_Inbox/<Name>.md`, frontmatter без `domain`/`topic` (если тема не разрешена) и
   с ними, если разрешена; иначе `<domain>/<topic>/<Name>.md`.
8. Frontmatter — `LinkedHashMap` + SnakeYAML `DumperOptions`: `BLOCK`, `splitLines(false)`,
   `allowUnicode(true)`, `width(Integer.MAX_VALUE)`. Порядок ключей: `type`, `domain`, `topic`, `status`
   (`raw`), `source` (`telegram`), `id` (noteId), `created`, `updated` (ISO-дата `LocalDate`),
   `summary` (одна строка ≤ 150 символов, переводы строк → пробелы, длиннее — обрезать по границе слова
   и добавить `…`; пустой → `ContractViolation`).
9. Тело: нормализовать `\r\n` → `\n`, обрезать хвостовые пробелы, гарантировать один завершающий `\n`.
   Wikilinks `[[Цель]]`/`[[Цель|текст]]`: если цели нет в `existingNames` (сравнение по имени файла без
   `.md`, без учёта регистра) — заменить на `текст` (или `Цель`), предупреждение
   «ссылка [[Цель]] вырезана: заметки нет». Ссылки на существующие оставить.
10. `markdown = "---\n" + yaml + "---\n\n" + body` (yaml без завершающего перевода строки лишний раз),
    `contentHash = sha256(markdown в UTF-8)` hex в нижнем регистре.
11. `warnings` — неизменяемый список в порядке появления, без дублей.

Путь никогда не выходит за корень: `relPath` собирается только из `id` словаря и проверенного имени.

### `ContractValidator`

```java
public record ContractReport(Path knowledgeRoot, Taxonomy taxonomy, List<String> warnings) {}
public ContractReport validate(KnowledgeBase base) throws ContractViolation;
```

Проверки (порядок важен, первое нарушение — исключение):

1. `rootPath` существует, каталог, доступен на чтение.
2. `rootPath/taxonomyRelPath` существует и читается; `TaxonomyLoader` отдаёт словарь (его ошибки
   пробрасываются).
3. `rootPath/<metaFolder>/CONTRACT.md` существует (metaFolder = родитель taxonomy-файла).
4. `rootPath/inboxRelPath` существует как каталог, иначе предупреждение «нет каталога _Inbox, будет создан
   при записи» (создаёт его store, а не валидатор).
5. Если есть `rootPath/.git`, то `.gitignore` должен содержать строку `.uzelok-tmp/`; иначе предупреждение
   «добавьте .uzelok-tmp/ в .gitignore базы».
6. `rootPath/.git` есть, а `git` недоступен — предупреждение, не ошибка.

Ничего не пишет на диск.

### `GitIndex`

```java
public final class GitIndex {
    public Set<String> trackedFiles(Path vaultRoot);   // пути относительно vaultRoot, POSIX
}
```

- Если `vaultRoot/.git` нет — пустое множество без запуска процесса.
- Иначе `git -C <root> ls-files -z` (таймаут 5 с, stderr не пробрасывать в исключение, ошибки — пустое
  множество). Никаких `git add/commit/checkout`.

### `NameUniqueness`

```java
public final class NameUniqueness {
    public NameUniqueness(GitIndex gitIndex);
    public Set<String> existingNames(Path knowledgeRoot, Path vaultRoot); // стемы без .md, lower(Locale.ROOT)
}
```

- Обход `Files.walk(knowledgeRoot)` + `gitIndex.trackedFiles(vaultRoot)`, отфильтрованные до
  `knowledgeRoot`-относительных путей.
- Пропускать `_meta`, `.obsidian`, `.trash`, `.uzelok-tmp`, всё скрытое, `*_Template.md`.
- Только `.md`, регистр — `lower(Locale.ROOT)`, возвращается множество уникальных стемов.

### `LinterRunner`

```java
public record LintResult(int exitCode, String output) { public boolean ok(); }
public final class LinterRunner {
    public LinterRunner(String command, int timeoutSeconds);
    public LintResult run(Path vaultRoot);
}
```

- Пустая команда → `LintResult(0, "")`.
- Команда разбирается пробелами (кавычки не нужны); `{root}` заменяется на путь, иначе `--root <path>`
  дописывается в конец. Запуск через `ProcessBuilder`, объединённый stdout+stderr, UTF-8.
- Таймаут — уничтожить процесс (`destroyForcibly`), `exitCode = 124`, в отчёт «линтер не уложился в N с».
- Вывод обрезать до 4000 символов с конца (там причина).
- Не бросать исключений на ненулевой код: решение принимает store.

### `VaultLockRegistry`

```java
public final class VaultLockRegistry {
    public <T> T withLock(UUID knowledgeBaseId, java.util.function.Supplier<T> action);
}
```

`ConcurrentHashMap<UUID, ReentrantLock>`, `lock()`/`unlock()` в `finally`, без удаления локов (баз мало).

### `ExternalObsidianStore`

```java
public final class ExternalObsidianStore implements KnowledgeStore {
    public ExternalObsidianStore(UzelokProperties.Knowledge properties, ContractValidator validator,
                                 NameUniqueness uniqueness, NoteRenderer renderer, LinterRunner linter,
                                 VaultLockRegistry locks, java.time.Clock clock);
    public ExternalObsidianStore(UzelokProperties.Knowledge properties, ContractValidator validator,
                                 NameUniqueness uniqueness, FileNamePolicy names,
                                 ContentSanitizer sanitizer, LinterRunner linter,
                                 VaultLockRegistry locks, java.time.Clock clock);
}
```

Второй конструктор создаёт `NoteRenderer` с `properties.confidenceThreshold()`.

`prepare(base, jobRef, draft, forceInbox)`:

1. `validate(base)`; взять `knowledgeRoot` и `taxonomy`.
2. `existingNames` = `uniqueness.existingNames(...)`.
3. `noteId`: если `jobRef` не null и не blank —
   `UUID.nameUUIDFromBytes(("uzelok-note:" + jobRef).getBytes(UTF_8))`, иначе `UUID.randomUUID()`;
   повторный `prepare` той же задачи даёт тот же id, frontmatter и `contentHash`.
   `today = LocalDate.now(clock)` (часовой пояс — системный, но через Clock).
4. `renderer.render(...)`.
5. Проверить, что `base.rootPath.resolve(relPath).normalize()` начинается с `rootPath` и с `knowledgeRoot`.
6. Если файл уже существует с тем же `sha256` — предупреждение «заметка уже записана, повтор не нужен».
7. Вернуть `PreparedNote` (в `warnings` — всё из рендера).

`write(base, prepared)` под `locks.withLock(base.id())`:

1. Разрешить целевой путь, повторить проверку выхода за корень.
2. Проверить, что каждый существующий сегмент пути от `knowledgeRoot` до каталога заметки не является
   символической ссылкой (`Files.isSymbolicLink`); иначе `ContractViolation`
   «путь заметки проходит через символическую ссылку: <segment>».
3. Идемпотентность: файл есть и `sha256` совпадает → `WrittenNote(created=false)`; файл есть с другим
   хешем → `ContractViolation` «файл уже существует с другим содержимым: <relPath>».
4. Прогнать линтер на временной копии: скопировать `knowledgeRoot` в `<root>/.uzelok-tmp/lint-<uuid>/40_Knowledge`
   (без `.git`, `.obsidian`, `.trash`), положить туда новый файл, запустить `linter.run(tmpRoot)`, где
   `tmpRoot` — каталог с `40_Knowledge`; удалить копию в `finally`. Ненулевой код → `ContractViolation`
   с последними 4000 символами вывода и путём файла. Линтер не должен видеть `.uzelok-tmp` внутри копии.
5. Только после успешного линтера создать каталоги домена/темы (для `_Inbox` — сам `_Inbox`); перед этим
   проверить, что каждый сегмент разрешён словарём (домен и тема), кроме `_Inbox`. При падении линтера
   каталог темы и файл в базе не появляются.
6. Проверить `<root>/.git/index.lock`: если существует — ждать 2 с и повторять до 5 раз, затем
   `ContractViolation` «в базе идёт git-операция (index.lock), запись отложена».
7. Атомарная запись: временный файл `<root>/.uzelok-tmp/.<Name>.<job8>.tmp`, `Files.writeString` UTF-8,
   затем `Files.move(..., ATOMIC_MOVE)`; при `AtomicMoveNotSupportedException` — `REPLACE_EXISTING`
   после проверки отсутствия цели. Каталог `.uzelok-tmp` создать при необходимости.
8. Вернуть `WrittenNote(noteId, relPath, contentHash, true)`.

## Конфигурация

`UzelokProperties.Knowledge`:

```java
public record Knowledge(
        @DefaultValue("") String lintCommand,
        @DefaultValue("60") int lintTimeoutSeconds,
        @DefaultValue("src/test/resources/lint-fixtures") String lintParityFixtures,
        @DefaultValue(".uzelok-tmp") String tmpDir,
        @DefaultValue("0.55") double confidenceThreshold,
        @DefaultValue("60") int maxFileNameLength) {}
```

`application.yml` — те же значения; тест `UzelokPropertiesTest` обновить (`maxFileNameLength` = 60,
`lintTimeoutSeconds` = 60).

## Тесты (JUnit 5 + AssertJ, без Spring-контекста)

Фикстуры: `src/test/resources/vault-fixture/` — мини-база:

```text
vault-fixture/
├── .gitignore                (.uzelok-tmp/)
├── 40_Knowledge/
│   ├── _meta/CONTRACT.md     (короткая шапка с contract_version: 2)
│   ├── _meta/taxonomy.yaml   (домены Algorithms/Java/Networks, 2–3 темы в каждом, алиасы)
│   ├── _Inbox/
│   ├── Algorithms/Two_Pointers/Two_Pointers.md   (валидная заметка)
│   └── Algorithms/Sliding_Window/Window_Basics.md
```

Обязательные тесты:

1. `TaxonomyLoaderTest` — happy path (2 домена, алиасы), `contract_version` 1 и отсутствующий, дубль темы
   в двух доменах, пустой `domains`, битый YAML, `resolve` по id и алиасу в разном регистре.
2. `FileNamePolicyTest` — «Два указателя» → `Dva_Ukazatelya`; «HTTP/2 и TLS» → `HTTP_2_I_TLS`;
   «3Sum» → `3Sum`; пустое → `Note`; длина > 60 обрезается; суффикс влезает и не режется;
   `isValid` на пробелах/дефисе/кириллице.
3. `ContentSanitizerTest` — dataview/dataviewjs/templater-блоки, `<% %>`, `<script>`, `javascript:`,
   обычный ```java``` блок и inline-код не тронуты, предупреждения агрегированы.
4. `NoteRendererTest` — домен/тема в frontmatter, порядок ключей, `id`/`created`, путь
   `Algorithms/Two_Pointers/<Name>.md`, `status: raw`, `source: telegram`, сохраняется LaTeX и таблица,
   `\r\n` нормализуется, summary обрезается до 150, неизвестная тема → `_Inbox` с предупреждением,
   `forceInbox` → `_Inbox` даже при известной теме, `problem` → `_Inbox`, коллизия имени (в другом
   регистре/папке) → `_Inbox/<Name>_<job8>.md`, неизвестный wikilink вырезан, известный сохранён,
   тип `null` → `ContractViolation`.
5. `ContractValidatorTest` — нет `40_Knowledge`, нет `_meta`, нет `CONTRACT.md`, версия 1, нет `_Inbox`
   (предупреждение), нет строки `.uzelok-tmp/` в `.gitignore` (предупреждение).
6. `NameUniquenessTest` — коллизия с учётом регистра в другой папке, untracked-файл, `_meta` и шаблоны
   пропущены; отдельный тест с `git init` (Assumptions: git доступен) — файл в индексе, удалённый из
   рабочего дерева, всё равно считается занятым.
7. `ExternalObsidianStoreTest` (на копии `vault-fixture` в `@TempDir`) — prepare+write создаёт файл,
   содержимое совпадает, повторный write с тем же хешем → `created=false`, файл с другим хешем →
   `ContractViolation`, попытка `../` через подменённый `PreparedNote` → `ContractViolation`,
   коллизия имени → файл в `_Inbox`, `prepare` не пишет на диск.
8. `LintParityTest` — если задан `-Dagora.lint.command=<путь>` (или env `AGORA_LINT`), прогнать линтер
   на копии фикстуры с отрендеренной заметкой и проверить exit 0; без переменной — `Assumptions.assumeTrue`
   и тест пропускается. Второй кейс — фикстура с заметкой без `domain` даёт exit ≠ 0 (проверяет, что
   линтер действительно подключён).
9. `AgoraVaultAcceptanceTest` — `@EnabledIfEnvironmentVariable(named = "AGORA_ROOT", matches = ".+")`:
   скопировать реальную базу (40_Knowledge + .gitignore, без `.git`) во временный каталог, отрендерить
   и записать заметку в тему `Algorithms/Two_Pointers`, прогнать реальный линтер
   (`AGORA_LINT`, по умолчанию `/home/roman/.hermes/scripts/agora_lint.py`) → exit 0, повторный write →
   `created=false`, фронтаттер содержит `domain: Algorithms`. Без переменных окружения тест пропускается.

## Критерий готовности этапа

1. `./gradlew test` — зелёный, число тестов в отчёте сверить глазами (должно вырасти на ~40+).
2. `AGORA_ROOT=/mnt/hdd/workspace/agora ./gradlew test --tests '*AgoraVaultAcceptanceTest*'` — зелёный.
3. `python3 ~/.hermes/scripts/agora_lint.py --root <скопированная база>` на базе после записи → 0 ошибок.
4. `git status --porcelain` показывает только ожидаемые файлы; `docs/TZ_MVP.md`, `README.md`, `.git` не
   изменены.
5. В отчёте: список файлов, число тестов, вывод линтера, что осталось за границей этапа.
