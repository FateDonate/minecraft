# FateDonate Minecraft

Официальный клиент FateDonate для Minecraft (Paper, Java).

## Что умеет плагин

- Меню игрока по команде `/fd`.
- Проверка баланса.
- Ссылка на пополнение.
- Магазин с категориями и товарами.
- Выдача товара через консольные команды после успешной покупки.

## Что нужно

- Java 21
- Paper 1.21.x

## Как собрать проект

Windows (без установленного Maven):

```powershell
.\mvnw.cmd -DskipTests package
```

Если Maven уже установлен:

```bash
mvn -DskipTests package
```

Готовый jar будет в `target/`.

## Как установить

1. Скопируйте jar в папку `plugins/`.
2. Запустите сервер, чтобы создался конфиг.
3. Откройте `plugins/FateDonate/config.yml`.
4. Заполните:
   - `settings.server-id`
   - `settings.private-key`
5. Перезапустите сервер.

## Файлы плагина

- `plugins/FateDonate.jar` - плагин.
- `plugins/FateDonate/config.yml` - основной конфиг: настройки, переводы, товары и команды.

## Команды в игре

- `/fd` - главное меню
- `/fd balance` - баланс
- `/fd topup 300` - ссылка на пополнение
- `/fd shop` - категории магазина
- `/fd buy vip_30d` - покупка товара по id
- `/fd help` - список команд

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
