package ru.fatedonate.minecraft.model;

import java.util.Map;
import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

public record OpenMenuContext(Inventory inventory, Map<Integer, Consumer<Player>> actions) {
}
