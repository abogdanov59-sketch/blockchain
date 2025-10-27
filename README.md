# Modular Data & DLT Platform (MVP Foundation)

Этот репозиторий содержит скелет модульной платформы, описанной в ТЗ. Реализована структура монорепозитория на Gradle с выделенными библиотеками, сервисами, chaincode и клиентом терминала. Цель — предоставить рабочую основу для дальнейшей реализации сквозных сценариев, криптографического контура и интеграции с Hyperledger Fabric.

## Структура

- `buildSrc` — общие версии и зависимости.
- `libs/common-models` — DTO мастер-данных и конвертов шифрования.
- `libs/crypto-lib` — заглушка криптобиблиотеки (nonce, zeroization).
- `services/*` — Ktor-сервисы (identity, kms, data-vault, sandbox, workflow, blockchain-gateway, audit, api-gateway) с единым каркасом API `/health` и `/capabilities`.
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

1. Реализовать криптографический контур в `crypto-lib` и `kms-service` (wrap/unwrap, HKDF, PKCS#11 и Infisical).
2. Добавить интеграцию с PostgreSQL, Kafka, MinIO, Flowable и Fabric SDK.
3. Настроить docker-compose с Keycloak, Infisical, SoftHSM2 и Fabric test network.
4. Реализовать песочницы, workflow и аудит согласно разделам ТЗ.
5. Подготовить детализированную документацию в `docs/` и API спецификации в `proto/`.

## Требования

- JDK 17+
- Gradle 8+

