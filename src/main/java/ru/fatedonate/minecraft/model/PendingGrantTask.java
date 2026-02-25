package ru.fatedonate.minecraft.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.bukkit.ChatColor;
import ru.fatedonate.minecraft.config.AppConfig;

public final class PendingGrantTask {
    public String id;
    public String purchaseId;
    public String playerUuid;
    public String playerId;
    public String playerName;
    public String itemId;
    public String itemTitle;
    public String itemCategory;
    public BigDecimal price;
    public BigDecimal balanceAfter;
    public String currency;
    public List<String> commands;
    public int attempts;
    public long nextAttemptAtMillis;
    public long createdAtMillis;

    public static PendingGrantTask from(
            String purchaseId,
            PlayerIdentity identity,
            AppConfig.ItemConfig item,
            BigDecimal balanceAfter,
            String currency,
            List<String> commands,
            long nextAttemptAtMillis
    ) {
        final PendingGrantTask task = new PendingGrantTask();
        task.id = UUID.randomUUID().toString();
        task.purchaseId = purchaseId;
        task.playerUuid = identity.uuid().toString();
        task.playerId = identity.playerId();
        task.playerName = identity.playerName();
        task.itemId = item.id();
        task.itemTitle = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', item.title()));
        task.itemCategory = item.categoryId();
        task.price = item.price();
        task.balanceAfter = balanceAfter;
        task.currency = currency;
        task.commands = List.copyOf(commands);
        task.attempts = 0;
        task.nextAttemptAtMillis = nextAttemptAtMillis;
        task.createdAtMillis = System.currentTimeMillis();
        return task;
    }

    public static boolean isValid(PendingGrantTask task) {
        return task != null
                && task.id != null
                && !task.id.isBlank()
                && task.purchaseId != null
                && !task.purchaseId.isBlank()
                && task.playerUuid != null
                && !task.playerUuid.isBlank()
                && task.playerId != null
                && !task.playerId.isBlank()
                && task.itemId != null
                && !task.itemId.isBlank()
                && task.itemTitle != null
                && !task.itemTitle.isBlank()
                && task.currency != null
                && !task.currency.isBlank()
                && task.balanceAfter != null
                && task.commands != null
                && !task.commands.isEmpty();
    }

    public UUID playerUuid() {
        return UUID.fromString(playerUuid);
    }
}
