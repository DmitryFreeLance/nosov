# SpeedCallerBot (Java + Telegram + SQLite)

Telegram-бот для быстрого обзвона телефонных баз.

## Что реализовано
- Java 17 + Maven (`pom.xml`)
- Telegram inline-кнопки (все кнопки в одну колонку, по одной в строке, с эмодзи)
- SQLite-хранилище пользователей, состояний и номеров
- Загрузка номеров из `.xlsx`, `.txt`, `.csv`
- Поддержка международных номеров (10-15 цифр)
- Авто-нормализация номеров в формат `+<digits>`
- Дедупликация при импорте и кнопка `REMOVE DUPLICATES`
- `CALL / SKIP / BACK / MAIN MENU`
- Интеграция AdsGram с показом рекламы каждые N карточек (по умолчанию 40)
- Встроенный приоритетный waterfall для AdsGram-блоков (выбор оффера по заданным приоритетам вертикалей)
- Очистка базы пользователя (`CLEAR ALL`)
- Чистый чат: карточка и меню редактируются в одном сообщении, пользовательские служебные сообщения удаляются
- Админ-панель:
  - список всех пользователей
  - добавление админов по `tg_id`
  - объединённая выгрузка уникальных номеров из всех пользовательских баз

## Переменные окружения
Обязательные:
- `BOT_TOKEN` - токен бота от BotFather
- `BOT_USERNAME` - username бота (без `@`)

Опциональные:
- `DB_PATH` - путь к SQLite (по умолчанию `data/speedcallerbot.db`)
- `OWNER_TG_ID` - Telegram ID владельца (будет автоматически админом)
- `OWNER_TG_IDS` - список Telegram ID владельцев через запятую
- `ADSGRAM_ENABLED` - включить AdsGram (`true/false`, авто-включение при наличии токена и block id)
- `ADSGRAM_TOKEN` - токен AdsGram
- `ADSGRAM_BLOCK_ID` - один block id AdsGram
- `ADSGRAM_BLOCK_IDS` - список block id через запятую/пробел
- `ADSGRAM_LANGUAGE` - язык рекламы (по умолчанию `en`)
- `ADS_EVERY_CARDS` - показывать рекламу каждые N карточек (по умолчанию `40`)
- `ADSGRAM_CANDIDATES_PER_BLOCK` - сколько кандидатов запрашивать из каждого блока для waterfall (по умолчанию `2`)

Важно:
- Для `ADSGRAM_BLOCK_ID(S)` используйте numeric part block id (если в кабинете `bot-12345`, в `env` указывайте `12345`).

## Локальный запуск
```bash
cd /Users/dmitry/Desktop/nosov
mvn -DskipTests package

export BOT_TOKEN="YOUR_BOT_TOKEN"
export BOT_USERNAME="YOUR_BOT_USERNAME"
export OWNER_TG_ID="123456789"
export ADSGRAM_TOKEN="YOUR_ADSGRAM_TOKEN"
export ADSGRAM_BLOCK_ID="12345"

java -jar target/speedcallerbot-1.0.0.jar
```

## Docker запуск
```bash
cd /Users/dmitry/Desktop/nosov
docker build -t speedcallerbot .

docker run -d \
  --name speedcallerbot \
  -e BOT_TOKEN="YOUR_BOT_TOKEN" \
  -e BOT_USERNAME="YOUR_BOT_USERNAME" \
  -e OWNER_TG_ID="123456789" \
  -e ADSGRAM_TOKEN="YOUR_ADSGRAM_TOKEN" \
  -e ADSGRAM_BLOCK_ID="12345" \
  -e ADS_EVERY_CARDS="40" \
  -e DB_PATH="/app/data/speedcallerbot.db" \
  -v $(pwd)/data:/app/data \
  speedcallerbot
```

## Кнопки и экраны
Главное меню:
- 🚀 START
- 📥 LOAD NUMBERS
- 🛠 ADMIN PANEL (только для админов)

Экран загрузки:
- 📤 LOAD FILE
- 📝 PASTE TEXT LIST
- ♻️ REMOVE DUPLICATES
- 🧹 CLEAR ALL
- 🏠 MAIN MENU

Экран обзвона:
- 📇 SEND CONTACT
- ⏭ SKIP
- ⏮ BACK
- 🏠 MAIN MENU

Рекламная карточка AdsGram:
- 🔗 Open Offer (если пришла `click_url`)
- 🎁 Claim Reward (если пришла `reward_url`)
- 🖼 View Creative (если пришла `image_url`)
- ⏭ SKIP / ⏮ BACK

Админ-панель:
- 👥 SHOW ALL USERS
- ➕ ADD ADMIN BY TG ID
- 🗃 EXPORT ALL NUMBERS
- 🏠 MAIN MENU
