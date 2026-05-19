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

## Локальный запуск
```bash
cd /Users/dmitry/Desktop/nosov
mvn -DskipTests package

export BOT_TOKEN="YOUR_BOT_TOKEN"
export BOT_USERNAME="YOUR_BOT_USERNAME"
export OWNER_TG_ID="123456789"

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
- 📞 CALL (URL `tel:+...`)
- ⏭ SKIP
- ⏮ BACK
- 🏠 MAIN MENU

Админ-панель:
- 👥 SHOW ALL USERS
- ➕ ADD ADMIN BY TG ID
- 🗃 EXPORT ALL NUMBERS
- 🏠 MAIN MENU
