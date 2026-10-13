# CLI-to-chat-chatgpt

Чат-сервис на Java 21 и Spring Boot, который предоставляет REST API поверх
Codex CLI. Принимает сообщения через `POST /api/chat` и возвращает текст ответа
модели в JSON.

## Особенности

- Вход через аккаунт ChatGPT без API-ключа.
- Сохранение контекста диалога по `conversationId`; без него каждый запрос независим.
- Диалоги и авторизация сохраняются в подключённом каталоге данных Codex.
- Режим обычного чата: инструменты, выполнение команд и работа с проектами отключены.
- Один активный запрос на весь сервис, таймаут — 10 минут, без автоматических повторов.
- Запуск в Docker Compose; подключение к внешнему GOST-прокси для выхода через SOCKS5.
- API на `localhost:8080` и в Docker-сетях; рассчитан на доверенных клиентов, HTTP-авторизации нет.

## Настройка

Создайте `.env` из примера и заполните две обязательные переменные:

```powershell
Copy-Item .env.example .env
```

```dotenv
PROXY_NETWORK_NAME='proxy-project_proxy'
CODEX_DATA_DIR='D:/Documents/codex-chat-data'
```

- `PROXY_NETWORK_NAME` — имя существующей Docker-сети внешнего GOST. Прокси должен
  быть запущен в этой сети и доступен как `gost:8080` (HTTP); SOCKS5 настраивается в GOST.
- `CODEX_DATA_DIR` — путь к каталогу авторизации и диалогов на хосте. Создайте его
  заранее и предоставьте пользователю контейнера (UID 10001) права чтения и записи.

## Авторизация и запуск

```powershell
docker compose build codex
docker compose run --rm codex login --device-auth
docker compose run --rm codex login status
docker compose up -d
```

Для входа откройте ссылку и введите код, показанные CLI, в браузере с аккаунтом
ChatGPT. Авторизация сохраняется в `CODEX_DATA_DIR` и используется после перезапуска.
