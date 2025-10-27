# Modular Data & DLT Platform (MVP Foundation)

Репозиторий содержит эталонную реализацию модульной платформы, описанной в ТЗ: общий Gradle-монорепозиторий, криптобиблиотеку, набор микросервисов (KMS, data-vault, sandbox, workflow, audit, blockchain-gateway и др.), chaincode и инфраструктурные артефакты.

## Структура

- `buildSrc` — общие версии и зависимости.
- `libs/common-models` — DTO мастер-данных и конвертов шифрования.
- `libs/crypto-lib` — криптобиблиотека (AES-GCM, AES-KW, HKDF, PBKDF2 и вспомогательные утилиты).
- `libs/kms-client` — общий REST-клиент для работы с KMS.
- `services/*` — микросервисы (identity, kms, data-vault, sandbox, workflow, blockchain-gateway, audit, api-gateway).
- `clients/terminal-agent` — CLI заглушка терминального агента.
- `chaincode/asset-lifecycle` — минимальный chaincode Hyperledger Fabric.
- `docs/` — эксплуатационная документация, схемы key-flow и сценарии проверки.
- `proto/` — OpenAPI/AsyncAPI спецификации сервисов.

## Подготовка окружения (Docker Compose)

1. Убедитесь, что установлены Docker и Docker Compose v2.
2. Выполните сборку образов:
   ```bash
   docker-compose build
   ```
3. Запустите платформу:
   ```bash
   docker-compose up -d
   ```
4. Дождитесь, пока все сервисы пройдут health-check (`docker-compose ps`). Ключевые HTTP-порты:
   - KMS — `http://localhost:8088`
   - Data Vault — `http://localhost:8089`
   - Sandbox — `http://localhost:8091`
   - Workflow — `http://localhost:8092`
   - Audit — `http://localhost:8093`
   - Blockchain gateway — `http://localhost:8094`
   - Keycloak — `http://localhost:8081`
   - Infisical — `http://localhost:8082`
   - Flowable UI — `http://localhost:8090`

Контейнеры поднимают PostgreSQL, Kafka, MinIO, Keycloak, Infisical, SoftHSM2, Flowable, тестовую сеть Fabric и все прикладные сервисы.

## Проверка сквозного сценария

Ниже приведён минимальный happy-path (использует dev-настройки docker-compose).

### 1. Получение токена Keycloak

```bash
curl -s -X POST "http://localhost:8081/realms/master/protocol/openid-connect/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d 'grant_type=password&client_id=admin-cli&username=admin&password=admin' \
  | jq -r '.access_token'
```

Сохраните токен в переменной `TOKEN` и используйте его в запросах (`Authorization: Bearer $TOKEN`).

### 2. Создание зашифрованного ассета

```bash
curl -s -X POST "http://localhost:8089/vault/assets" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
        "tenantId":"tenant-a",
        "type":"Order",
        "metadataHash":"hash-123",
        "state":"CREATED",
        "ownerOrg":"OrgA",
        "plaintext":"{\"terms\":\"Confidential terms\"}"
      }' | jq
```

В PostgreSQL (`docker-compose exec postgres psql -U platform -d platform`) убедитесь, что в таблице `vault_assets` хранится только ciphertext/wDEK — поля `ciphertext`, `wrapped_dek` и др. не содержат открытых данных.

### 3. Работа в песочнице

```bash
SESSION=$(curl -s -X POST "http://localhost:8091/sandbox/sessions" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"tenantId":"tenant-a"}' | jq -r '.sessionId')

curl -s -X POST "http://localhost:8091/sandbox/sessions/$SESSION/checkout" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"assetId":"<ID ассета из шага 2>"}' | jq
```

Запись появится в таблице `sandbox_records`; данные хранятся в виде ciphertext, зашифрованного эфемерным SSK.

### 4. Триггер workflow (Flowable)

```bash
curl -s -X POST "http://localhost:8092/workflow/processes/CreateOrderProcess/start" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"tenantId":"tenant-a","businessKey":"order-1","variables":{"assetId":"<ID ассета>"}}' | jq
```

Flowable автоматически разворачивает BPMN/DMN (см. `services/workflow-service/src/main/resources`) и фиксирует событие в Kafka (`workflow.events`).

### 5. Аудит и якорь в gateway

```bash
curl -s -X POST "http://localhost:8093/audit/events" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
        "tenantId":"tenant-a",
        "assetId":"<ID ассета>",
        "category":"ORDER",
        "action":"CREATED",
        "payload":"order created"
      }' | jq

curl -s "http://localhost:8094/audit/anchors" | jq
```

API аудита формирует хэш-цепочку в PostgreSQL (`audit_events`), публикует событие в Kafka (`audit.events`) и отправляет якорь в `blockchain-gateway`.

## Диагностика и останов

- Health-checkи доступны по `/health` у каждого сервиса.
- Для просмотра логов используйте `docker-compose logs -f <service>`.
- Для остановки окружения: `docker-compose down` (добавьте `-v` для очистки volume).

## Документация

- `docs/key-flow.md` — жизненный цикл ключей (RK/TMK/DEK/SSK).
- `docs/runbook.md` — подробный runbook и чек-листы оператора (как проверить шифрование, песочницу, workflow, audit).
- `proto/*.openapi.yaml` — OpenAPI-спецификации (KMS, Data Vault, Sandbox, Workflow, Audit).

## Требования для локальной разработки

- JDK 21+
- Gradle 8+

## История задач

1. ✅ Подключение data-vault-service, sandbox-service, workflow-service и audit-service к обновлённому KMS-контурy.
2. ✅ Интеграция с PostgreSQL, Kafka, MinIO, Flowable и Fabric SDK.
3. ✅ Docker Compose-окружение (Keycloak, Infisical, SoftHSM2, Fabric, сервисы) + инструкции запуска.
4. ✅ Реализация песочниц, workflow и аудита согласно ТЗ.
5. ✅ Документация (`docs/`) и OpenAPI спецификации (`proto/`).

