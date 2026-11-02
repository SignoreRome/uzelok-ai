# ADR 0002: Spring-аннотации только в `config` и `persistence`

**Статус:** принято (этап 3, часть 3a).

Бины приложения собираются в одном месте — пакет `config`. Классы `telegram`, `llm`, `knowledge` и
`pipeline` не носят Spring-аннотаций и не зависят от Spring API: зависимости приходят конструктором.
Единственное исключение — `persistence` с `@Repository` и `@Transactional` (там живут JDBC-адаптеры).

Причина: правило §7.1.2 ТЗ требует, чтобы `pipeline` зависел только от `domain` и JDK. `@Scheduled`/`@Component`
в `pipeline` нарушают это правило, а `@Transactional` тянет `org.springframework.transaction` внутрь слоя,
который должен тестироваться без контекста. Тот же принцип распространён на `telegram`, `llm` и `knowledge`
ради единообразия: бизнес-логика проверяется обычными unit-тестами, а не `@SpringBootTest`.

Следствия:

- `JobWorker` (этап 3b) будет обычным классом с методом `runOnce()`; `@Scheduled`-обёртка живёт в
  `config.SchedulingConfig`.
- Запуск long polling оформляется `SmartLifecycle` в `config`, а не `@PostConstruct` в `telegram`.
- `LayerRulesTest` проверяет: `@Component`/`@Scheduled` — только в `uzelok.config..`,
  `@Repository` — только в `uzelok.persistence..`.

Рассматривалась альтернатива «аннотации в каждом пакете, как в §7 ТЗ» — отвергнута: она конфликтует
с ArchUnit-правилом и делает юнит-тесты зависимыми от контекста Spring.
