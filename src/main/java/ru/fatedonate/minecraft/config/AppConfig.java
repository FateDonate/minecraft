package ru.fatedonate.minecraft.config;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.bukkit.Material;

public final class AppConfig {
    private final Settings settings;
    private final Map<String, String> messages;
    private final Map<String, CategoryConfig> categories;
    private final Map<String, ItemConfig> items;

    public AppConfig(
            Settings settings,
            Map<String, String> messages,
            Map<String, CategoryConfig> categories,
            Map<String, ItemConfig> items
    ) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.messages = Collections.unmodifiableMap(new LinkedHashMap<>(messages));
        this.categories = Collections.unmodifiableMap(new LinkedHashMap<>(categories));
        this.items = Collections.unmodifiableMap(new LinkedHashMap<>(items));
    }

    public Settings settings() {
        return settings;
    }

    public String message(String key) {
        return messages.get(key);
    }

    public Map<String, CategoryConfig> categories() {
        return categories;
    }

    public Map<String, ItemConfig> items() {
        return items;
    }

    public Collection<ItemConfig> enabledItems() {
        return items.values().stream().filter(ItemConfig::enabled).toList();
    }

    public List<ItemConfig> enabledItemsByCategory(String categoryId) {
        return items.values().stream()
                .filter(ItemConfig::enabled)
                .filter(item -> item.categoryId().equalsIgnoreCase(categoryId))
                .sorted(Comparator.comparingInt(ItemConfig::slot))
                .toList();
    }

    public Map<String, CategoryConfig> categoriesWithEnabledItems() {
        final var enabledCategoryIds = enabledItems().stream()
                .map(ItemConfig::categoryId)
                .map(id -> id.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        final var result = new LinkedHashMap<String, CategoryConfig>();
        categories.forEach((id, category) -> {
            if (enabledCategoryIds.contains(id.toLowerCase(Locale.ROOT))) {
                result.put(id, category);
            }
        });
        return Collections.unmodifiableMap(result);
    }

    public ItemConfig findItem(String itemId) {
        for (var item : items.values()) {
            if (item.id().equalsIgnoreCase(itemId)) {
                return item;
            }
        }
        return null;
    }

    public CategoryConfig findCategory(String categoryId) {
        for (var category : categories.values()) {
            if (category.id().equalsIgnoreCase(categoryId)) {
                return category;
            }
        }
        return null;
    }

    public record Settings(
            String apiBaseUrl,
            String serverId,
            String privateKey,
            String currency,
            int requestTimeoutSeconds,
            int balanceCacheSeconds,
            List<BigDecimal> topupQuickAmounts,
            boolean reopenMainMenuAfterAction,
            boolean requirePurchaseConfirmation,
            boolean announcePurchasesToServer,
            String purchaseAnnouncementTemplate
    ) {
        public Settings {
            topupQuickAmounts = List.copyOf(topupQuickAmounts);
        }
    }

    public record CategoryConfig(
            String id,
            String title,
            List<String> description,
            Material material,
            int slot
    ) {
        public CategoryConfig {
            description = List.copyOf(description);
        }
    }

    public record ItemConfig(
            String id,
            String categoryId,
            String title,
            List<String> description,
            Material material,
            int slot,
            BigDecimal price,
            int durationDays,
            boolean enabled,
            List<String> grantCommands
    ) {
        public ItemConfig {
            description = List.copyOf(description);
            grantCommands = List.copyOf(grantCommands);
        }
    }
}
