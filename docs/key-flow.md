# Ключевые потоки и безопасность

Документ фиксирует основные процессы обращения с ключами и данными согласно ТЗ. На текущем этапе реализованы каркасы сервисов; подробные интеграции будут добавляться итеративно.

## Иерархия ключей

- **Root KMS Key (RK)** — генерируется в HSM и используется для обертки TMK.
- **Tenant Master Key (TMK)** — выделяется на арендатора, хранится в Infisical только в виде `wTMK`.
- **Data Encryption Key (DEK)** — one-per-record, хранится вместе с envelope.
- **Session Key (SSK)** — эфемерный ключ песочницы.
- **Device/Terminal Key (TDK)** — сертификаты терминалов для mTLS и подписей.
- **Ledger Signing Key (LSK)** — ключи Fabric CA.

## Потоки

1. `data-vault-service` запрашивает актуальный TMK у `kms-service` и использует `crypto-lib` для шифрования данных и генерации envelope.
2. `sandbox-service` создает nonce, инициирует derivation SSK через `kms-service` и обеспечивает перешифрование данных внутри песочницы.
3. `workflow-service` оркестрирует жизненный цикл активов (BPMN/DMN) и взаимодействует с Fabric через `blockchain-gateway`.
4. `audit-service` формирует неизменяемые журналы и публикует хэши в Fabric.

## Безопасность

- Шифрование на стороне приложений (AES-256-GCM).
- mTLS и OIDC для всех сервисов.
- Blind indexes на HMAC-SHA256 c pepper.
- Полный аудит операций с ключами и данными.

## Следующие шаги

- Интеграция с SoftHSM2 и реализация API `/keys/*` в `kms-service`.
- Настройка Infisical для хранения `wTMK`, pepper и PKI.
- Реализация управления песочницами (создание, перешифрование, уничтожение).
- Разработка chaincode транзакций: `CreateOrder`, `DispatchShipment`, `AcceptDelivery`.
