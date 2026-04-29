# 🍎 FateDonate Minecraft

Официальный клиент FateDonate для Minecraft.

## Возможности плагина

- GUI меню по команде `/fd`: товары с категориями, кнопки.
- Проверка баланса игрока.
- Получение ссылки на пополнение.
- Выдача услуги после успешной транзакции.

## Требования

- Java 21
- Paper 1.21.x

Для сборки плагина запустите `Build.bat`.

## Как установить

1. Скопируйте jar в папку `plugins/`.
2. Запустите сервер для создания конфига.
3. Настройте `plugins/FateDonate/config.yml`:
    - `settings.server-id`
    - `settings.private-key`
4. Перезапустите сервер.

## Файлы

- `plugins/FateDonate.jar` - плагин.
- `plugins/FateDonate/config.yml` - основные настройки и кастомизация товаров.

## Команды в игре

- `/fd` - главное меню
- `/fd balance` - проверка баланса
- `/fd topup 300` - ссылка на пополнение (например, 300 руб.)
- `/fd shop` - открыть магазин с категориями товаров
- `/fd buy vip_30d` - купить товар по id
- `/fd help` - список доступных команд

## Плейсхолдеры

Для `grant-commands` и `settings.purchase-announcement-template`:

- `{player_name}`
- `{player_uuid}`
- `{player_uuid_nodash}`
- `{item_id}`
- `{item_name}`
- `{item_category}`
- `{price}`
- `{currency}`
- `{duration_days}`
- `{balance}`
- `{server_id}`
