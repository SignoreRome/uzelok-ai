# Этап 5 — деплой в k3s, бэкапы, прогон DoD

Последний этап MVP: сервис живёт в кластере как единственный под, база бэкапится, критерии
приёмки из §15 ТЗ проверены на живом боте.

Источник требований — `docs/TZ_MVP.md` v1.7: §13.2 (окружение, монтирования, Deployment, бэкапы),
NFR-1 (лимиты), NFR-4 (одна реплика), §15 Definition of Done, §16.6. Решение — `docs/adr/0005-deploy-single-pod.md`.

## Границы

Делаем:

- `Dockerfile` (runtime-образ с JRE 25, python3 для линтера базы) и `.dockerignore`.
- `deploy/k8s/`: `namespace.yaml`, `networkpolicy.yaml`, `pvc.yaml`, `deployment.yaml`,
  `configmap-lint.yaml` (генерируется из `~/.hermes/scripts/agora_lint.py` при деплое, в git — только
  пример без содержимого), `secret.example.yaml`.
- `deploy/systemd/uzelok-backup.service`, `uzelok-backup.timer`.
- `scripts/deploy.sh` — сборка jar → образ → импорт в containerd k3s → ConfigMap линтера → `kubectl apply`;
  секреты не создаёт.
- `scripts/pg-dump.sh` — `pg_dump` базы `uzelok` через `kubectl exec postgres-0`, хранение 7 дней.
- `scripts/backup-vaults.sh` — tar внутренних баз из пода, хранение 7 дней.
- `docs/STAGE5.md` (этот файл) и `docs/DOD_RUN.md` (журнал прогона приёмки, заполняется по факту).

НЕ делаем: ingress/webhook, вторую реплику, HPA, CI-пайплайн, свой реестр образов, мониторинг
(Prometheus/Grafana), ротацию секретов, вынос LLM-ключа в отдельный Secret.

## Жёсткие правила

- **`replicas: 1` и `strategy: Recreate`** — второй под ломает long polling (`409` на `getUpdates`)
  и внутрипроцессную блокировку записи (NFR-4). Никакого rolling update: старый под обязан умереть
  до старта нового.
- **Секретов в git нет.** `secret.example.yaml` — только имена ключей и пустые значения.
  Реальный Secret создаётся `kubectl create secret generic` из `~/.hermes/secrets.env`
  (значения не печатать, не логировать, не передавать аргументом в чат).
- **Образ без реестра:** собирается `podman build` на hector и импортируется
  `sudo k3s ctr images import`. `imagePullPolicy: IfNotPresent`.
- **Под не работает от root:** `runAsUser: 1000`, `runAsGroup: 1000`, `fsGroup: 1000`,
  `runAsNonRoot: true`, `allowPrivilegeEscalation: false`, `readOnlyRootFilesystem: true`, `/tmp` — emptyDir.
- **Лимиты (NFR-1):** requests `100m/256Mi`, limits `1 CPU/1536Mi`, `-XX:MaxRAMPercentage=70`.
- **HTTP-сервер не поднимается:** проб нет (нечего опрашивать); живость подтверждается логом
  `Telegram long polling` и строками в БД, а не HTTP-пробой.
- **Внешняя база — hostPath только для тенанта владельца** (`/mnt/hdd/workspace/agora`, rw), внутренние
  базы и артефакты — PVC `uzelok-data` (5 Gi) на `/var/lib/uzelok`.
- **Линтер базы обязателен и в поде:** образ содержит `python3` + `PyYAML`, скрипт монтируется
  ConfigMap'ом из `~/.hermes/scripts/agora_lint.py` (единственный источник истины — хост).
  `uzelok.knowledge.lint-command="python3 /opt/uzelok/lint/agora_lint.py"`, таймаут 60 с.
- **Бэкапы — на хосте, не в кластере:** user-таймер systemd запускает скрипты, которые дергают
  `kubectl exec`; никаких CronJob с hostPath.

## Dockerfile

```dockerfile
FROM eclipse-temurin:25-jre-noble

RUN apt-get update \
 && apt-get install -y --no-install-recommends python3 python3-yaml ca-certificates \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY build/libs/uzelok-ai-0.0.1-SNAPSHOT.jar /app/uzelok.jar

USER 1000:1000
EXPOSE 0
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/uzelok.jar"]
```

`.dockerignore`: `build/`, `.gradle/`, `.git/`, `docs/`, `deploy/`, `*.md`, `src/`, `gradle/`.

Проверка образа до деплоя: `podman run --rm uzelok:0.1.0 java -version` (JRE 25) и
`podman run --rm uzelok:0.1.0 python3 -c 'import yaml; print(yaml.__version__)'`.

## Манифесты `deploy/k8s/`

`namespace.yaml`:

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: uzelok
  labels:
    purpose: application
```

`networkpolicy.yaml` — копия `~/work/hector-k8s/templates/app-namespace-policies.yaml` под ns `uzelok`:
default-deny ingress + ingress только от своих подов (Traefik-правило не нужно: входящих портов нет),
egress: свои поды, DNS `kube-system:53`, `infra-shared:5432`, интернет `0.0.0.0/0` с `except`
(`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `169.254.0.0/16`, `100.64.0.0/10`).

`pvc.yaml`:

```yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: uzelok-data
  namespace: uzelok
spec:
  accessModes: ["ReadWriteOnce"]
  storageClassName: local-path
  resources:
    requests:
      storage: 5Gi
```

`deployment.yaml`:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: uzelok
  namespace: uzelok
  labels:
    app.kubernetes.io/name: uzelok
spec:
  replicas: 1
  strategy:
    type: Recreate
  selector:
    matchLabels:
      app.kubernetes.io/name: uzelok
  template:
    metadata:
      labels:
        app.kubernetes.io/name: uzelok
    spec:
      securityContext:
        runAsUser: 1000
        runAsGroup: 1000
        fsGroup: 1000
        runAsNonRoot: true
        seccompProfile:
          type: RuntimeDefault
      containers:
        - name: uzelok
          image: uzelok:0.1.0
          imagePullPolicy: IfNotPresent
          envFrom:
            - secretRef:
                name: uzelok-secrets
          env:
            - name: UZELOK_ARTIFACTS_DIR
              value: /var/lib/uzelok/artifacts
            - name: UZELOK_VAULTS_DIR
              value: /var/lib/uzelok/vaults
            - name: UZELOK_EXPORTS_DIR
              value: /var/lib/uzelok/exports
            - name: UZELOK_KNOWLEDGE_LINT_COMMAND
              value: python3 /opt/uzelok/lint/agora_lint.py
          resources:
            requests:
              cpu: 100m
              memory: 256Mi
            limits:
              cpu: "1"
              memory: 1536Mi
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
          volumeMounts:
            - name: data
              mountPath: /var/lib/uzelok
            - name: vault-agora
              mountPath: /mnt/hdd/workspace/agora
            - name: lint
              mountPath: /opt/uzelok/lint
              readOnly: true
            - name: tmp
              mountPath: /tmp
      volumes:
        - name: data
          persistentVolumeClaim:
            claimName: uzelok-data
        - name: vault-agora
          hostPath:
            path: /mnt/hdd/workspace/agora
            type: Directory
        - name: lint
          configMap:
            name: uzelok-lint
        - name: tmp
          emptyDir: {}
```

`secret.example.yaml` — список ключей без значений (`UZELOK_DB_URL`, `UZELOK_DB_USER`,
`UZELOK_DB_PASSWORD`, `UZELOK_BOT_TOKEN`, `UZELOK_BOT_USERNAME`, `UZELOK_TELEGRAM_PROXY`,
`UZELOK_LLM_PROXY`, `UZELOK_LLM_OPENCODE_GO_KEY`, `UZELOK_LLM_OPENROUTER_KEY`).

## `scripts/deploy.sh`

```bash
#!/usr/bin/env bash
# Сборка и раскатка uzelok в k3s. Секреты не создаёт и не читает.
set -euo pipefail
```

Шаги: `./gradlew bootJar` → `podman build -t uzelok:$TAG .` → `podman save` в tar в
`/home/roman/.hermes/cache/scratch` → `sudo k3s ctr images import` → `kubectl create configmap uzelok-lint
-n uzelok --from-file=agora_lint.py=$HOME/.hermes/scripts/agora_lint.py --dry-run=client -o yaml | kubectl apply -f -`
→ `kubectl apply -f deploy/k8s/namespace.yaml -f deploy/k8s/pvc.yaml -f deploy/k8s/networkpolicy.yaml
-f deploy/k8s/deployment.yaml` → `kubectl -n uzelok rollout status deploy/uzelok --timeout=120s`.
`TAG` по умолчанию `0.1.0`.

## Бэкапы

`scripts/pg-dump.sh`:

- `kubectl exec -n infra-shared postgres-0 -- pg_dump -U postgres -d uzelok --no-owner | gzip -9 >
  /mnt/hdd/backups/uzelok/uzelok-$(date -u +%Y%m%d-%H%M).sql.gz`
- удалять файлы старше 7 дней (`find ... -mtime +7 -delete`), писать строку в
  `/mnt/hdd/backups/uzelok/backup.log` без содержимого БД.
- пустой дамп (< 1 КиБ) — ошибка, не удалять предыдущие.

`scripts/backup-vaults.sh`:

- `kubectl exec -n uzelok deploy/uzelok -- tar -C /var/lib/uzelok -czf - vaults > /mnt/hdd/backups/uzelok/vaults-$(date -u +%Y%m%d-%H%M).tar.gz`
- пустой каталог (`vaults` нет) — не ошибка, файл не создаётся; хранение 7 дней.

`deploy/systemd/uzelok-backup.service` (Type=oneshot, `Environment=KUBECONFIG=/home/roman/.kube/config`,
`ExecStart=/mnt/hdd/workspace/uzelok-ai/scripts/pg-dump.sh` и второй `ExecStart` для vaults) и
`uzelok-backup.timer` (`OnCalendar=*-*-* 03:40:00`, `Persistent=true`, `RandomizedDelaySec=300`).

## Приёмка (Definition of Done, §15)

Проверяется на живом боте после раскатки; результаты — в `docs/DOD_RUN.md`.

1. Фото конспекта → превью → «Сохранить» → файл в базе (Agora подхватывает sync, линтер 0).
2. Текстовое сообщение без OCR → тот же путь.
3. Повторная отправка того же фото: нет второго файла, нет второго `EXTRACT` (`llm_call` не растёт).
4. `kill -9` пода на `EXTRACTING` и `WRITING` → job продолжается, дублей нет.
5. Пользователь без базы получает меню, «Создать новую» → валидная база, линтер 0, `/export` отдаёт zip.
6. Чужой `chatId`/callback → в `audit_event` `auth_denied`, действий нет.
7. Стоимость заметки в `llm_call`, сумма за сутки ≤ бюджета.
8. 10 реальных заметок подряд без ручной правки YAML.

Технические гейты (выполняются без телефона):
- `kubectl -n uzelok get pod` → `1/1 Running`, RESTARTS без роста;
- `select version from flyway_schema_history` → все миграции применены;
- ровно один процесс, поллящий Telegram (нет `409 Conflict` в логе);
- `scripts/pg-dump.sh` создаёт валидный gzip, `gunzip -t` проходит;
- после `kubectl delete pod` под поднимается и продолжает незавершённый job.
