# Этап 6 — нейтральная база и отказ от git

Решение владельца 2026-11-03: сервис не навязывает пользователю структуру agora. Пользователь
получает сгенерированные заметки в нейтральном формате и выгружает их командой `/export`;
что делать с выгрузкой — его дело. База с контрактом (наша agora) остаётся доступной через
подключение внешней базы, но перестаёт быть обязательной.

Второе решение того же дня: из кода убираются все git-зависимости. Сервис не пишет в git
(так было с v1.2), но до сих пор читал индекс, ждал `index.lock` и определял вид базы по `.git`.

Источник: `docs/TZ_MVP.md` v1.9, §10 (контракт базы знаний), FR-14…FR-19, FR-24, §13.1.
Решения: `docs/adr/0006-drop-git-dependencies.md`, `docs/adr/0007-neutral-internal-vault.md`.

## Границы

Делаем:

- удаление `GitIndex` и всех git-проверок (`ContractValidator`, `DirVaultStore`);
- вид базы — из способа создания, а не из наличия `.git`;
- нейтральная внутренняя база: `notes/<Topic>/<Name>.md`, frontmatter без `domain`;
- `PlainVault` вместо `TemplateVault`; ресурсы `vault-template/**` удаляются;
- `PlainVaultStore` вместо `InternalDirStore`: без валидатора контракта, без линтера, без `_Inbox`;
- `/export` для внутренней базы: zip `notes/**` + `README.md`, без валидатора;
- промпт DRAFT v2: `taxonomy_mode` (`dictionary` для внешней базы, `free` для внутренней);
- миграция `V3__neutral_vault.sql`: `taxonomy_relpath`/`inbox_relpath` → nullable,
  колонки `git_remote`/`branch` удаляются;
- тексты бота: «Создать в Uzelok» / «Подключить по контракту», пояснения в `/help`.

НЕ делаем: импорт выгрузки в agora внутри сервиса (это задача агента), второй формат экспорта,
изменение API LLM, изменение схемы `note`/`knowledge_base` сверх перечисленного, RAG, FSRS.

## Целевая модель

| | `INTERNAL_DIR` (по умолчанию) | `EXTERNAL_OBSIDIAN` |
|---|---|---|
| создаётся | `PlainVault` в `<vaults-dir>/<tenantId>` | пользователем, подключается путём |
| раскладка | `notes/<Topic>/<Name>.md` | `<domain>/<topic>/<Name>.md` под `40_Knowledge/` |
| словарь тем | нет, тема свободным текстом | `_meta/taxonomy.yaml`, contract v2 |
| линтер | нет | `agora_lint.py` на временной копии |
| `_Inbox` | нет | есть |
| `domain` во frontmatter | не пишется | обязателен |
| путь наружу | `/export` | файлы уже у пользователя, `/export` отказ |

### Формат нейтральной заметки

```text
<root>/
├── README.md                  # формат, поля, как забрать выгрузку
└── notes/
    └── Kafka_Streams/
        └── Exactly_Once_Semantics.md
```

```yaml
---
type: concept                  # concept | problem | source_note
topic: "Kafka Streams"         # свободный текст от модели, не id словаря
status: raw
source: telegram
id: 0192f3c4-...               # UUID от сервиса
created: 2026-11-03
updated: 2026-11-03
summary: "одно предложение до 150 символов"
---
```

- `Topic` — `FileNamePolicy.fromTitle(draft.topic())`; пустая тема недопустима (валидатор
  черновика её и так требует), fallback — `Inbox`.
- `Name` — как сейчас: `FileNamePolicy.fromTitle(draft.title())`, ≤ `max-file-name-length`.
- Коллизия имени — `Name_<job8>` (без переноса в `_Inbox`, детерминированный суффикс).
- `problem` без `problem_*` остаётся `problem` с предупреждением в превью (переносить некуда).
- Тело — через `ContentSanitizer`, вики-ссылки разрешаются только на существующие имена.

## Изменения по файлам

### domain

- `Taxonomy`: разрешить пустой список доменов; добавить `static Taxonomy free()` →
  `new Taxonomy(0, List.of())` и `boolean isFree()`. Инвариант «словарь не пуст» переносится
  в `TaxonomyLoader` (там падать на пустом словаре контракта, как раньше).
- `KnowledgeStore.taxonomy(base)` — сигнатура не меняется; `PlainVaultStore` возвращает
  `Taxonomy.free()`.

### knowledge

- `GitIndex` — удалить файл.
- `NameUniqueness` — конструктор без аргументов, только `Files.walk`; удалить блок `gitIndex`.
- `ContractValidator` — убрать `gitDirectory`, `gitignoreHasTmpDirectory`, `gitAvailable` и
  связанные warning'и; остальное без изменений.
- `TemplateVault` — удалить; новый `PlainVault implements VaultProvisioner`: создаёт
  `<vaults-dir>/<tenantId>/notes` с правами 700 и копирует `vault-template/README.md` в корень
  базы (единственный ресурс шаблона; остальные удаляются).
- `DirVaultStore` — вынести различия в хуки:
  - `protected Layout layout(KnowledgeBase base)` → `record Layout(Path knowledgeRoot,
    Taxonomy taxonomy, List<String> warnings)`;
  - `protected boolean lintRequired()`;
  - `protected void verifyParent(KnowledgeBase base, Layout layout, Path relativeParent)`;
  - `prepare/write/existingNoteNames/taxonomy` используют `layout(...)`, а не `validator.validate`
    напрямую;
  - `writeAtomically` пишет временный файл `<targetParent>/.uzelok-tmp-<uuid>` и удаляет его в
    `finally`; каталог `.uzelok-tmp/` в корне базы не используется;
  - удалить `awaitGitIndexLock`, `gitAware()`, `LINT_COPY_SKIPPED` дополнить `README.md` не нужно,
    но копия для линтера больше не включает `.git` как особенность.
- `ExternalObsidianStore` — `layout()` = `validator.validate(base)`, `lintRequired()` = true.
- `InternalDirStore` — переименовать в `PlainVaultStore`; `layout()` =
  `new Layout(root.resolve("notes"), Taxonomy.free(), List.of())`, `lintRequired()` = false;
  `verifyParent` разрешает ровно один сегмент, проходящий `FileNamePolicy.isValid`.
- `NoteRenderer` — добавить ветку `taxonomy.isFree()`:
  - путь `notes/<TopicSlug>/<Name>.md`, `forceInbox` игнорируется (warning «внутренняя база без
    входящих»);
  - frontmatter без `domain`, с `topic` строкой;
  - низкая уверенность остаётся предупреждением.
- `ExportArchiver` — убрать `ContractValidator` из конструктора; для `INTERNAL_DIR` собирать
  от корня базы (`collect(root, root)`), пропуская любые сегменты, начинающиеся с точки;
  для `EXTERNAL_OBSIDIAN` — `ContractViolation("экспорт доступен только внутренней базе")`.
- `LinterRunner` — без изменений (используется только внешней базой).

### config

- `KnowledgeConfig`: `templateVault` → `plainVault`; `nameUniqueness` без `GitIndex`;
  `internalDirStore` → `plainVaultStore`; `vaultExporter` без валидатора.
- `UzelokProperties.Knowledge`: удалить `tmpDir`.
- `VaultOptions`: `record VaultOptions(double confidenceThreshold)`.
- `application.yml`: `prompt-version: v2`; удалить `tmp-dir`; комментарий к `lint-command`
  уточнить: «только для внешней базы с контрактом».

### llm

- `OpenAiCompatibleLlmGateway.draftPrompt`: добавить `"taxonomy_mode": "free"|"dictionary"`;
  в `free` массив `taxonomy` пустой.
- `src/main/resources/prompts/draft-v2.md` — копия v1 с правилом: «если `taxonomy_mode = free`,
  `topic` — короткое название темы (2–4 слова) на языке материала, без кавычек».
  `draft-v1.md` оставить (историчные ключи `llm_call` на него ссылаются).

### pipeline и telegram

- `OnboardingService`: `createInternal` → `KnowledgeBaseKind.INTERNAL_DIR` c
  `taxonomyRelPath = null`, `inboxRelPath = null`; `connect` → всегда `EXTERNAL_OBSIDIAN`
  (снять определение по `.git`).
- `CommandHandler` — тексты и проверка `kind` для `/export` остаются, меняется только текст.
- `strings/ru.properties`:
  - `base.prompt` — «Создать базу в Uzelok или подключить существующую по контракту?»;
  - `base.button.create` — «Создать в Uzelok»;
  - `base.button.connect` — «Подключить по контракту»;
  - `base.created` — «База создана: {0}. Заметки лежат в notes/, забрать их — /export.»;
  - `base.ask_path` — «Отправьте абсолютный путь к базе с контрактом
    (40_Knowledge/_meta/taxonomy.yaml)…»;
  - `export.external` — «Экспорт доступен для базы, созданной в Uzelok.»
  - `help` — добавить строку про формат выгрузки.

### persistence и миграции

- `V3__neutral_vault.sql`:

```sql
ALTER TABLE knowledge_base ALTER COLUMN taxonomy_relpath DROP NOT NULL;
ALTER TABLE knowledge_base ALTER COLUMN inbox_relpath DROP NOT NULL;
ALTER TABLE knowledge_base ALTER COLUMN taxonomy_relpath DROP DEFAULT;
ALTER TABLE knowledge_base ALTER COLUMN inbox_relpath DROP DEFAULT;
ALTER TABLE knowledge_base DROP COLUMN git_remote;
ALTER TABLE knowledge_base DROP COLUMN branch;
ALTER TABLE note DROP COLUMN git_commit_sha;
COMMENT ON COLUMN knowledge_base.kind IS
    'INTERNAL_DIR — нейтральная база notes/, EXTERNAL_OBSIDIAN — база с контрактом';
```

- `JdbcKnowledgeBaseRepository` — без изменений (читает те же колонки, кроме удалённых).

### deploy

- `Dockerfile` не меняется (git там и не было).
- ConfigMap линтера остаётся: он нужен внешней базе.
- `scripts/deploy.sh`, `deploy/systemd/*`, `backup-vaults.sh` — без изменений.

## Тесты

Удалить: `TemplateVaultTest`, `InternalDirStoreTest`, `InternalVaultAcceptanceTest`.
Добавить: `PlainVaultTest`, `PlainVaultStoreTest`, `PlainVaultAcceptanceTest`.
Обновить: `NameUniquenessTest` (без `GitIndex`, кейс «удалённый файл больше не занимает имя»),
`ContractValidatorTest`/`ContractValidatorViolationsTest` (git-кейсы убраны),
`ExportArchiverTest` (zip `notes/**` + `README.md`, внешняя база → отказ),
`NoteRendererTest` (путь `notes/<Topic>/`, frontmatter без `domain`, коллизия → суффикс,
`problem` не уходит в `_Inbox`), `OnboardingServiceTest` (`connect` → `EXTERNAL_OBSIDIAN` при
отсутствии `.git`), `DraftStepTest` (`Taxonomy.free()` в промпте), `PromptCatalogTest` (v2),
`JdbcPersistenceIT` (миграции до V3, `taxonomy_relpath IS NULL` для внутренней).

Обязательные проверки после правок:

```bash
export JAVA_HOME="$HOME/.local/share/jvm/jdk-25.0.5+8"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew cleanTest test
AGORA_ROOT=/mnt/hdd/workspace/agora AGORA_LINT="python3 $HOME/.hermes/scripts/agora_lint.py" \
  ./gradlew test --tests '*AgoraVaultAcceptanceTest*' --tests '*LintParityTest*'
```

## Приёмка этапа

1. Тесты: `tests=<N> failures=0 errors=0 skipped=<M>` из `build/test-results/test/*.xml`.
2. Пробник на скомпилированных классах: `PlainVault.create` → `PlainVaultStore.prepare/write` →
   файл `notes/<Topic>/<Name>.md`, frontmatter без `domain`, повтор идемпотентен, коллизия даёт
   `_<job8>`, `.uzelok-tmp-*` после записи не остаётся.
3. Экспорт: zip содержит `README.md` и `notes/**`, `fileCount` совпадает с числом файлов;
   для `EXTERNAL_OBSIDIAN` — `ContractViolation`.
4. Копия Agora: запись через `ExternalObsidianStore` проходит, лinter — 0 ошибок, предупреждений
   про git в `PreparedNote.warnings()` нет.
5. Smoke boot: `scripts/smoke-boot.sh` стартует без git в PATH и без `tmp-dir` в конфиге.
6. Живой бот: онбординг «Создать в Uzelok» создаёт `<vaults>/<tenant>/notes`; заметка пишется;
   `/export` отдаёт zip; `/export` на Agora отвечает отказом.

## Риски

- **Потеря контрактных проверок во внутренней базе.** Битые вики-ссылки и абсолютные пути не
  отсекаются; остаются санитайз и обязательные поля. Решение владельца, зафиксировано в ADR 0007.
- **Свободная тема от модели.** При импорте в agora возможны дубли («kafka» / «Kafka Streams»);
  маппинг на `domain/topic` делает агент полуавтоматически.
- **Смена промпта.** `prompt-version: v2` инвалидирует кэш `llm_call` — повторные генерации
  пойдут в модель заново и потратят бюджет.
- **Удаление `_Inbox` из внутренней базы.** Сценарий «имя занято» больше не имеет карантина:
  заметка ложится рядом с суффиксом. Это осознанно: карантин был частью контракта.
