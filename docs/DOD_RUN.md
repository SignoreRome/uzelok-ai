# Журнал приёмки Uzelok (DoD, §15 ТЗ)

Дата прогона: 2026-11-05 (UTC). Версия образа: `uzelok:0.1.2` (п.1–5, 7), `0.1.4` (п.3, 4 после фиксов).
Стенд: k3s на hector, namespace `uzelok`. Дополнение 2026-11-08 — образ `uzelok:0.1.9`, см. раздел ниже.

## Definition of Done

| № | Проверка | Результат | Доказательство (команда/лог) |
|---|----------|-----------|------------------------------|
| 1 | Фото конспекта → превью → «Сохранить» → файл в базе; Agora подхватывает sync, линтер 0 | **пройдено** (замечание к имени файла — BL-2) | `40_Knowledge/Algorithms/Trees/Binarnoe_Derevo.md`; `agora_lint.py` → «Ошибок: 0, предупреждений: 0»; `git log` → `b9d1778 sync: agora auto-update`, файл в индексе; `audit_event`: `job_created` → `callback_handled{SAVE}` → `note_saved{created:true}`; `llm_call`: EXTRACT claude-haiku-5-5 2432/652 (7.8 с), DRAFT gemini-4-argon 2405/420 (32 с), $0.009579 |
| 2 | Текстовое сообщение без OCR → тот же путь | **пройдено** (с оговоркой: DRAFT писал фолбэк) | `40_Knowledge/Java/Core_Language/Obektno_Orientirovannoe_Programmirovanie.md` (41 строка), линтер 0/0, `note_saved{created:true}`, job `DONE` с первой попытки; вход пошёл по ветке тезисов: `EXTRACT claude-haiku-5-5` 1003/416 (4.7 с), OCR не вызывался. **Оговорка:** `DRAFT` исполнен фолбэком — `requested_model=gemini-4-argon`, `model=deepseek-v4.1-flash`, `fallback_reason=HTTP_429`; качество текста этой заметки получено от другой модели, поэтому сравнение с п.1 по качеству нечистое, повтор на gemini — отдельным прогоном |
| 3 | Повторная отправка того же фото: нет второго файла, нет второго `EXTRACT` (`llm_call` не растёт) | **пройдено после фикса** (первый прогон — провал, BL-3) | Первый прогон (образ 0.1.2): второй `source_item` `6dc8588c` → job `f24c0a62` → файл `_Inbox/Binarnoe_Derevo_f24c0a62.md`, тело идентично, `llm_call` не вырос (кэш по `request_key`). После фикса (образ 0.1.4, V8 `material_hash`): фото → «Сохранить» (`17:03:37 note_saved`, заметка `40_Knowledge/Algorithms/Two_Pointers/Metod_Dvukh_Ukazateley_Two_Pointers.md`) → то же фото повторно → `17:03:56 duplicate_material` `{existing_note: Metod_Dvukh_Ukazateley_Two_Pointers.md}`; новых `source_item`/job/файлов нет (jobs 7, notes 6, линтер 0/0, sync `4a2aab6`); токен `DUP_REGEN` валиден до `2026-11-06 17:03:56` |
| 4 | `kill -9` пода на шагах `EXTRACTING` и `WRITING` → job продолжается, дублей нет | **пройдено после фикса** (EXTRACTING — 0.1.2, WRITING — 0.1.4) | EXTRACTING: watcher поллил `processing_job` каждые 0.34 с; `14:32:11.240 EXTRACTING` → `delete pod --grace-period=0 --force`, новый под в `14:32:11`; job `177fe747` остался `EXTRACTING`, `lease_until=14:42:11`; после истечения `14:42:17 EXTRACT` (2432/649) → `14:42:28 DRAFT` (2454/571) → `AWAITING_CONFIRM`, `attempt_count=1`, второго job нет. WRITING на 0.1.2: инъекция `status='WRITING'` + kill → **второй файл** `_Inbox/Otsenka_..._177fe747.md`, `note` с расхождением `file_name`/`rel_path` (BL-5, исправлено). Повтор на 0.1.4: инъекция `status='WRITING'`, `lease_until=+90s` на job `47e34beb`, `17:18:09` force-delete пода → после lease `17:19:26` job `DONE`, `notes=6`, файлов в базе 19 — второго файла нет, `file_name` и `rel_path` согласованы. Наблюдение: при `replicas: 1` lease мёртвого процесса держит задачу полные 10 минут |
| 5 | Пользователь без базы получает меню, «Создать новую» → валидная база, линтер 0, `/export` отдаёт zip | **пройдено** | Пользователь `856658972` (брат): `14:43:34 onboarding_required` → меню «Создать в Uzelok» / «Подключить по контракту» → `14:43:41 vault_created` `{root: /var/lib/uzelok/vaults/63b48f3d-…, existed: false}`, `knowledge_base.kind=INTERNAL_DIR`, каталог `700`, внутри `notes/` и `README.md`; первый `/export` на пустой базе `14:43:48` `files=1 bytes=924`; запись материала `15:47:05` → `notes/Sokety_I_Adresa/Sokety_I_Preobrazovanie_Adresov.md` (frontmatter без `domain`, тема свободным текстом), линтер не вызывался; повторный `/export` `16:04:48` `files=2 bytes=2306 parts=1` |
| 6 | Чужой `chatId`/callback → в `audit_event` `auth_denied`, действий нет | **пройдено на уровне кода и тестов** (2026-11-08); живая проверка недоступна | До 2026-11-08 код молчал, хотя UC-1 п.1 требует ответа (расхождение — BL-6). После решения владельца «пишем, что нет доступа»: `MessageHandler` отвечает `access.denied` не чаще раза в 10 минут на `chatId`, пишет ровно одно `auth_denied{reason: unknown_chat, reply: sent|throttled|failed}`, ничего не создаёт; сбой доставки наружу не уходит. Тесты: `unknownChatReceivesDeniedReply`, `repeatedMessagesFromUnknownChatAreThrottled`, `unknownChatGetsReplyAgainAfterIntervalExpires`, `unknownChatReplyFailureIsAuditedAndRetriedOnNextMessage`. Отказ по callback покрыт `CallbackHandlerTest`. **Живьём не проверялось:** аккаунта вне `app_user` нет (в базе владелец и его брат) |
| 7 | Стоимость заметки в `llm_call`, сумма за сутки ≤ бюджета | **пройдено** | Заметка `Two_Pointers` (job `47e34beb`): `EXTRACT claude-haiku-5-5` 2432/739 `$0.000613`, `DRAFT gemini-4-argon` 2472/544 `$0.010384`, итого `$0.010997` — стоимость лежит в `llm_call.cost_usd` по каждому шагу. За сутки 12 вызовов, `$0.039518` при `uzelok.llm.daily-budget-usd = 2.00` — бюджет не превышен. Слоты (`slot`) заполнены (`uzelok.extract`, `uzelok.draft`), `requested_model`/`fallback_reason` пишутся (см. п.2 — фолбэк `HTTP_429`) |
| 8 | 10 реальных заметок подряд без ручной правки YAML | не проверено (приостановлено) | Прогон остановлен по решению владельца 2026-11-05 на этапе правок; лимит `uzelok.limits.jobs-per-hour: 5` — 10 заметок требуют двух окон по часу либо поднятия лимита на время приёмки |

## Технические гейты

| Проверка | Результат | Доказательство (команда/лог) |
|----------|-----------|------------------------------|
| `kubectl -n uzelok get pod` → `1/1 Running`, RESTARTS без роста | пройдено (2026-11-08) | `uzelok-84c749b976-2r7mc` `1/1 Running`, `restartCount=0` сразу после выката 0.1.9; предыдущий под `0.1.8` жил 2d2h с нулём рестартов |
| `select version from flyway_schema_history` → все миграции применены | пройдено | `max(version::int) = 8` в базе `uzelok` (в этом релизе новых миграций нет — V8 от 06.11) |
| Ровно один процесс, поллящий Telegram (нет `409 Conflict` в логе) | пройдено | `strategy: Recreate` + `replicas: 1`; после выката в логе одна строка «поллер запущен», `409`/`Conflict` за 10 минут — 0. За 72 часа 7 строк `GetUpdates Request` — все `502 Bad Gateway`/`SocketTimeout` прокси (07.11 01:11, 08.11 01:11 и 09:18 UTC), не конфликт поллеров |
| `scripts/pg-dump.sh` создаёт валидный gzip, `gunzip -t` проходит | пройдено | `uzelok-20261108-0343.sql.gz` (62 КБ) и `vaults-20261108-0343.tar.gz` (2.1 КБ), `gzip -t` OK; `uzelok-backup.timer` активен, следующий запуск 09.11 03:44 UTC |
| После `kubectl delete pod` под поднимается и продолжает незавершённый job | пройдено | п.4: watcher ловил `EXTRACTING`, `delete pod --grace-period=0 --force`, job продолжился после истечения lease без дубля |
| Меню команд бота совпадает с `CommandHandler.COMMANDS` | пройдено (2026-11-08) | лог «меню команд установлено: 6 команд»; `getMyCommands` вернул `start, help, stats, ai_mode, cancel, export` с подписями из `strings/ru.properties` |

## Дополнение 2026-11-08: релиз 0.1.9 (BL-4, BL-6, BL-7)

Приёмка DoD 2026-11-05 оставила три записи бэклога; они закрыты PR #12 и выкачены образом `uzelok:0.1.9`.

| Что | Результат | Доказательство |
|-----|-----------|----------------|
| BL-6: ответ неизвестному `chatId` | код и тесты зелёные, п.6 DoD — «пройдено на уровне кода и тестов» | 4 теста в `MessageHandlerTest`: ответ `access.denied`, троттлинг 10 минут, повтор после истечения интервала, сбой доставки → `reply=failed` и повтор на следующем сообщении |
| BL-4: меню команд из кода | пройдено живьём | лог `меню команд установлено: 6 команд` (22:26:01 UTC); `getMyCommands` → `start, help, stats, ai_mode, cancel, export` с новыми подписями |
| BL-7: аудит повтора записи | тест `repeatedRunAuditsWriteSkipped` | `write_skipped{reason: already_written, note_id, rel_path}` при раннем выходе `WriteStep` |
| Сборка и тесты | `tests=349 failures=0 errors=0 skipped=4` (52 сьюта, 5 `*IT.xml`) | `./gradlew cleanTest test` с `UZELOK_TEST_DB_URL` в том же вызове; 4 скипа — vault-тесты без `AGORA_ROOT`/`AGORA_LINT`, отдельным прогоном с ними `BUILD SUCCESSFUL` |
| Старт контекста | пройдено | `scripts/smoke-boot.sh` на `uzelok_dev`: `Started UzelokApplication in 2.653 seconds`, «поллер не запущен: пустой токен»; в поде `Started … in 11.65 seconds` |
| Ревью другой моделью | два прогона `deepseek-v4-pro` | первый — `partial` без блокеров, 3 существенных замечания отработаны (try/catch вокруг отправки, `reply=sent` только после успеха, `anySatisfy`-с-условием); второй подтвердил закрытие, остаток вынесен в BL-8 |
| Выкат | пройдено | `deploy.sh TAG=0.1.9`: импорт образа, `rollout status` — success; под `1/1 Running`, `restartCount=0`, `409` — 0 |

Осталось до полного закрытия MVP:

- **п.8 DoD** (10 заметок подряд) — нужен телефон владельца и решение по `uzelok.limits.jobs-per-hour: 5`.
- **п.6 DoD живьём** — нужен `chatId` вне `app_user`: проще всего добавить бота в отдельную группу и
  отправить там `/start` (chatId группы в белом списке отсутствует).
- В базе висят без движения: задача `7b9f7ed4` в `FAILED` (`contract_violation` на `WRITING` от 06.11 —
  причина закрыта в `87f7fbf`) и `d4a082e8` в `AWAITING_CONFIRM` от 06.11. Живому пользователю они не мешают,
  но искажают `/stats`; чистятся переочередью и отменой.
