# Журнал приёмки Uzelok (DoD, §15 ТЗ)

Дата прогона: 2026-11-05 (UTC). Версия образа: `uzelok:0.1.2`. Стенд: k3s на hector, namespace `uzelok`.

## Definition of Done

| № | Проверка | Результат | Доказательство (команда/лог) |
|---|----------|-----------|------------------------------|
| 1 | Фото конспекта → превью → «Сохранить» → файл в базе; Agora подхватывает sync, линтер 0 | **пройдено** (замечание к имени файла — BL-2) | `40_Knowledge/Algorithms/Trees/Binarnoe_Derevo.md`; `agora_lint.py` → «Ошибок: 0, предупреждений: 0»; `git log` → `b9d1778 sync: agora auto-update`, файл в индексе; `audit_event`: `job_created` → `callback_handled{SAVE}` → `note_saved{created:true}`; `llm_call`: EXTRACT claude-haiku-5-5 2432/652 (7.8 с), DRAFT gemini-4-argon 2405/420 (32 с), $0.009579 |
| 2 | Текстовое сообщение без OCR → тот же путь | **пройдено** (с оговоркой: DRAFT писал фолбэк) | `40_Knowledge/Java/Core_Language/Obektno_Orientirovannoe_Programmirovanie.md` (41 строка), линтер 0/0, `note_saved{created:true}`, job `DONE` с первой попытки; вход пошёл по ветке тезисов: `EXTRACT claude-haiku-5-5` 1003/416 (4.7 с), OCR не вызывался. **Оговорка:** `DRAFT` исполнен фолбэком — `requested_model=gemini-4-argon`, `model=deepseek-v4.1-flash`, `fallback_reason=HTTP_429`; качество текста этой заметки получено от другой модели, поэтому сравнение с п.1 по качеству нечистое, повтор на gemini — отдельным прогоном |
| 3 | Повторная отправка того же фото: нет второго файла, нет второго `EXTRACT` (`llm_call` не растёт) | **не пройдено**: второй файл создан | То же фото байт-в-байт: `sha256=217a55ce…` у обоих вложений, но создан новый `source_item` `6dc8588c` → job `f24c0a62` → заметка `40_Knowledge/_Inbox/Binarnoe_Derevo_f24c0a62.md` (в `_Inbox` с суффиксом `_<job8>` из-за занятого имени). Тело заметки идентично первой (diff по строкам 9+ пуст), отличается только frontmatter `id`. `llm_call` не вырос: осталось 4 строки, стоимость $0.011683 не изменилась — кэш по `request_key` сработал. Дефект: дедуп есть только по `update_id` (`duplicate_delivery`), по содержимому материала — нет. Задача — BL-3 |
| 4 | `kill -9` пода на шагах `EXTRACTING` и `WRITING` → job продолжается, дублей нет | **EXTRACTING — пройдено, WRITING — не пройдено** (дубль файла) | EXTRACTING: watcher поллил `processing_job` каждые 0.34 с; `14:32:11.240 EXTRACTING` → `KILL` → `delete pod --grace-period=0 --force`, новый под `tfcln` в `14:32:11` (`Started UzelokApplication in 9.172s`); job `177fe747` остался `EXTRACTING`, `lease_until=14:42:11`; после истечения `14:42:17 EXTRACT` (2432/649) → `14:42:28 DRAFT` (2454/571) → `AWAITING_CONFIRM`, `attempt_count=1`, второго job нет. WRITING: шаг сымитирован инъекцией (`UPDATE processing_job SET status='WRITING', lease_until=now()+10min` на уже отработавшем job `177fe747`, окно реальной записи 14 мс вручную не ловится) — **записан второй файл** `_Inbox/Otsenka_..._Big_O_177fe747.md` рядом с `_Inbox/Otsenka_..._Big_O.md`, оба 2375 байт; строка `note 2182a6cb` обновлена по `id` и осталась с `file_name` без суффикса при `rel_path` с суффиксом — БД и диск разошлись. Причина: `WriteStep` ищет файл по `Path.of(note.relPath())` без корня базы, в поде CWD `/app`, проверка всегда ложна. Дефект — BL-5. Наблюдение: при `replicas: 1` lease мёртвого процесса держит задачу полные 10 минут |
| 5 | Пользователь без базы получает меню, «Создать новую» → валидная база, линтер 0, `/export` отдаёт zip | не проверено | |
| 6 | Чужой `chatId`/callback → в `audit_event` `auth_denied`, действий нет | не проверено | |
| 7 | Стоимость заметки в `llm_call`, сумма за сутки ≤ бюджета | не проверено | |
| 8 | 10 реальных заметок подряд без ручной правки YAML | не проверено | |

## Технические гейты

| Проверка | Результат | Доказательство (команда/лог) |
|----------|-----------|------------------------------|
| `kubectl -n uzelok get pod` → `1/1 Running`, RESTARTS без роста | не проверено | |
| `select version from flyway_schema_history` → все миграции применены | не проверено | |
| Ровно один процесс, поллящий Telegram (нет `409 Conflict` в логе) | не проверено | |
| `scripts/pg-dump.sh` создаёт валидный gzip, `gunzip -t` проходит | не проверено | |
| После `kubectl delete pod` под поднимается и продолжает незавершённый job | не проверено | |
