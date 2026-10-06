# Минимальный чат-сервис через Codex CLI

Compose запускает `codex` — Spring Boot REST API и дочерний `codex app-server`.
GOST запускается отдельно и переводит HTTP proxy-запросы Codex в личный SOCKS5.

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
PROXY_NETWORK_NAME='proxy-project_proxy'
CODEX_DATA_DIR='D:/Documents/codex-chat-data'
```

Заранее создайте папку `CODEX_DATA_DIR` и предоставьте пользователю контейнера
UID 10001 права чтения/записи. Compose не создаёт отсутствующую папку автоматически.

`PROXY_NETWORK_NAME` — точное имя существующей Docker-сети внешнего GOST;
`proxy-project_proxy` — пример, замените его своим значением. Ключ `proxy` в
его Compose не обязательно совпадает с фактическим именем сети.
GOST должен быть заранее запущен в этой сети с именем сервиса `gost` и HTTP
listener на порту `8080`. Codex обращается к `http://gost:8080`; опубликованный
на хосте порт `3128` для этого подключения не используется.
SOCKS5 URL и credentials настраиваются только в отдельном проекте GOST.
Не добавляйте `.env` в Git и не публикуйте auth или credentials.

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
API `http://codex:8080` доступен также другим участникам внешней proxy-сети.

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

## Диалоги и сохранение контекста

Для продолжения переписки передавайте один и тот же `conversationId`:

```json
{"conversationId":"vk:my-bot:peer-123:session-1","message":"Запомни: мой любимый цвет — зелёный."}
```

```json
{"conversationId":"vk:my-bot:peer-123:session-1","message":"Какой мой любимый цвет?"}
```

Первый запрос создаёт сохранённый thread Codex, следующие продолжают его.
Ответ по-прежнему содержит только `requestId` и `answer`. Без `conversationId`
или с `null` каждый запрос создаёт отдельный одноразовый thread без контекста.
Указанный ID должен быть непустой строкой не длиннее 256 символов UTF-16;
иначе API возвращает `400 CONVERSATION_ID_INVALID`.

Разделяйте ID по приложению, чату и сессии. Для новой переписки используйте новый
ID, например `vk:my-bot:peer-123:session-2`; отдельного reset endpoint нет.
Одновременно может храниться много диалогов, но общий лимит остаётся прежним:
один активный запрос на весь сервис. Остальные получают `409 CHAT_BUSY`.

Сервис хранит только соответствие `conversationId → threadId` в
`CODEX_HOME/chat-service/conversations.json`. Историю и сжатие длинного контекста
ведёт Codex; сжатие не гарантирует точное воспроизведение всех старых деталей.
В Docker весь `CODEX_HOME` находится в подключённом `CODEX_DATA_DIR`.
При запуске вне Docker без `CODEX_HOME` используется каталог `~/.codex`.
Для восстановления после перезапуска нужны и карта ID, и данные Codex.
Для резервной копии остановите сервис и сохраните весь `CODEX_DATA_DIR`;
не ограничивайтесь файлом `conversations.json`. Восстановленный каталог должен
быть доступен UID 10001. Резервная копия содержит также данные авторизации.

Хранилище рассчитано на один экземпляр сервиса. Несколько реплик не должны
одновременно использовать один каталог. Запись карты атомарная; если файловая
система не поддерживает такую замену или данные повреждены, запрос завершается
ошибкой без перезаписи карты пустыми данными. Сохранение при отключении питания
не гарантируется: отдельный `fsync` не выполняется.

Переписка с ID сохраняется на диске. Автоматического удаления, retention и API
чтения истории нет. `conversationId` не является средством авторизации:
HTTP auth отсутствует, клиент, знающий ID, может продолжить этот диалог.
Подключайте только доверенных потребителей, включая участников proxy-сети.

При потере карты после перезапуска прежние ID могут создать новые threads;
восстанавливайте весь каталог из согласованной резервной копии. При повреждении
карты или недоступном сохранённом thread API возвращает `503 CODEX_UNAVAILABLE`,
не создавая новый thread вместо известного. Не очищайте карту для исправления ошибки.
У пустого thread до первого turn ещё может не быть сохранённого rollout;
ранний сбой после записи связи способен оставить ID, который нельзя продолжить.
Для отдельной новой переписки используйте новый ID, сохранив прежние данные.

Timeout или обрыв HTTP-соединения не означает, что сообщение не попало в историю.
Автоматического retry нет; повтор запроса может добавить ещё один turn.
Дедупликацию событий ВК и других платформ выполняет приложение-потребитель.

Проверка сохранённого диалога из PowerShell:

```powershell
$chatUri = 'http://localhost:8080/api/chat'
Invoke-RestMethod -Method Post -Uri $chatUri -ContentType 'application/json; charset=utf-8' `
  -Body '{"conversationId":"smoke:a:session-1","message":"Запомни кодовое слово: ЛИПА."}'
Invoke-RestMethod -Method Post -Uri $chatUri -ContentType 'application/json; charset=utf-8' `
  -Body '{"conversationId":"smoke:b:session-1","message":"Запомни кодовое слово: КЕДР."}'
```

После перезапуска приложения и пересоздания контейнера с прежним каталогом
отправьте `"Какое кодовое слово я назвал?"` каждому ID: ожидаются соответственно
«ЛИПА» и «КЕДР». Выполняйте проверку в согласованном тестовом окружении.

```powershell
Invoke-RestMethod -Method Post -Uri $chatUri -ContentType 'application/json; charset=utf-8' `
  -Body '{"conversationId":"smoke:a:session-1","message":"Какое кодовое слово я назвал?"}'
Invoke-RestMethod -Method Post -Uri $chatUri -ContentType 'application/json; charset=utf-8' `
  -Body '{"conversationId":"smoke:b:session-1","message":"Какое кодовое слово я назвал?"}'
```

## Автоматический деплой через GitHub Actions

Workflow `Deploy` запускается при каждом push в `main`, независимо от `Java CI`.
Он скачивает commit этого push, собирает `codex`, запускает его и проверяет API.
Если сборка завершается ошибкой, шаг запуска не выполняется. Dockerfile собирает
приложение с пропуском тестов; результат Java CI не блокирует deployment.
Build и запуск выполняются на Docker daemon вашей машины. Раннер и временный
Ubuntu-контейнер `ghcr.io/catthehacker/ubuntu:act-24.04` с готовыми
Docker CLI/Compose работают через Docker socket хоста.
Инструменты деплоя не попадают в итоговый образ приложения; Dockerfile прежний.

В GitHub → Settings → Secrets and variables → Actions настройте:

| Тип | Имя | Значение |
| --- | --- | --- |
| Variable | `PROXY_NETWORK_NAME` | Точное имя существующей сети внешнего GOST |
| Variable | `CODEX_DATA_DIR` | Абсолютный путь на Linux-хосте, например `/srv/cli-to-chat/codex-data` |
| Variable | `COMPOSE_PROJECT_NAME` | Необязательно; по умолчанию `cli-to-chat-chatgpt` |

Раннер должен иметь labels `self-hosted`, `ci`, доступ к
`/var/run/docker.sock` хоста и поддерживать обычные job containers.
Его workspace должен быть доступен этим контейнерам через mounts раннера.
Job получает socket; одноразовый `curlimages/curl:8.22.0` с `--network host`
проверяет HTTP через loopback хоста. Сам job остаётся в сети GitHub runner.
Для получения job image и сборки нужен выход к GitHub/GHCR, Maven Central,
npm и Docker image registry.

Папку данных заранее создайте **на хосте**, вне workspace раннера, с доступом
UID 10001. Сохранённый ChatGPT login должен находиться в этой папке; для первого
входа используйте ручную процедуру выше с тем же host path. Workflow проверяет
наличие папки, не читает auth-файлы и не выполняет отдельные login/refresh-команды.
Для существующей установки `COMPOSE_PROJECT_NAME` должен совпадать с прежним
именем проекта: это исключает запуск второго stack с тем же портом и сетью.

CD не использует `.env`: настройки передаются через GitHub Variables.
Workflow проверяет наличие внешней сети и папки данных, собирает и обновляет
только `codex`. Внешний GOST и его SOCKS5 secret не входят в этот deployment.
Деплои выполняются последовательно, без отмены текущего. Образ сначала собирается,
затем `up -d --no-build` пересоздаёт изменённые контейнеры; возможен короткий перерыв.
Предварительного `down`, удаления auth и автоматического rollback нет.

После запуска CD ждёт HTTP `400 MESSAGE_REQUIRED` на пустой POST `/api/chat`.
Это проверяет доступность Java API, но не ChatGPT login, SOCKS5 и ответ модели.
После первого push проверьте Actions → Deploy и выполните обычный запрос чата.

При ошибке установки/build работающий сервис не заменяется. При ошибке up/smoke
возможен частичный deployment: проверьте статус контейнеров на хосте и HTTP ответ.
SHA и результат smoke видны в Actions summary. Не отправляйте runtime logs в
Actions автоматически: CLI/proxy могут содержать чувствительные данные.
Для отката верните предыдущий код новым commit в `main`: push запустит CD.
Повторный запуск старого `Deploy` также может задеплоить старый commit:
проверки актуальности `main` больше нет.
Чтобы отключить автоматический deployment, отключите workflow `Deploy` в Actions.
`CODEX_DATA_DIR` при отключении/откате сохраняется.

## Остановка и откат

```powershell
docker compose down
```

Команда удаляет контейнеры и Compose-сети, но сохраняет `.env` и содержимое
`CODEX_DATA_DIR`. Внешняя proxy-сеть и GOST сохраняются. Для отката приложения
верните предыдущий код с подключением к внешней сети и пересоберите `codex`;
папку авторизации удалять не нужно. Откат приложения не откатывает внешний proxy.

Если здесь раньше запускался локальный `gost`, после удаления из Compose он
может остаться orphan-контейнером. Проверьте старый stack и остановите только
прежний локальный GOST вручную. CD не применяет `--remove-orphans`; внешний
GOST должен продолжать работать. Старый Compose с собственным GOST нельзя
возвращать без проверки портов, сети и владения proxy.

## Диагностика

```powershell
docker compose ps
docker compose logs --tail 100 codex
```

Логи приложения содержат request/thread/turn ID, длительность и нормализованный
код ошибки. Текст запроса, ответ, auth tokens и SOCKS5 credentials приложение не
логирует. Если авторизация или запрос не работают, проверьте ChatGPT login,
имя внешней сети и доступность `gost:8080`. SOCKS5 и логи GOST проверяйте
в отдельном проекте proxy. Успешный HTTP smoke CD не доказывает работу proxy.

## Проверки Java CI и SonarQube Cloud

На PR в `main` и push в `main` на GitHub-hosted runner `ubuntu-24.04`
в контейнере Maven 3.9.16 / Java 21 выполняются Maven build/tests, затем анализ
SonarQube Cloud (`https://sonarcloud.io`) с ожиданием Quality Gate до 300 секунд.
Ошибка сборки, анализа или Quality Gate завершает CI ошибкой. JaCoCo XML
передаётся Sonar; условия качества и coverage задаются в Quality Gate проекта.
Падения тестов не блокируют CI: Maven продолжает `verify`, формирует JaCoCo XML
и запускается Sonar. Ошибки компиляции/сборки остаются блокирующими. Результаты
тестов доступны в `quality-reports`; выполненные строки учитываются в coverage
даже у упавшего теста, поэтому Quality Gate может пройти при падении тестов.
Отдельных Synapse, diff-cover, CVE и license проверок в workflow больше нет.

Импортируйте репозиторий в SonarQube Cloud и выберите CI-based analysis;
отключите Automatic Analysis, если он включён. В GitHub → Settings →
Secrets and variables → Actions добавьте:

| Тип | Имя | Значение |
| --- | --- | --- |
| Secret | `SONAR_TOKEN` | Токен Sonar с правом анализа проекта |
| Variable | `SONAR_ORGANIZATION` | Organization key из Sonar |
| Variable | `SONAR_PROJECT_KEY` | Project key из Sonar |

Раннеру нужен HTTPS-доступ к SonarQube Cloud и Maven Central. План Sonar должен
поддерживать нужный PR-анализ. Для fork PR GitHub не передаёт token: тесты идут,
анализ завершается явной ошибкой без обхода gate. JaCoCo/test reports сохраняются
в `quality-reports`. После настройки проверьте push/PR run и coverage в Sonar.
CI не блокирует существующий Deploy; required checks для merge задаются в GitHub.

Явные лимиты CPU/памяти контейнера и JVM в workflow не заданы;
JVM использует автоматические настройки в пределах ресурсов GitHub runner.
Кеши Maven/Sonar хранятся в `/tmp` временного job container без host bind.
После загрузки отчётов workflow удаляет `target`, включая при ошибке проверки.

Sonar, скачивание JRE/движка, Maven Central, checkout и загрузка отчётов
используют прямые подключения с GitHub runner. Прокси и доступ к локальной
Docker-сети для Java CI не нужны. `PROXY_NETWORK_NAME` остаётся необходимой
для self-hosted Deploy и приложения.
