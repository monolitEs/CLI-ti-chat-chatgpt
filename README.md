# Codex CLI через SOCKS5-прокси

Compose запускает два сервиса:

- `gost` — переводит HTTP proxy-запросы Codex в личный SOCKS5;
- `codex` — Codex CLI в режиме интерактивного чата.

Проекты и документы хоста в контейнер Codex не подключаются. Авторизация
ChatGPT и история сохраняются в папке `CODEX_DATA_DIR`.

## Настройка

Если `.env` ещё не существует, создайте его из примера:

```powershell
Copy-Item .env.example .env
```

Заполните в `.env` две переменные:

```dotenv
SOCKS5_PROXY_URL='socks5://username:password@proxy.example.com:1080'
CODEX_DATA_DIR='D:/Documents/codex-chat-data'
```

Спецсимволы в имени пользователя и пароле должны быть закодированы как URL
percent-escapes. Не добавляйте `.env` в Git и не публикуйте вывод
`docker compose config` или `docker inspect`: в нём могут оказаться credentials.

## Первый запуск

Соберите образ Codex:

```powershell
docker compose build codex
```

Выполните вход через ChatGPT по device-коду:

```powershell
docker compose run --rm codex login --device-auth
```

После успешного входа откройте чат:

```powershell
docker compose run --rm codex
```

Команда `docker compose run` сама запускает зависимый сервис `gost`.

## Последующие запуски

Для нового сеанса чата достаточно:

```powershell
docker compose run --rm codex
```

`docker compose up -d` запускает Codex в фоне и поэтому не подходит для работы
с интерактивным терминальным интерфейсом.

## Остановка

После завершения чата остановите GOST и удалите созданные Compose-сети:

```powershell
docker compose down
```

Эта команда сохраняет `.env` и содержимое `CODEX_DATA_DIR`.

## Диагностика

Состояние сервисов:

```powershell
docker compose ps
```

Последние сообщения GOST:

```powershell
docker compose logs --tail 50 gost
```

Если авторизация или чат не подключаются, проверьте доступность SOCKS5,
credentials и percent-encoding спецсимволов в `SOCKS5_PROXY_URL`.
