package ru.fatedonate.minecraft;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import ru.fatedonate.minecraft.api.ApiResult;
import ru.fatedonate.minecraft.api.GameApiClient;
import ru.fatedonate.minecraft.config.AppConfig;
import ru.fatedonate.minecraft.config.ConfigLoader;

public final class FateDonatePlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private static final DecimalFormat AMOUNT_FORMAT = new DecimalFormat("0.##");
    private static final int[] TOPUP_BUTTON_SLOTS = new int[]{
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25
    };

    private final Set<UUID> activeOperations = ConcurrentHashMap.newKeySet();
    private final Map<String, BalanceCacheEntry> balanceCache = new ConcurrentHashMap<>();
    private final Map<UUID, OpenMenuContext> openMenus = new ConcurrentHashMap<>();

    private AppConfig appConfig;
    private GameApiClient apiClient;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        final ConfigLoader.LoadResult loadResult = ConfigLoader.load(getConfig());
        if (!loadResult.errors().isEmpty() || loadResult.config() == null) {
            getLogger().severe("FateDonate config has errors:");
            for (var error : loadResult.errors()) {
                getLogger().severe("- " + error);
            }
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        appConfig = loadResult.config();
        apiClient = new GameApiClient(appConfig.settings());

        final PluginCommand fdCommand = getCommand("fd");
        if (fdCommand == null) {
            getLogger().severe("Command /fd is not registered in plugin.yml.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        fdCommand.setExecutor(this);
        fdCommand.setTabCompleter(this);
        Bukkit.getPluginManager().registerEvents(this, this);

        getLogger().info(
                "FateDonate loaded. Command: /fd, API base: "
                        + appConfig.settings().apiBaseUrl()
                        + ", serverId set: "
                        + !appConfig.settings().serverId().isBlank()
        );
    }

    @Override
    public void onDisable() {
        activeOperations.clear();
        balanceCache.clear();
        openMenus.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            reply(sender, message("player-only-command"));
            return true;
        }

        if (!isReady()) {
            reply(player, message("service-not-initialized"));
            return true;
        }

        if (args.length == 0 || "menu".equalsIgnoreCase(args[0])) {
            openMainMenu(player);
            return true;
        }

        final String action = args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "balance" -> {
                requestBalance(player);
                return true;
            }
            case "topup" -> {
                if (args.length == 1) {
                    openTopupMenu(player);
                    return true;
                }

                final BigDecimal amount = parseAmount(args[1]);
                if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
                    reply(player, message("topup-invalid-amount"));
                    return true;
                }

                requestTopupLink(player, amount);
                return true;
            }
            case "shop", "items" -> {
                if (args.length > 1) {
                    openItemsMenu(player, args[1]);
                    return true;
                }
                openCategoriesMenu(player);
                return true;
            }
            case "buy" -> {
                if (args.length < 2) {
                    reply(player, message("buy-usage"));
                    return true;
                }

                final AppConfig.ItemConfig item = appConfig.findItem(args[1]);
                if (item == null) {
                    reply(player, message("item-not-found"));
                    return true;
                }

                if (!item.enabled()) {
                    reply(player, message("purchase-not-available"));
                    return true;
                }

                if (appConfig.settings().requirePurchaseConfirmation()) {
                    openPurchaseConfirmationMenu(player, item);
                    return true;
                }

                startPurchase(player, item);
                return true;
            }
            case "help" -> {
                showHelp(player);
                return true;
            }
            default -> {
                reply(player, message("unknown-command"));
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!isReady()) {
            return List.of();
        }

        if (args.length == 1) {
            return filterByPrefix(
                    List.of("balance", "topup", "shop", "buy", "help"),
                    args[0]
            );
        }

        if (args.length == 2) {
            final String action = args[0].toLowerCase(Locale.ROOT);
            if ("topup".equals(action)) {
                final List<String> values = appConfig.settings().topupQuickAmounts().stream()
                        .map(FateDonatePlugin::formatAmount)
                        .toList();
                return filterByPrefix(values, args[1]);
            }

            if ("buy".equals(action)) {
                final List<String> values = appConfig.enabledItems().stream()
                        .map(AppConfig.ItemConfig::id)
                        .toList();
                return filterByPrefix(values, args[1]);
            }

            if ("shop".equals(action) || "items".equals(action)) {
                final List<String> values = appConfig.categoriesWithEnabledItems().values().stream()
                        .map(AppConfig.CategoryConfig::id)
                        .toList();
                return filterByPrefix(values, args[1]);
            }
        }

        return List.of();
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        final OpenMenuContext context = openMenus.get(player.getUniqueId());
        if (context == null) {
            return;
        }

        final Inventory topInventory = event.getView().getTopInventory();
        if (!topInventory.equals(context.inventory())) {
            return;
        }

        event.setCancelled(true);
        if (event.getRawSlot() < 0 || event.getRawSlot() >= topInventory.getSize()) {
            return;
        }

        final MenuAction action = context.actions().get(event.getRawSlot());
        if (action == null) {
            return;
        }

        handleMenuAction(player, action);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        final OpenMenuContext context = openMenus.get(player.getUniqueId());
        if (context == null) {
            return;
        }

        if (event.getInventory().equals(context.inventory())) {
            openMenus.remove(player.getUniqueId());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        openMenus.remove(event.getPlayer().getUniqueId());
    }

    private void handleMenuAction(Player player, MenuAction action) {
        switch (action) {
            case SimpleAction simpleAction -> handleSimpleAction(player, simpleAction.type());
            case TopupAction topupAction -> {
                player.closeInventory();
                requestTopupLink(player, topupAction.amount());
            }
            case OpenCategoryAction openCategoryAction -> openItemsMenu(player, openCategoryAction.categoryId());
            case BuyAction buyAction -> {
                final AppConfig.ItemConfig item = appConfig.findItem(buyAction.itemId());
                if (item == null || !item.enabled()) {
                    reply(player, message("item-not-found"));
                    return;
                }

                if (appConfig.settings().requirePurchaseConfirmation()) {
                    openPurchaseConfirmationMenu(player, item);
                    return;
                }

                player.closeInventory();
                startPurchase(player, item);
            }
            case ConfirmAction confirmAction -> {
                final AppConfig.ItemConfig item = appConfig.findItem(confirmAction.itemId());
                if (item == null || !item.enabled()) {
                    reply(player, message("item-not-found"));
                    return;
                }

                player.closeInventory();
                startPurchase(player, item);
            }
        }
    }

    private void handleSimpleAction(Player player, SimpleActionType actionType) {
        switch (actionType) {
            case SHOW_BALANCE -> {
                player.closeInventory();
                requestBalance(player);
            }
            case OPEN_TOPUP -> openTopupMenu(player);
            case OPEN_CATEGORIES -> openCategoriesMenu(player);
            case SHOW_HELP -> {
                player.closeInventory();
                showHelp(player);
                reopenMainMenuIfNeeded(player.getUniqueId());
            }
            case OPEN_MAIN -> openMainMenu(player);
            case CLOSE -> player.closeInventory();
        }
    }

    private void openMainMenu(Player player) {
        final Inventory inventory = Bukkit.createInventory(
                player,
                27,
                colorize(message("menu-main-title"))
        );
        final Map<Integer, MenuAction> actions = new HashMap<>();

        setButton(
                inventory,
                10,
                Material.PAPER,
                message("menu-my-balance"),
                List.of(),
                actions,
                new SimpleAction(SimpleActionType.SHOW_BALANCE)
        );
        setButton(
                inventory,
                12,
                Material.EMERALD,
                message("menu-topup"),
                List.of(),
                actions,
                new SimpleAction(SimpleActionType.OPEN_TOPUP)
        );
        setButton(
                inventory,
                14,
                Material.CHEST,
                message("menu-shop"),
                List.of(),
                actions,
                new SimpleAction(SimpleActionType.OPEN_CATEGORIES)
        );
        setButton(
                inventory,
                16,
                Material.BOOK,
                message("menu-help"),
                List.of(),
                actions,
                new SimpleAction(SimpleActionType.SHOW_HELP)
        );
        setButton(
                inventory,
                22,
                Material.BARRIER,
                message("menu-close"),
                List.of(),
                actions,
                new SimpleAction(SimpleActionType.CLOSE)
        );

        openMenu(player, inventory, actions);
    }

    private void openTopupMenu(Player player) {
        if (appConfig.settings().topupQuickAmounts().isEmpty()) {
            reply(player, message("config-missing-topup-amounts"));
            return;
        }

        final Inventory inventory = Bukkit.createInventory(
                player,
                27,
                colorize(message("menu-topup-title"))
        );
        final Map<Integer, MenuAction> actions = new HashMap<>();

        final List<BigDecimal> amounts = appConfig.settings().topupQuickAmounts();
        final int buttonsCount = Math.min(amounts.size(), TOPUP_BUTTON_SLOTS.length);
        for (int index = 0; index < buttonsCount; index += 1) {
            final BigDecimal amount = amounts.get(index);
            final String title = renderTemplate(
                    message("topup-quick-template"),
                    Map.of(
                            "{amount}", formatAmount(amount),
                            "{currency}", appConfig.settings().currency()
                    )
            );

            setButton(
                    inventory,
                    TOPUP_BUTTON_SLOTS[index],
                    Material.EMERALD,
                    title,
                    List.of(),
                    actions,
                    new TopupAction(amount)
            );
        }

        setButton(
                inventory,
                18,
                Material.ARROW,
                message("menu-back"),
                List.of(),
                actions,
                new SimpleAction(SimpleActionType.OPEN_MAIN)
        );
        setButton(
                inventory,
                26,
                Material.BARRIER,
                message("menu-close"),
                List.of(),
                actions,
                new SimpleAction(SimpleActionType.CLOSE)
        );

        openMenu(player, inventory, actions);
    }

    private void openCategoriesMenu(Player player) {
        final Collection<AppConfig.CategoryConfig> categories = appConfig.categoriesWithEnabledItems()
                .values()
                .stream()
                .sorted(Comparator.comparingInt(AppConfig.CategoryConfig::slot))
                .toList();

        if (categories.isEmpty()) {
            reply(player, message("categories-empty"));
            return;
        }

        final Inventory inventory = Bukkit.createInventory(
                player,
                54,
                colorize(message("menu-categories-title"))
        );
        final Map<Integer, MenuAction> actions = new HashMap<>();
        final Set<Integer> usedSlots = new HashSet<>();

        for (var category : categories) {
            if (!usedSlots.add(category.slot())) {
                continue;
            }

            setButton(
                    inventory,
                    category.slot(),
                    category.material(),
                    category.title(),
                    category.description(),
                    actions,
                    new OpenCategoryAction(category.id())
            );
        }

        setButton(
                inventory,
                49,
                Material.ARROW,
                message("menu-back"),
                List.of(),
                actions,
                new SimpleAction(SimpleActionType.OPEN_MAIN)
        );
        setButton(
                inventory,
                53,
                Material.BARRIER,
                message("menu-close"),
                List.of(),
                actions,
                new SimpleAction(SimpleActionType.CLOSE)
        );

        openMenu(player, inventory, actions);
    }

    private void openItemsMenu(Player player, String categoryId) {
        final AppConfig.CategoryConfig category = appConfig.findCategory(categoryId);
        if (category == null) {
            reply(player, message("category-not-found"));
            return;
        }

        final List<AppConfig.ItemConfig> items = appConfig.enabledItemsByCategory(category.id());
        if (items.isEmpty()) {
            reply(player, message("shop-empty"));
            return;
        }

        final String title = renderTemplate(
                message("menu-items-title-template"),
                Map.of("{category_name}", plain(category.title()))
        );
        final Inventory inventory = Bukkit.createInventory(player, 54, colorize(title));
        final Map<Integer, MenuAction> actions = new HashMap<>();
        final Set<Integer> usedSlots = new HashSet<>();

        for (var item : items) {
            if (!usedSlots.add(item.slot())) {
                continue;
            }

            final List<String> lore = new ArrayList<>(item.description());
            lore.add(
                    renderTemplate(
                            message("selected-item-price-template"),
                            Map.of(
                                    "{price}", formatAmount(item.price()),
                                    "{currency}", appConfig.settings().currency()
                            )
                    )
            );

            setButton(
                    inventory,
                    item.slot(),
                    item.material(),
                    item.title(),
                    lore,
                    actions,
                    new BuyAction(item.id(), item.categoryId())
            );
        }

        setButton(
                inventory,
                49,
                Material.ARROW,
                message("menu-back"),
                List.of(),
                actions,
                new SimpleAction(SimpleActionType.OPEN_CATEGORIES)
        );
        setButton(
                inventory,
                53,
                Material.BARRIER,
                message("menu-close"),
                List.of(),
                actions,
                new SimpleAction(SimpleActionType.CLOSE)
        );

        openMenu(player, inventory, actions);
    }

    private void openPurchaseConfirmationMenu(Player player, AppConfig.ItemConfig item) {
        reply(
                player,
                renderTemplate(
                        message("selected-item-template"),
                        Map.of("{item_title}", plain(item.title()))
                )
        );
        reply(
                player,
                renderTemplate(
                        message("selected-item-id-template"),
                        Map.of("{item_id}", item.id())
                )
        );
        if (!item.description().isEmpty()) {
            reply(
                    player,
                    renderTemplate(
                            message("selected-item-description-template"),
                            Map.of("{item_description}", plain(item.description().get(0)))
                    )
            );
        }
        reply(
                player,
                renderTemplate(
                        message("selected-item-price-template"),
                        Map.of(
                                "{price}", formatAmount(item.price()),
                                "{currency}", appConfig.settings().currency()
                        )
                )
        );

        final Inventory inventory = Bukkit.createInventory(
                player,
                27,
                colorize(message("menu-confirm-title"))
        );
        final Map<Integer, MenuAction> actions = new HashMap<>();

        final List<String> previewLore = new ArrayList<>(item.description());
        previewLore.add(
                renderTemplate(
                        message("selected-item-price-template"),
                        Map.of(
                                "{price}", formatAmount(item.price()),
                                "{currency}", appConfig.settings().currency()
                        )
                )
        );

        setButton(
                inventory,
                13,
                item.material(),
                item.title(),
                previewLore,
                actions,
                new BuyAction(item.id(), item.categoryId())
        );
        setButton(
                inventory,
                11,
                Material.LIME_CONCRETE,
                message("menu-confirm-purchase"),
                List.of(),
                actions,
                new ConfirmAction(item.id(), item.categoryId())
        );
        setButton(
                inventory,
                15,
                Material.RED_CONCRETE,
                message("menu-back-to-shop"),
                List.of(),
                actions,
                new OpenCategoryAction(item.categoryId())
        );

        openMenu(player, inventory, actions);
    }

    private void requestBalance(Player player) {
        reply(player, message("balance-request"));

        runSinglePlayerOperation(player, identity -> {
            final BigDecimal cachedBalance = getCachedBalance(identity.playerId());
            if (cachedBalance != null) {
                runSync(() -> {
                    final Player onlinePlayer = Bukkit.getPlayer(identity.uuid());
                    if (onlinePlayer != null) {
                        reply(
                                onlinePlayer,
                                renderTemplate(
                                        message("balance-response-template"),
                                        Map.of(
                                                "{balance}", formatAmount(cachedBalance),
                                                "{currency}", appConfig.settings().currency()
                                        )
                                )
                        );
                    }
                    reopenMainMenuIfNeeded(identity.uuid());
                });
                return;
            }

            final ApiResult<GameApiClient.BalanceResponse> apiResult = apiClient.getBalance(identity.playerId());
            if (!apiResult.isSuccess() || apiResult.data() == null) {
                runSync(() -> {
                    final Player onlinePlayer = Bukkit.getPlayer(identity.uuid());
                    if (onlinePlayer != null) {
                        reply(onlinePlayer, fallbackError(apiResult.error(), "get-balance-failed"));
                    }
                    reopenMainMenuIfNeeded(identity.uuid());
                });
                return;
            }

            final BigDecimal balance = apiResult.data().balance();
            updateBalanceCache(identity.playerId(), balance);

            runSync(() -> {
                final Player onlinePlayer = Bukkit.getPlayer(identity.uuid());
                if (onlinePlayer != null) {
                    reply(
                            onlinePlayer,
                            renderTemplate(
                                    message("balance-response-template"),
                                    Map.of(
                                            "{balance}", formatAmount(balance),
                                            "{currency}", appConfig.settings().currency()
                                    )
                            )
                    );
                }
                reopenMainMenuIfNeeded(identity.uuid());
            });
        });
    }

    private void requestTopupLink(Player player, BigDecimal amount) {
        reply(player, message("topup-link-creating"));

        runSinglePlayerOperation(player, identity -> {
            final ApiResult<GameApiClient.TopupLinkResponse> apiResult = apiClient.createTopupLink(
                    identity.playerId(),
                    amount
            );
            if (!apiResult.isSuccess() || apiResult.data() == null) {
                runSync(() -> {
                    final Player onlinePlayer = Bukkit.getPlayer(identity.uuid());
                    if (onlinePlayer != null) {
                        reply(onlinePlayer, fallbackError(apiResult.error(), "create-topup-failed"));
                    }
                    reopenMainMenuIfNeeded(identity.uuid());
                });
                return;
            }

            final String checkoutUrl = apiResult.data().checkoutUrl();
            runSync(() -> {
                final Player onlinePlayer = Bukkit.getPlayer(identity.uuid());
                if (onlinePlayer != null) {
                    reply(
                            onlinePlayer,
                            renderTemplate(
                                    message("topup-link-template"),
                                    Map.of(
                                            "{amount}", formatAmount(amount),
                                            "{currency}", appConfig.settings().currency(),
                                            "{checkout_url}", checkoutUrl
                                    )
                            )
                    );
                }
                reopenMainMenuIfNeeded(identity.uuid());
            });
        });
    }

    private void startPurchase(Player player, AppConfig.ItemConfig item) {
        if (!item.enabled()) {
            reply(player, message("purchase-not-available"));
            return;
        }

        reply(
                player,
                renderTemplate(
                        message("purchase-starting-template"),
                        Map.of("{item_title}", plain(item.title()))
                )
        );

        runSinglePlayerOperation(player, identity -> {
            final ApiResult<GameApiClient.DebitResponse> debitResult = apiClient.debit(
                    identity.playerId(),
                    item.price(),
                    buildPurchaseDescription(item)
            );

            if (!debitResult.isSuccess() || debitResult.data() == null) {
                runSync(() -> {
                    final Player onlinePlayer = Bukkit.getPlayer(identity.uuid());
                    if (onlinePlayer != null) {
                        reply(onlinePlayer, fallbackError(debitResult.error(), "purchase-debit-failed"));
                    }
                    reopenMainMenuIfNeeded(identity.uuid());
                });
                return;
            }

            final BigDecimal balanceAfter = debitResult.data().balance();
            updateBalanceCache(identity.playerId(), balanceAfter);

            runSync(() -> {
                final boolean grantSuccess = executeGrantCommands(identity, item, balanceAfter);
                final Player onlinePlayer = Bukkit.getPlayer(identity.uuid());

                if (!grantSuccess) {
                    if (onlinePlayer != null) {
                        reply(onlinePlayer, message("purchase-grant-failed"));
                    }
                    reopenMainMenuIfNeeded(identity.uuid());
                    return;
                }

                if (onlinePlayer != null) {
                    reply(
                            onlinePlayer,
                            renderTemplate(
                                    message("purchase-success-template"),
                                    Map.of(
                                            "{item_title}", plain(item.title()),
                                            "{balance}", formatAmount(balanceAfter),
                                            "{currency}", appConfig.settings().currency()
                                    )
                            )
                    );
                }

                announcePurchase(identity, item, balanceAfter);
                reopenMainMenuIfNeeded(identity.uuid());
            });
        });
    }

    private boolean executeGrantCommands(PlayerIdentity identity, AppConfig.ItemConfig item, BigDecimal balanceAfter) {
        final Map<String, String> placeholders = buildPlaceholders(identity, item, balanceAfter);
        boolean executedAtLeastOne = false;

        for (var commandTemplate : item.grantCommands()) {
            final String command = renderTemplate(commandTemplate, placeholders).trim();
            if (command.isEmpty()) {
                continue;
            }

            try {
                final boolean success = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                if (!success) {
                    getLogger().warning("Grant command returned false for item " + item.id() + ": " + command);
                    return false;
                }
                executedAtLeastOne = true;
            } catch (Exception exception) {
                getLogger().severe(
                        "Grant command failed for item " + item.id() + ": " + command + " (" + exception.getMessage() + ")"
                );
                return false;
            }
        }

        return executedAtLeastOne;
    }

    private void announcePurchase(PlayerIdentity identity, AppConfig.ItemConfig item, BigDecimal balanceAfter) {
        if (!appConfig.settings().announcePurchasesToServer()) {
            return;
        }

        final String announcement = renderTemplate(
                appConfig.settings().purchaseAnnouncementTemplate(),
                buildPlaceholders(identity, item, balanceAfter)
        );
        Bukkit.broadcastMessage(
                colorize(message("prefix") + " " + announcement)
        );
    }

    private void showHelp(Player player) {
        reply(player, message("help-header"));
        reply(player, message("help-main-menu"));
        reply(player, message("help-balance"));
        reply(player, message("help-topup"));
        reply(player, message("help-shop"));
        reply(player, message("help-buy"));
    }

    private void runSinglePlayerOperation(Player player, Consumer<PlayerIdentity> operation) {
        final UUID playerUuid = player.getUniqueId();
        if (!activeOperations.add(playerUuid)) {
            reply(player, message("balance-request-in-progress"));
            return;
        }

        final PlayerIdentity identity = new PlayerIdentity(
                playerUuid,
                player.getName(),
                playerUuid.toString().replace("-", "")
        );

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                operation.accept(identity);
            } catch (Exception exception) {
                runSync(() -> {
                    final Player onlinePlayer = Bukkit.getPlayer(identity.uuid());
                    if (onlinePlayer != null) {
                        reply(onlinePlayer, message("internal-error"));
                    }
                });
                getLogger().severe("FateDonate operation failed: " + exception.getMessage());
            } finally {
                activeOperations.remove(playerUuid);
            }
        });
    }

    private void openMenu(Player player, Inventory inventory, Map<Integer, MenuAction> actions) {
        player.openInventory(inventory);
        openMenus.put(player.getUniqueId(), new OpenMenuContext(inventory, Map.copyOf(actions)));
    }

    private void setButton(
            Inventory inventory,
            int slot,
            Material material,
            String title,
            List<String> lore,
            Map<Integer, MenuAction> actions,
            MenuAction action
    ) {
        if (slot < 0 || slot >= inventory.getSize()) {
            return;
        }

        final ItemStack itemStack = new ItemStack(material);
        final ItemMeta itemMeta = itemStack.getItemMeta();
        if (itemMeta == null) {
            return;
        }

        itemMeta.setDisplayName(colorize(title));
        if (!lore.isEmpty()) {
            itemMeta.setLore(lore.stream().map(this::colorize).collect(Collectors.toList()));
        }
        itemStack.setItemMeta(itemMeta);
        inventory.setItem(slot, itemStack);

        actions.put(slot, action);
    }

    private void reply(CommandSender sender, String message) {
        final String prefix = appConfig != null ? message("prefix") : "[FateDonate]";
        sender.sendMessage(colorize(prefix + " " + message));
    }

    private void reopenMainMenuIfNeeded(UUID playerUuid) {
        if (!appConfig.settings().reopenMainMenuAfterAction()) {
            return;
        }

        runSync(() -> {
            final Player player = Bukkit.getPlayer(playerUuid);
            if (player != null && player.isOnline()) {
                openMainMenu(player);
            }
        });
    }

    private BigDecimal getCachedBalance(String playerId) {
        if (appConfig.settings().balanceCacheSeconds() <= 0) {
            balanceCache.remove(playerId);
            return null;
        }

        final BalanceCacheEntry cacheEntry = balanceCache.get(playerId);
        if (cacheEntry == null) {
            return null;
        }

        if (cacheEntry.expiresAtMillis() <= System.currentTimeMillis()) {
            balanceCache.remove(playerId);
            return null;
        }

        return cacheEntry.balance();
    }

    private void updateBalanceCache(String playerId, BigDecimal balance) {
        if (appConfig.settings().balanceCacheSeconds() <= 0) {
            balanceCache.remove(playerId);
            return;
        }

        final long expiresAt = System.currentTimeMillis() + appConfig.settings().balanceCacheSeconds() * 1000L;
        balanceCache.put(playerId, new BalanceCacheEntry(balance, expiresAt));
    }

    private String fallbackError(String apiError, String fallbackKey) {
        if (apiError == null || apiError.isBlank()) {
            return message(fallbackKey);
        }
        return apiError;
    }

    private String message(String key) {
        if (appConfig == null) {
            return key;
        }

        final String value = appConfig.message(key);
        return value == null ? key : value;
    }

    private String renderTemplate(String template, Map<String, String> placeholders) {
        String result = template;
        for (var entry : placeholders.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private String colorize(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    private String plain(String text) {
        return ChatColor.stripColor(colorize(text));
    }

    private static BigDecimal parseAmount(String raw) {
        if (raw == null) {
            return null;
        }

        try {
            return new BigDecimal(raw.trim().replace(',', '.')).stripTrailingZeros();
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String formatAmount(BigDecimal amount) {
        synchronized (AMOUNT_FORMAT) {
            return AMOUNT_FORMAT.format(amount);
        }
    }

    private static List<String> filterByPrefix(List<String> values, String input) {
        final String normalizedInput = input == null ? "" : input.toLowerCase(Locale.ROOT);
        return values.stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(normalizedInput))
                .toList();
    }

    private static String buildPurchaseDescription(AppConfig.ItemConfig item) {
        final String itemName = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', item.title()));
        if (item.durationDays() <= 0) {
            return "Purchase item: " + itemName;
        }
        return "Purchase item: " + itemName + " (" + item.durationDays() + "d)";
    }

    private Map<String, String> buildPlaceholders(
            PlayerIdentity identity,
            AppConfig.ItemConfig item,
            BigDecimal balanceAfter
    ) {
        final Map<String, String> placeholders = new HashMap<>();
        placeholders.put("{player_name}", identity.playerName());
        placeholders.put("{player_uuid}", identity.uuid().toString());
        placeholders.put("{player_uuid_nodash}", identity.playerId());
        placeholders.put("{item_id}", item.id());
        placeholders.put("{item_name}", plain(item.title()));
        placeholders.put("{item_category}", item.categoryId());
        placeholders.put("{price}", formatAmount(item.price()));
        placeholders.put("{currency}", appConfig.settings().currency());
        placeholders.put("{duration_days}", Integer.toString(item.durationDays()));
        placeholders.put("{balance}", formatAmount(balanceAfter));
        placeholders.put("{server_id}", appConfig.settings().serverId());
        return placeholders;
    }

    private boolean isReady() {
        return appConfig != null && apiClient != null;
    }

    private void runSync(Runnable runnable) {
        Bukkit.getScheduler().runTask(this, runnable);
    }

    private record PlayerIdentity(UUID uuid, String playerName, String playerId) {
    }

    private record BalanceCacheEntry(BigDecimal balance, long expiresAtMillis) {
    }

    private record OpenMenuContext(Inventory inventory, Map<Integer, MenuAction> actions) {
    }

    private sealed interface MenuAction permits
            SimpleAction,
            TopupAction,
            OpenCategoryAction,
            BuyAction,
            ConfirmAction {
    }

    private record SimpleAction(SimpleActionType type) implements MenuAction {
    }

    private record TopupAction(BigDecimal amount) implements MenuAction {
    }

    private record OpenCategoryAction(String categoryId) implements MenuAction {
    }

    private record BuyAction(String itemId, String categoryId) implements MenuAction {
    }

    private record ConfirmAction(String itemId, String categoryId) implements MenuAction {
    }

    private enum SimpleActionType {
        SHOW_BALANCE,
        OPEN_TOPUP,
        OPEN_CATEGORIES,
        SHOW_HELP,
        OPEN_MAIN,
        CLOSE
    }
}
