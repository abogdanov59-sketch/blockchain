# Runbook: проверка MVP-платформы

Документ описывает последовательность действий для оператора/ревьюера, позволяющую убедиться, что криптоконтур, песочницы, workflow, аудит и публикация якорей работают так, как предписано ТЗ.

## Предварительные требования

- Docker & Docker Compose v2.
- Порты `5432`, `8080-8094`, `9000`, `9001`, `29092`, `7050`, `7051`, `7054` свободны на хосте.
- Доступен `jq` для удобного форматирования JSON.

## 1. Запуск окружения

```bash
docker-compose build
docker-compose up -d
```

Проверить статус:

```bash
docker-compose ps
docker-compose logs -f kms-service
```

## 2. Получение токена Keycloak

```bash
TOKEN=$(curl -s -X POST "http://localhost:8081/realms/master/protocol/openid-connect/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d 'grant_type=password&client_id=admin-cli&username=admin&password=admin' \
  | jq -r '.access_token')
```

## 3. Создание ассета (data-vault)

```bash
ASSET=$(curl -s -X POST "http://localhost:8089/vault/assets" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
        "tenantId":"tenant-a",
        "type":"Order",
        "metadataHash":"hash-123",
        "state":"CREATED",
        "ownerOrg":"OrgA",
        "plaintext":"{\"terms\":\"Confidential terms\"}"
      }')
ASSET_ID=$(echo "$ASSET" | jq -r '.id')
```

### 3.1 Верификация шифрования

```bash
docker-compose exec postgres psql -U platform -d platform -c "SELECT id, ciphertext, wrapped_dek FROM vault_assets WHERE id = '$ASSET_ID';"
```

Поля `ciphertext` и `wrapped_dek` должны быть заполнены, plaintext отсутствует.

## 4. Песочница

```bash
SESSION=$(curl -s -X POST "http://localhost:8091/sandbox/sessions" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"tenantId":"tenant-a"}' | jq -r '.sessionId')

curl -s -X POST "http://localhost:8091/sandbox/sessions/$SESSION/checkout" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"assetId":"'$ASSET_ID'"}' | jq
```

### 4.1 Проверка sandbox-хранилища

```bash
docker-compose exec postgres psql -U platform -d platform -c "SELECT ciphertext, tag FROM sandbox_records LIMIT 1;"
```

Данные зашифрованы SSK, в таблице нет plaintext.

## 5. Workflow / Flowable

```bash
curl -s -X POST "http://localhost:8092/workflow/processes/CreateOrderProcess/start" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"tenantId":"tenant-a","businessKey":"order-1","variables":{"assetId":"'$ASSET_ID'"}}' | jq
```

Проверка deployment в Flowable UI: `http://localhost:8090/flowable-task` (логин/пароль `admin/test`).

## 6. Аудит и якорь

```bash
curl -s -X POST "http://localhost:8093/audit/events" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
        "tenantId":"tenant-a",
        "assetId":"'$ASSET_ID'",
        "category":"ORDER",
        "action":"CREATED",
        "payload":"order created"
      }' | jq
```

### 6.1 Проверка hash-chain

```bash
docker-compose exec postgres psql -U platform -d platform -c "SELECT event_id, payload_hash, chain_hash FROM audit_events ORDER BY position;"
```

### 6.2 Проверка blockchain gateway

```bash
curl -s "http://localhost:8094/audit/anchors" | jq
```

## 7. Kafka события

Просмотр событий в Kafka (пример через `kcat`):

```bash
docker-compose exec kafka kcat -b kafka:9092 -t vault.assets.created -C -o beginning -e
```

## 8. Очистка

```bash
docker-compose down -v
```

## Примечания

- Все сервисы публикуют `/health` и `/capabilities` для быстрой диагностики.
- Значения секретов/pepper заданы для dev-окружения в `docker-compose.yml`. В production требуется замена.
- KMS используется через общую библиотеку `libs/kms-client`; дополнительные примеры запросов — в `proto/`.

