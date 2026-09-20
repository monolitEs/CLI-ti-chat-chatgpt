# Минимальный чат-сервис через Codex CLI

Compose запускает два сервиса:

- `codex` — Spring Boot REST API и дочерний `codex app-server`;
- `gost` — переводит HTTP proxy-запросы Codex в личный SOCKS5.

Исходящие запросы Codex настроены на GOST через системные proxy-переменные. API
опубликован только на loopback хоста, а другой контейнер обращается к нему через
сеть `cli-to-chat`.

## Настройка

Создайте локальный `.env` из примера:

```powershell
Copy-Item .env.example .env
```

Заполните две переменные:

```dotenv
SOCKS5_PROXY_URL='socks5://username:password@proxy.example.com:1080'
CODEX_DATA_DIR='D:/Documents/codex-chat-data'
```

Спецсимволы в имени пользователя и пароле кодируются как URL percent-escapes.
Не добавляйте `.env` в Git и не публикуйте вывод `docker compose config` или
`docker inspect`: в нём могут находиться credentials.

## Сборка и вход через ChatGPT

```powershell
docker compose build codex
docker compose run --rm codex login --device-auth
docker compose run --rm codex login status
```

Авторизация и данные Codex сохраняются в `CODEX_DATA_DIR`. Проекты и документы
хоста в контейнер не подключаются.

## Запуск сервиса

```powershell
docker compose up -d
docker compose ps
```

Spring Boot запускается по умолчанию. API доступен на рабочей машине по адресу
`http://localhost:8080` и контейнерам сети `cli-to-chat` по адресу
`http://codex:8080`. Binding `127.0.0.1:8080` не публикует API во внешнюю сеть.
Сетевая изоляция не блокирует direct egress: соблюдение proxy обеспечивают
`respect_system_proxy = true` и переменные `HTTP_PROXY`/`HTTPS_PROXY`/`ALL_PROXY`.

Проверка с хоста в PowerShell:

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri http://localhost:8080/api/chat `
  -ContentType 'application/json' `
  -Body '{"message":"Привет"}'
```

## Подключение контейнера-потребителя

В другом Compose-проекте подключите сервис-потребитель к существующей сети:

```yaml
services:
  consumer:
    networks:
      - cli-to-chat

networks:
  cli-to-chat:
    external: true
    name: cli-to-chat
```

После подключения endpoint доступен по имени сервиса:

```text
POST http://codex:8080/api/chat
Content-Type: application/json

{"message":"Привет"}
```

Успешный ответ:

```json
{"requestId":"...","answer":"..."}
```

Одновременно обрабатывается один запрос. Второй получает `409 CHAT_BUSY`.
Общий timeout ответа — 10 минут. Известные отказы Codex возвращаются как
контролируемые `502`, `503` или `504` с `requestId`.

## Остановка и откат

```powershell
docker compose down
```

Команда удаляет контейнеры и Compose-сети, но сохраняет `.env` и содержимое
`CODEX_DATA_DIR`. Для отката образа верните предыдущие Dockerfile/Compose/README
и пересоберите `codex`; папку авторизации удалять не нужно.

## Диагностика

```powershell
docker compose ps
docker compose logs --tail 100 codex
docker compose logs --tail 50 gost
```

Логи приложения содержат request/thread/turn ID, длительность и нормализованный
код ошибки. Текст запроса, ответ, auth tokens и SOCKS5 credentials приложение не
логирует. Если авторизация или запрос не работают, проверьте ChatGPT login,
доступность SOCKS5 и percent-encoding в `SOCKS5_PROXY_URL`.
