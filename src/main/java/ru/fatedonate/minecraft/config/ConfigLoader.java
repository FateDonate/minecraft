package ru.fatedonate.minecraft.config;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

public final class ConfigLoader {
    private static final List<String> REQUIRED_MESSAGE_KEYS = List.of(
            "prefix",
            "service-not-initialized",
            "unknown-command",
            "player-only-command",
            "balance-request",
            "balance-response-template",
            "balance-request-in-progress",
            "topup-link-creating",
            "topup-link-template",
            "topup-usage",
            "topup-invalid-amount",
            "buy-usage",
            "item-not-found",
            "category-not-found",
            "shop-empty",
            "categories-empty",
            "purchase-not-available",
            "purchase-debit-failed",
            "purchase-grant-failed",
            "internal-error",
            "get-balance-failed",
            "create-topup-failed",
            "menu-main-title",
            "menu-topup-title",
            "menu-categories-title",
            "menu-items-title-template",
            "menu-confirm-title",
            "menu-my-balance",
            "menu-topup",
            "menu-shop",
            "menu-help",
            "menu-back",
            "menu-close",
            "menu-confirm-purchase",
            "menu-back-to-shop",
            "topup-quick-template",
            "selected-item-template",
            "selected-item-id-template",
            "selected-item-description-template",
            "selected-item-price-template",
            "purchase-starting-template",
            "purchase-success-template",
            "help-header",
            "help-main-menu",
            "help-balance",
            "help-topup",
            "help-shop",
            "help-buy",
            "config-missing-server-id",
            "config-missing-private-key",
            "config-missing-topup-amounts",
            "config-invalid-categories",
            "config-invalid-items"
    );

    private ConfigLoader() {
    }

    public static LoadResult load(FileConfiguration configuration) {
        final var errors = new ArrayList<String>();

        final var settings = readSettings(configuration, errors);
        final var messages = readMessages(configuration, errors);
        final var categories = readCategories(configuration, errors);
        final var items = readItems(configuration, categories, errors);

        if (!errors.isEmpty()) {
            return new LoadResult(null, List.copyOf(errors));
        }

        return new LoadResult(
                new AppConfig(settings, messages, categories, items),
                List.of()
        );
    }

    private static AppConfig.Settings readSettings(
            FileConfiguration configuration,
            List<String> errors
    ) {
        final String apiBaseUrl = readRequiredString(configuration, "settings.api-base-url", errors);
        final String serverId = readRequiredString(configuration, "settings.server-id", errors);
        final String privateKey = readRequiredString(configuration, "settings.private-key", errors);
        final String currency = readRequiredString(configuration, "settings.currency", errors);

        final int requestTimeout = clampInt(
                configuration.getInt("settings.request-timeout-seconds", 8),
                3,
                60
        );
        final int balanceCache = clampInt(
                configuration.getInt("settings.balance-cache-seconds", 5),
                0,
                300
        );

        final var topupQuickAmounts = readTopupQuickAmounts(configuration, errors);
        final boolean reopenMainMenuAfterAction = configuration.getBoolean(
                "settings.reopen-main-menu-after-action",
                true
        );
        final boolean requirePurchaseConfirmation = configuration.getBoolean(
                "settings.require-purchase-confirmation",
                true
        );
        final boolean announcePurchasesToServer = configuration.getBoolean(
                "settings.announce-purchases-to-server",
                true
        );
        final String purchaseAnnouncementTemplate = readRequiredString(
                configuration,
                "settings.purchase-announcement-template",
                errors
        );

        return new AppConfig.Settings(
                apiBaseUrl.trim(),
                serverId.trim(),
                privateKey.trim(),
                currency.trim().toUpperCase(Locale.ROOT),
                requestTimeout,
                balanceCache,
                topupQuickAmounts,
                reopenMainMenuAfterAction,
                requirePurchaseConfirmation,
                announcePurchasesToServer,
                purchaseAnnouncementTemplate
        );
    }

    private static List<BigDecimal> readTopupQuickAmounts(
            FileConfiguration configuration,
            List<String> errors
    ) {
        final Object value = configuration.get("settings.topup-quick-amounts");
        if (!(value instanceof List<?> rawList)) {
            errors.add("settings.topup-quick-amounts: ожидается список положительных чисел.");
            return List.of();
        }

        final var result = new ArrayList<BigDecimal>();
        for (int index = 0; index < rawList.size(); index += 1) {
            final Object raw = rawList.get(index);
            if (raw == null) {
                errors.add("settings.topup-quick-amounts[" + index + "]: значение отсутствует.");
                continue;
            }

            final BigDecimal amount;
            try {
                amount = new BigDecimal(raw.toString());
            } catch (NumberFormatException exception) {
                errors.add("settings.topup-quick-amounts[" + index + "]: значение не является числом.");
                continue;
            }

            if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                errors.add("settings.topup-quick-amounts[" + index + "]: сумма должна быть больше 0.");
                continue;
            }

            result.add(amount.stripTrailingZeros());
        }

        final var unique = result.stream()
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();

        if (unique.isEmpty()) {
            errors.add("settings.topup-quick-amounts: добавьте минимум одну сумму.");
        }

        return unique;
    }

    private static Map<String, String> readMessages(
            FileConfiguration configuration,
            List<String> errors
    ) {
        final var section = configuration.getConfigurationSection("messages");
        final var messages = new LinkedHashMap<String, String>();
        if (section == null) {
            errors.add("messages: секция отсутствует.");
            return messages;
        }

        for (var key : REQUIRED_MESSAGE_KEYS) {
            final String value = readRequiredString(section, key, "messages." + key, errors);
            messages.put(key, value);
        }

        return messages;
    }

    private static Map<String, AppConfig.CategoryConfig> readCategories(
            FileConfiguration configuration,
            List<String> errors
    ) {
        final var section = configuration.getConfigurationSection("categories");
        final var categories = new LinkedHashMap<String, AppConfig.CategoryConfig>();
        if (section == null) {
            errors.add("categories: секция отсутствует.");
            return categories;
        }

        final var usedSlots = new HashSet<Integer>();

        for (var categoryId : section.getKeys(false)) {
            final var categorySection = section.getConfigurationSection(categoryId);
            if (categorySection == null) {
                errors.add("categories." + categoryId + ": ожидается объект.");
                continue;
            }

            final String path = "categories." + categoryId;
            final String title = readRequiredString(categorySection, "title", path + ".title", errors);
            final List<String> description = readRequiredStringList(
                    categorySection,
                    "description",
                    path + ".description",
                    errors
            );
            final Material material = readRequiredMaterial(
                    categorySection,
                    "material",
                    path + ".material",
                    errors
            );
            final int slot = readRequiredIntInRange(
                    categorySection,
                    "slot",
                    0,
                    53,
                    path + ".slot",
                    errors
            );

            if (!usedSlots.add(slot)) {
                errors.add(path + ".slot: слот уже занят другой категорией.");
            }

            categories.put(
                    categoryId,
                    new AppConfig.CategoryConfig(
                            categoryId,
                            title,
                            description,
                            material,
                            slot
                    )
            );
        }

        if (categories.isEmpty()) {
            errors.add("categories: добавьте минимум одну категорию.");
        }

        return categories;
    }

    private static Map<String, AppConfig.ItemConfig> readItems(
            FileConfiguration configuration,
            Map<String, AppConfig.CategoryConfig> categories,
            List<String> errors
    ) {
        final var section = configuration.getConfigurationSection("items");
        final var items = new LinkedHashMap<String, AppConfig.ItemConfig>();
        if (section == null) {
            errors.add("items: секция отсутствует.");
            return items;
        }

        final var categoryIds = categories.keySet().stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        final var usedSlotsByCategory = new HashMap<String, Set<Integer>>();

        for (var itemId : section.getKeys(false)) {
            final var itemSection = section.getConfigurationSection(itemId);
            if (itemSection == null) {
                errors.add("items." + itemId + ": ожидается объект.");
                continue;
            }

            final String path = "items." + itemId;
            final String categoryId = readRequiredString(
                    itemSection,
                    "category",
                    path + ".category",
                    errors
            );
            final String title = readRequiredString(itemSection, "title", path + ".title", errors);
            final List<String> description = readRequiredStringList(
                    itemSection,
                    "description",
                    path + ".description",
                    errors
            );
            final Material material = readRequiredMaterial(
                    itemSection,
                    "material",
                    path + ".material",
                    errors
            );
            final int slot = readRequiredIntInRange(
                    itemSection,
                    "slot",
                    0,
                    44,
                    path + ".slot",
                    errors
            );
            final BigDecimal price = readRequiredBigDecimal(
                    itemSection,
                    "price",
                    path + ".price",
                    errors
            );
            final int durationDays = readRequiredIntInRange(
                    itemSection,
                    "duration-days",
                    0,
                    3650,
                    path + ".duration-days",
                    errors
            );
            final boolean enabled = readRequiredBoolean(
                    itemSection,
                    "enabled",
                    path + ".enabled",
                    errors
            );
            final List<String> grantCommands = readRequiredStringList(
                    itemSection,
                    "grant-commands",
                    path + ".grant-commands",
                    errors
            );

            if (price.compareTo(BigDecimal.ZERO) <= 0) {
                errors.add(path + ".price: значение должно быть больше 0.");
            }

            final String normalizedCategoryId = categoryId.toLowerCase(Locale.ROOT);
            if (!categoryIds.contains(normalizedCategoryId)) {
                errors.add(path + ".category: категория \"" + categoryId + "\" не существует.");
            }

            if (enabled) {
                final var usedSlots = usedSlotsByCategory.computeIfAbsent(
                        normalizedCategoryId,
                        unused -> new HashSet<>()
                );
                if (!usedSlots.add(slot)) {
                    errors.add(path + ".slot: слот уже занят другим активным товаром в этой категории.");
                }
            }

            items.put(
                    itemId,
                    new AppConfig.ItemConfig(
                            itemId,
                            categoryId,
                            title,
                            description,
                            material,
                            slot,
                            price.stripTrailingZeros(),
                            durationDays,
                            enabled,
                            grantCommands
                    )
            );
        }

        if (items.isEmpty()) {
            errors.add("items: добавьте минимум один товар.");
        }

        return items;
    }

    private static String readRequiredString(
            FileConfiguration configuration,
            String path,
            List<String> errors
    ) {
        if (!configuration.isString(path)) {
            errors.add(path + ": значение обязательно и должно быть строкой.");
            return "";
        }

        final String value = configuration.getString(path, "").trim();
        if (value.isEmpty()) {
            errors.add(path + ": значение не должно быть пустым.");
        }
        return value;
    }

    private static String readRequiredString(
            ConfigurationSection section,
            String key,
            String fullPath,
            List<String> errors
    ) {
        if (!section.isString(key)) {
            errors.add(fullPath + ": значение обязательно и должно быть строкой.");
            return "";
        }

        final String value = section.getString(key, "").trim();
        if (value.isEmpty()) {
            errors.add(fullPath + ": значение не должно быть пустым.");
        }
        return value;
    }

    private static List<String> readRequiredStringList(
            ConfigurationSection section,
            String key,
            String fullPath,
            List<String> errors
    ) {
        if (!section.isList(key)) {
            errors.add(fullPath + ": ожидается список строк.");
            return List.of();
        }

        final var list = section.getStringList(key).stream()
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();

        if (list.isEmpty()) {
            errors.add(fullPath + ": список не должен быть пустым.");
        }

        return list;
    }

    private static Material readRequiredMaterial(
            ConfigurationSection section,
            String key,
            String fullPath,
            List<String> errors
    ) {
        final String materialName = readRequiredString(section, key, fullPath, errors);
        if (materialName.isEmpty()) {
            return Material.STONE;
        }

        final Material material = Material.matchMaterial(materialName.toUpperCase(Locale.ROOT));
        if (material == null || !material.isItem()) {
            errors.add(fullPath + ": материал \"" + materialName + "\" не найден или не является предметом.");
            return Material.STONE;
        }

        return material;
    }

    private static int readRequiredIntInRange(
            ConfigurationSection section,
            String key,
            int min,
            int max,
            String fullPath,
            List<String> errors
    ) {
        if (!section.isInt(key)) {
            errors.add(fullPath + ": ожидается целое число.");
            return min;
        }

        final int value = section.getInt(key);
        if (value < min || value > max) {
            errors.add(fullPath + ": значение должно быть в диапазоне от " + min + " до " + max + ".");
        }
        return value;
    }

    private static BigDecimal readRequiredBigDecimal(
            ConfigurationSection section,
            String key,
            String fullPath,
            List<String> errors
    ) {
        if (!section.isSet(key)) {
            errors.add(fullPath + ": значение обязательно.");
            return BigDecimal.ZERO;
        }

        final Object raw = section.get(key);
        if (raw == null) {
            errors.add(fullPath + ": значение обязательно.");
            return BigDecimal.ZERO;
        }

        try {
            return new BigDecimal(raw.toString());
        } catch (NumberFormatException exception) {
            errors.add(fullPath + ": значение должно быть числом.");
            return BigDecimal.ZERO;
        }
    }

    private static boolean readRequiredBoolean(
            ConfigurationSection section,
            String key,
            String fullPath,
            List<String> errors
    ) {
        if (!section.isBoolean(key)) {
            errors.add(fullPath + ": ожидается true или false.");
            return false;
        }

        return section.getBoolean(key);
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public record LoadResult(AppConfig config, List<String> errors) {
    }
}
