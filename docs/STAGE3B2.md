# Этап 3, часть 3b2 — подтверждение, запись, команды

Замыкает цикл: задача доходит из `AWAITING_CONFIRM` в `DONE`, файл появляется в базе знаний,
кнопки работают по одноразовым токенам.

Источник требований — `docs/TZ_MVP.md` v1.7: FR-16..FR-21, FR-23, FR-24 (только `/export` не наш — этап 4),
§10.3, §11, §12. ADR 0002 (Spring только в `config`/`persistence`).

## Границы

Делаем:

- `domain`: `PendingActionType`, `PendingAction`, порты `PendingActionRepository`, `NoteRepository`,
  `ActionHandler`, `ActionOutcome`; в `TelegramGateway` — `sendDocument`; в `JobRepository` —
  `findActiveByChat`, `startWriting(jobId, forceInbox)`, `bumpDraftVersion(jobId)`, `find(jobId)`.
- `persistence`: `JdbcPendingActionRepository`, `JdbcNoteRepository`, новые методы `JdbcJobRepository`.
- `telegram`: `Keyboards`, настоящий `CallbackHandler`, `Documents` (отправка `.md`), команды `/stats`,
  `/ai_mode`, `/cancel` в `CommandHandler`.
- `pipeline`: `WriteStep`, `ActionService` (SAVE/REGENERATE/INBOX/CANCEL/RETRY), доработка `JobWorker`
  (статус `WRITING`) и `DraftStep` (версия черновика из `processing_job.draft_version`).
- `config`: `ActionDispatcher` (реализация `ActionHandler`), `TelegramJobNotifier.saved(...)`, обновление
  `PipelineConfig`/`TelegramConfig`, `JobStats` для `/stats`.
- миграция `V4__confirm_write.sql`.

НЕ делаем: `/export`, `InternalDirStore`, онбординг баз, k8s, чистку вложений по TTL (этап 5).

## Жёсткие правила

- Только каталог `/mnt/hdd/workspace/uzelok-ai`. Git-команд не выполнять, `.git`, `docs/**`, `README.md`,
  `build.gradle.kts`, `gradle/libs.versions.toml` не менять. Новых зависимостей нет.
- `pipeline` — только `java..` и `uzelok.domain..`; Spring — только в `config`/`persistence`.
- `telegram` не импортирует `pipeline`, `llm`, `knowledge`, `persistence`. Действия пользователя
  диспетчеризуются через порт `domain.port.ActionHandler`, реализация — в `config`.
- Сеть — только в `telegram` и `llm`. Тесты — без внешней сети (фейковый `HttpServer` и фейки портов).
- В логах и сообщениях об ошибках — только идентификаторы, без содержимого заметок и токенов.
- Тексты пользователю по-русски, публичные имена по-английски, 4 пробела, ≤ 110 символов, records.

## Миграция `V4__confirm_write.sql`

```sql
ALTER TABLE processing_job ADD COLUMN IF NOT EXISTS draft_version int NOT NULL DEFAULT 0;
ALTER TABLE processing_job ADD COLUMN IF NOT EXISTS force_inbox boolean NOT NULL DEFAULT false;
CREATE INDEX IF NOT EXISTS pending_action_job_idx ON pending_action (job_id);
```

`draft_version` нужен FR-9 и «Перегенерировать»: 0 — первый прогон (артефакт версии 1),
после нажатия — 1, 2, … `force_inbox` — флаг «в _Inbox», выбранный пользователем.

## Домен

```java
public enum PendingActionType { SAVE, REGENERATE, INBOX, CANCEL, RETRY }

public record PendingAction(String token, UUID jobId, UUID tenantId, PendingActionType type,
                            long chatId, Long messageId, Instant expiresAt, Instant usedAt) {}

public record ActionOutcome(boolean handled, String userMessage) {
    public static ActionOutcome ignored() { return new ActionOutcome(false, null); }
    public static ActionOutcome done(String message) { return new ActionOutcome(true, message); }
}

public interface PendingActionRepository {
    PendingAction create(PendingAction action);
    /** Атомарно помечает токен использованным: UPDATE ... SET used_at = now()
     *  WHERE token = :token AND chat_id = :chatId AND used_at IS NULL AND expires_at > :now RETURNING * */
    Optional<PendingAction> consume(String token, long chatId, Instant now);
    Optional<PendingAction> findByToken(String token);
}

public record NoteRecord(UUID noteId, UUID tenantId, UUID knowledgeBaseId, UUID jobId, NoteType type,
                         String title, String domain, String topic, String fileName, String relPath,
                         String contentHash) {}

public interface NoteRepository {
    /** INSERT ... ON CONFLICT (knowledge_base_id, file_name) DO UPDATE SET content_hash, rel_path,
     *  updated_at = now() RETURNING id */
    UUID upsert(NoteRecord note);
    /** version_no = COALESCE(MAX(version_no), 0) + 1 для этого note_id. */
    void appendVersion(UUID noteId, UUID tenantId, String markdown, String contentHash);
}

public interface ActionHandler {
    ActionOutcome handle(PendingAction action);
}

public interface JobRepository {
    Optional<ProcessingJob> find(UUID jobId);
    Optional<ProcessingJob> findActiveByChat(long chatId);          // AWAITING_CONFIRM | FAILED, свежая
    boolean startWriting(UUID jobId, boolean forceInbox);           // AWAITING_CONFIRM -> WRITING + флаг
    boolean bumpDraftVersion(UUID jobId);                           // draft_version + 1
}

public interface TelegramGateway {
    /** Документ в чат; caption — короткий текст (может быть null). */
    long sendDocument(long chatId, String fileName, byte[] content, String caption);
}
```

## `persistence`

- `JdbcPendingActionRepository`: `create` — `INSERT INTO pending_action (...) RETURNING *`;
  `consume` — один `UPDATE ... RETURNING`, результат пуст → токен просрочен/использован/чужой (различать
  не нужно); `findByToken` — для диагностики.
- `JdbcNoteRepository`: `upsert` — по `UNIQUE(knowledge_base_id, file_name)`; `appendVersion` — подзапрос
  `COALESCE(MAX(version_no),0)+1`, уникальность `(note_id, version_no)` гарантирует схема.
- `JdbcJobRepository`: `findActiveByChat` — `JOIN source_item` по `chat_id`, статусы `AWAITING_CONFIRM`/`FAILED`,
  `ORDER BY j.updated_at DESC LIMIT 1`; `startWriting` — `UPDATE ... SET status='WRITING', force_inbox=:flag,
  lease_until=NULL WHERE id=:id AND status='AWAITING_CONFIRM'`; `bumpDraftVersion` —
  `UPDATE ... SET draft_version = draft_version + 1 WHERE id=:id AND status='AWAITING_CONFIRM'`.

## `pipeline`

- `DraftStep`: версия черновика = `job.draftVersion() + 1` (0 → 1). Если артефакт этой версии уже есть —
  вернуть его, LLM не вызывать. `LlmContext.draftVersion` — та же версия (входит в `request_key`).
  `RenderStep` пишет `RENDERED_MD`/`LINT_REPORT` с версией = `job.draftVersion() + 1` (сейчас жёстко 1) и
  читает артефакты именно этой версии.
- `WriteStep`:
  ```java
  public record WriteResult(WrittenNote note, PreparedNote prepared) {}
  public WriteResult run(ProcessingJob job);
  ```
  1. `DRAFT_JSON` последней версии → `NoteDraft`;
  2. `knowledge.prepare(base, job.id().toString(), draft, job.forceInbox())` — **всегда заново**, без опоры на
     артефакт, чтобы флаг `_Inbox` применялся;
  3. `knowledge.write(base, prepared)` (внутри — линтер на копии, атомарная запись);
  4. `noteRepository.upsert(...)` + `appendVersion(noteId, tenant, prepared.markdown(), hash)`;
  5. `audit("note_saved", detail={note_id, rel_path, content_hash, created})`.
- `ActionService implements domain.port.ActionHandler`:
  - `SAVE` → `startWriting(jobId, false)`; сообщение «Записываю заметку…»;
  - `INBOX` → `startWriting(jobId, true)`; сообщение «Записываю во входящие…»;
  - `REGENERATE` → `bumpDraftVersion` + переход `AWAITING_CONFIRM → DRAFTING` (через `JobService`),
    ответ «Генерирую заново…»; воркер сам сделает черновик и превью;
  - `CANCEL` → `AWAITING_CONFIRM → CANCELLED`, ответ «Отменено.»;
  - `RETRY` → `FAILED → <failed_stage>` (`failed_stage` из задачи; пусто → `RECEIVED`), сброс
    `error_code`/`error_message`, ответ «Повторяю…». Не больше 3 повторов: считаем по `attempt_count`
    и при превышении отвечаем «Повтор больше недоступен».
  - Токен, которого нет/просрочен/чужой — `ActionOutcome.ignored()`, пользователю ничего.
- `JobWorker`: добавить ветку `WRITING -> write.run(job)` и переход `WRITING → DONE`. Ошибка записи —
  обычная логика ретраев (3 попытки, потом `FAILED` с `failed_stage=WRITING`).

## `telegram`

- `Keyboards.preview(String saveToken, String regenerateToken, String inboxToken, String cancelToken)` —
  четыре кнопки в две строки: `✅ Сохранить` / `📥 В _Inbox`, `🔄 Перегенерировать` / `❌ Отмена`.
  `callbackData` = `act:<token>` (не длиннее 64 байт: сам токен — 32 hex-символа).
- `Documents`:
  ```java
  public final class Documents {
      public Documents(TelegramGateway telegram) {}
      /** Тело > 3500 символов: отправляем .md файлом, в чат — путь, тема, summary. */
      public void sendDraft(long chatId, String fileName, String markdown, String caption);
  }
  ```
  Имя файла — `<relPath>`-based стем + `.md`; содержимое UTF-8.
- `CallbackHandler` — переписать:
  ```java
  public CallbackHandler(AccessRepository access, PendingActionRepository actions,
                         ActionHandler handler, TelegramGateway telegram, AuditRepository audit, Clock clock)
  ```
  Порядок: `chatId`/`from.id` из callback (нет сообщения → игнор + аудит `callback_rejected`); доступ
  (белый список + `from.id`) → отказ с аудитом `auth_denied`; разбор `data` (`act:<token>`); `consume(token,
  chatId, now)` пусто → аудит `callback_rejected` (reason `token_invalid`), пользователю ничего;
  `handler.handle(action)` → ответ в чат сообщением `ActionOutcome.userMessage` (если не null),
  аудит `callback_handled` с типом действия.
- `CommandHandler`: добавить `/stats`, `/ai_mode`, `/cancel`.
  - `/stats` — число заметок тенанта, задач за 24 часа, потрачено за 24 часа и за текущий месяц;
  - `/ai_mode` — для каждого шага (`extract`, `draft`) провайдер, модель и fallback;
  - `/cancel` — `findActiveByChat` → `CANCELLED` (или `REJECTED`, если задача в `FAILED`), ответ с итогом.
    Команды принимаются только от разрешённого пользователя (проверка до `CommandHandler` уже в `MessageHandler`).
- `config/TelegramJobNotifier.preview` — теперь с клавиатурой; токены создаёт `config` через
  `PendingActionRepository` (TTL 24 часа, `messageId` из `PreviewView.statusMessageId()`), текст и превью —
  как сейчас (≤ 3800 символов), но при теле > 3500 символов — `Documents.sendDraft`, а в сообщении
  путь/тема/summary.

## Тесты

1. `PendingActionRepositoryIT` (в `JdbcPipelineIT` или отдельным классом) — `create`/`consume`:
   повторный `consume` пуст, истёкший токен пуст, чужой `chatId` пуст.
2. `KeyboardsTest` — 4 кнопки, `callbackData` = `act:<token>`, длина ≤ 64 байт.
3. `CallbackHandlerTest` — чужой чат/`from.id` → `auth_denied`, ничего не выполнено; неверный токен →
   `callback_rejected`; валидный → `handled`, аудит `callback_handled`, ответ отправлен; повторное
   нажатие того же токена → игнор.
4. `ActionServiceTest` — SAVE/INBOX вызывают `startWriting` с нужным флагом; REGENERATE повышает
   `draft_version` и переводит в `DRAFTING`; CANCEL → `CANCELLED`; RETRY из `FAILED` → `failed_stage`,
   при `attempt_count >= 3` отказ.
5. `WriteStepTest` (фейки) — файл пишется через `KnowledgeStore.write`, создаётся `note` и `note_version`,
   аудит `note_saved`; повторный запуск идемпотентен (`created=false`, версия добавляется только при
   изменении содержимого); `force_inbox=true` → путь `_Inbox/...`.
6. `JobWorkerTest` — `WRITING` доходит до `DONE`; ошибка записи → 3 попытки → `FAILED`.
7. `DraftStepTest` — `draft_version=1` создаёт артефакт версии 2 и новый `request_key`; при наличии
   артефакта нужной версии LLM не вызывается.
8. `DocumentsTest` — тело > 3500 → `sendDocument` с именем `*.md` и caption; тело ≤ 3500 — документ не шлётся.
9. `CommandHandlerTest` — `/stats`, `/ai_mode`, `/cancel` (задача в `AWAITING_CONFIRM` → `CANCELLED`).
10. `WritePipelineIT` — на живом PostgreSQL: `AWAITING_CONFIRM → WRITING → DONE`, `note` + `note_version`
    в БД, повторная запись того же содержимого не создаёт вторую версию.

## Критерий готовности 3b2

1. `./gradlew cleanTest test --offline` — зелёный, `tests ≥ 215`.
2. `UZELOK_TEST_DB_URL=...` — все IT зелёные на живом PostgreSQL.
3. `scripts/smoke-boot.sh` — контекст поднимается.
4. `git status --porcelain` — только ожидаемые файлы; `docs/**`, зависимости не менялись.
5. Отчёт: файлы, номер миграции, число тестов, что осталось за границей этапа.
