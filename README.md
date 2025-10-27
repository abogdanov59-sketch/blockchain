# Modular Data & DLT Platform (MVP Foundation)

Этот репозиторий содержит скелет модульной платформы, описанной в ТЗ. Реализована структура монорепозитория на Gradle с выделенными библиотеками, сервисами, chaincode и клиентом терминала. Цель — предоставить рабочую основу для дальнейшей реализации сквозных сценариев, криптографического контура и интеграции с Hyperledger Fabric.

## Структура

- `buildSrc` — общие версии и зависимости.
- `libs/common-models` — DTO мастер-данных и конвертов шифрования.
- `libs/crypto-lib` — криптобиблиотека c AES-GCM, AES-KW, HKDF, PBKDF2 и вспомогательными утилитами.
- `services/*` — Ktor-сервисы (identity, kms, data-vault, sandbox, workflow, blockchain-gateway, audit, api-gateway). `kms-service` реализует полный набор API `/keys/*` и интеграции с PostgreSQL, SoftHSM2 (PKCS#11) и Infisical.
- `clients/terminal-agent` — CLI-заглушка терминального агента.
- `chaincode/asset-lifecycle` — минимальный Java chaincode для Hyperledger Fabric.
- `docs/` — документация по ключевым потокам (будет пополняться).
- `proto/` — место для OpenAPI/gRPC спецификаций.

## Запуск (локально)

```bash
gradle tasks
```

Каждый сервис собирается и запускается отдельной командой, например:

```bash
gradle :services:data-vault-service:run
```

Сервис отвечает на `/health` и `/capabilities`. На следующих итерациях будут добавлены маршруты, соответствующие ТЗ (vault, sandbox, workflow, blockchain gateway и т.д.).

## Дальнейшие шаги

1. ✅ Подключить `data-vault-service`, `sandbox-service`, `workflow-service` и `audit-service` к обновленному контуру KMS.
2. Добавить интеграцию с PostgreSQL, Kafka, MinIO, Flowable и Fabric SDK в соответствующих сервисах.
3. Расширить docker-compose (Keycloak, Infisical, SoftHSM2, Fabric) скриптами bootstrap и CI-проверками.
4. Реализовать песочницы, workflow и аудит согласно разделам ТЗ.
5. Подготовить детализированную документацию в `docs/` и API спецификации в `proto/` по остальным сервисам.

## Требования

- JDK 17+
- Gradle 8+

