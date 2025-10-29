# Ключевые потоки и безопасность

Документ фиксирует основные процессы обращения с ключами и данными согласно ТЗ. В текущей итерации реализован криптографический контур `crypto-lib` + `kms-service`, включая поддержку SoftHSM2 (PKCS#11), Infisical и HKDF-диверсификацию ключей сессий.

## Иерархия ключей

- **Root KMS Key (RK)** — генерируется в HSM и используется для обертки TMK.
- **Tenant Master Key (TMK)** — выделяется на арендатора, хранится в Infisical только в виде `wTMK`.
- **Data Encryption Key (DEK)** — one-per-record, хранится вместе с envelope.
- **Session Key (SSK)** — эфемерный ключ песочницы.
- **Device/Terminal Key (TDK)** — сертификаты терминалов для mTLS и подписей.
- **Ledger Signing Key (LSK)** — ключи Fabric CA.

## Потоки

1. `data-vault-service` (при реализации CRUD) запрашивает актуальный TMK у `kms-service` и использует `crypto-lib` для шифрования данных и генерации envelope (`nonce`, `ciphertext`, `tag`, `wDEK`).
2. `sandbox-service` создает nonce, инициирует derivation SSK через `kms-service` (`/keys/derive/ssk`) и обеспечивает перешифрование данных внутри песочницы.
3. `workflow-service` оркестрирует жизненный цикл активов (BPMN/DMN) и взаимодействует с Fabric через `blockchain-gateway`.
4. `audit-service` формирует неизменяемые журналы и публикует хэши в Fabric.

## Безопасность

- Шифрование на стороне приложений (AES-256-GCM).
- mTLS и OIDC для всех сервисов.
- Blind indexes на HMAC-SHA256 c pepper.
- Полный аудит операций с ключами и данными.

## Следующие шаги

- Полностью реализованы API `/keys/*` в `kms-service` с поддержкой SoftHSM2 (PKCS#11) и Infisical.
- Следующий шаг — подключение `data-vault-service`, `sandbox-service` и `audit-service` к обновленному KMS.
- Реализация управления песочницами (создание, перешифрование, уничтожение).
- Разработка chaincode транзакций: `CreateOrder`, `DispatchShipment`, `AcceptDelivery`.
