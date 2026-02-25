package ru.fatedonate.minecraft;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.ArrayList;
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
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
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
import org.bukkit.scheduler.BukkitTask;
import ru.fatedonate.minecraft.api.ApiResult;
import ru.fatedonate.minecraft.api.GameApiClient;
import ru.fatedonate.minecraft.config.AppConfig;
import ru.fatedonate.minecraft.config.ConfigLoader;
import ru.fatedonate.minecraft.model.BalanceCacheEntry;
import ru.fatedonate.minecraft.model.OpenMenuContext;
import ru.fatedonate.minecraft.model.PlayerIdentity;
import ru.fatedonate.minecraft.model.TopupWatchSession;
import ru.fatedonate.minecraft.service.ApiErrorResolver;
import ru.fatedonate.minecraft.util.Pagination;

public final class FateDonatePlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private static final DecimalFormat AMOUNT_FORMAT = new DecimalFormat("0.##");
    private static final LegacyComponentSerializer LEGACY_SERIALIZER =
            LegacyComponentSerializer.legacyAmpersand();
    private static final String PERMISSION_USE = "fatedonate.use";
    private static final String PERMISSION_ADMIN = "fatedonate.admin";

    private static final int[] PAGE_SLOTS = new int[]{
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };

    private static final Map<String, String> DEFAULT_MESSAGES = Map.ofEntries(
            Map.entry("no-permission", "&cУ вас нет прав для этой команды."),
            Map.entry("reload-success", "&aКонфигурация FateDonate успешно перезагружена."),
            Map.entry("reload-failed", "&cНе удалось перезагрузить конфигурацию. Проверьте логи."),
            Map.entry("status-header", "&7Статус FateDonate:"),
            Map.entry("status-ready-template", "&7Готовность: &f{ready}"),
            Map.entry("status-api-template", "&7API URL: &f{api_base_url}"),
            Map.entry("status-server-id-template", "&7Server ID: &f{server_id}"),
            Map.entry("status-active-operations-template", "&7Активные операции: &f{count}"),
            Map.entry("status-topup-watches-template", "&7Ожидание пополнений: &f{count}"),
            Map.entry("topup-completed-template", "&aПополнение подтверждено: +{amount} {currency}. Текущий баланс: &f{balance} {currency}"),
            Map.entry("topup-watch-expired-template", "&eНе удалось дождаться подтверждения оплаты по сессии {session_id}. Проверьте баланс позже."),
            Map.entry("topup-watch-failed-template", "&cОшибка проверки статуса пополнения (сессия {session_id})."),
            Map.entry("help-status", "&f/fd status &7- статус плагина и API"),
            Map.entry("help-reload", "&f/fd reload &7- перезагрузить конфиг плагина")
    );

    private final Set<UUID> activeOperations = ConcurrentHashMap.newKeySet();
    private final Map<String, BalanceCacheEntry> balanceCache = new ConcurrentHashMap<>();
    private final Map<UUID, OpenMenuContext> openMenus = new ConcurrentHashMap<>();
    private final Map<String, TopupWatchSession> topupWatchSessions = new ConcurrentHashMap<>();

    private AppConfig appConfig;
    private GameApiClient apiClient;
    private BukkitTask topupWatchTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        if (!loadRuntimeConfig(true)) {
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        final PluginCommand fdCommand = getCommand("fd");
        if (fdCommand == null) {
            getLogger().severe("Команда /fd не зарегистрирована plugin.yml.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        fdCommand.setExecutor(this);
        fdCommand.setTabCompleter(this);
        Bukkit.getPluginManager().registerEvents(this, this);

        restartBackgroundTasks();
        getLogger().info("FateDonate loaded.");
    }

    @Override
    public void onDisable() {
        stopBackgroundTasks();

        activeOperations.clear();
        balanceCache.clear();
        openMenus.clear();
        topupWatchSessions.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        final String action = args.length == 0 ? "menu" : args[0].toLowerCase(Locale.ROOT);

        switch (action) {
            case "reload" -> {
                handleReload(sender);
                return true;
            }
            case "status" -> {
                handleStatus(sender);
                return true;
            }
            default -> {
                // continue
            }
        }

        if (!(sender instanceof Player player)) {
            reply(sender, message("player-only-command"));
            return true;
        }

        if (!hasUsePermission(player)) {
            reply(player, message("no-permission"));
            return true;
        }

        if (!isReady()) {
            reply(player, message("service-not-initialized"));
            return true;
        }

        switch (action) {
            case "menu" -> openMainMenu(player);
            case "balance" -> requestBalance(player);
            case "topup" -> {
                if (args.length == 1) {
                    openTopupMenu(player, 0);
                    return true;
                }

                final BigDecimal amount = parseAmount(args[1]);
                if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
                    reply(player, message("topup-invalid-amount"));
                    return true;
                }

                requestTopupLink(player, amount);
            }
            case "shop", "items" -> {
                if (args.length > 1) {
                    openItemsMenu(player, args[1], 0, 0);
                    return true;
                }
                openCategoriesMenu(player, 0);
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
                    openPurchaseConfirmationMenu(player, item, 0, 0);
                    return true;
                }

                player.closeInventory();
                startPurchase(player, item);
            }
            case "help" -> showHelp(player);
            default -> reply(player, message("unknown-command"));
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            final List<String> values = new ArrayList<>(List.of("balance", "topup", "shop", "buy", "help"));
            if (sender.hasPermission(PERMISSION_ADMIN)) {
                values.add("status");
                values.add("reload");
            }
            return filterByPrefix(values, args[0]);
        }

        if (!isReady()) {
            return List.of();
        }

        if (args.length == 2) {
            final String action = args[0].toLowerCase(Locale.ROOT);
            if ("topup".equals(action)) {
                return filterByPrefix(
                        appConfig.settings().topupQuickAmounts().stream().map(FateDonatePlugin::formatAmount).toList(),
                        args[1]
                );
            }
            if ("buy".equals(action)) {
                return filterByPrefix(
                        appConfig.enabledItems().stream().map(AppConfig.ItemConfig::id).toList(),
                        args[1]
                );
            }
            if ("shop".equals(action) || "items".equals(action)) {
                return filterByPrefix(
                        appConfig.categoriesWithEnabledItems().values().stream().map(AppConfig.CategoryConfig::id).toList(),
                        args[1]
                );
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

        final Consumer<Player> action = context.actions().get(event.getRawSlot());
        if (action != null) {
            action.accept(player);
        }
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

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission(PERMISSION_ADMIN)) {
            reply(sender, message("no-permission"));
            return;
        }

        reloadConfig();
        if (!loadRuntimeConfig(true)) {
            reply(sender, message("reload-failed"));
            return;
        }

        restartBackgroundTasks();
        reply(sender, message("reload-success"));
    }

    private void handleStatus(CommandSender sender) {
        if (!sender.hasPermission(PERMISSION_ADMIN)) {
            reply(sender, message("no-permission"));
            return;
        }

        reply(sender, message("status-header"));
        reply(sender, renderTemplate(message("status-ready-template"), Map.of("{ready}", isReady() ? "ready" : "not_ready")));
        reply(sender, renderTemplate(message("status-api-template"), Map.of("{api_base_url}", appConfig == null ? "-" : appConfig.settings().apiBaseUrl())));
        reply(sender, renderTemplate(message("status-server-id-template"), Map.of("{server_id}", appConfig == null ? "-" : appConfig.settings().serverId())));
        reply(sender, renderTemplate(message("status-active-operations-template"), Map.of("{count}", Integer.toString(activeOperations.size()))));
        reply(sender, renderTemplate(message("status-topup-watches-template"), Map.of("{count}", Integer.toString(topupWatchSessions.size()))));
    }

    private void openMainMenu(Player player) {
        final Inventory inventory = Bukkit.createInventory(player, 54, colorize(message("menu-main-title")));
        decorateMenu(inventory);
        final Map<Integer, Consumer<Player>> actions = new HashMap<>();

        final BigDecimal cached = getCachedBalance(player.getUniqueId().toString().replace("-", ""));
        final List<String> balanceLore = new ArrayList<>();
        balanceLore.add(cached == null
                ? "&7Нажмите, чтобы обновить баланс."
                : "&7Кэш: &f" + formatAmount(cached) + " " + appConfig.settings().currency());

        setButton(inventory, 13, Material.SUNFLOWER, message("menu-my-balance"), balanceLore, actions, target -> {
            target.closeInventory();
            requestBalance(target);
        });
        setButton(inventory, 21, Material.EMERALD, message("menu-topup"), List.of("&7Быстрые суммы и создание ссылки."), actions, target -> openTopupMenu(target, 0));
        setButton(inventory, 23, Material.CHEST, message("menu-shop"), List.of("&7Категории и товары."), actions, target -> openCategoriesMenu(target, 0));
        setButton(inventory, 31, Material.CLOCK, "&bСервис", List.of(
                "&7Активные операции: &f" + activeOperations.size(),
                "&7Ожидание пополнений: &f" + topupWatchSessions.size()
        ), actions, target -> {
            if (target.hasPermission(PERMISSION_ADMIN)) {
                target.closeInventory();
                handleStatus(target);
            }
        });
        setButton(inventory, 39, Material.BOOK, message("menu-help"), List.of("&7Показать доступные команды."), actions, target -> {
            target.closeInventory();
            showHelp(target);
            reopenMainMenuIfNeeded(target.getUniqueId());
        });
        setButton(inventory, 41, Material.BARRIER, message("menu-close"), List.of(), actions, Player::closeInventory);

        openMenu(player, inventory, actions);
    }

    private void openTopupMenu(Player player, int page) {
        final List<BigDecimal> amounts = appConfig.settings().topupQuickAmounts();
        if (amounts.isEmpty()) {
            reply(player, message("config-missing-topup-amounts"));
            return;
        }

        final Pagination.Page<BigDecimal> pageData = Pagination.paginate(amounts, page, PAGE_SLOTS.length);
        final String title = pageData.totalPages() > 1
                ? message("menu-topup-title") + " &8(" + (pageData.page() + 1) + "/" + pageData.totalPages() + ")"
                : message("menu-topup-title");

        final Inventory inventory = Bukkit.createInventory(player, 54, colorize(title));
        decorateMenu(inventory);
        final Map<Integer, Consumer<Player>> actions = new HashMap<>();

        setButton(inventory, 4, Material.GOLD_INGOT, "&eБаланс", List.of("&7Проверьте баланс перед пополнением."), actions, target -> {
            target.closeInventory();
            requestBalance(target);
        });

        for (int index = 0; index < pageData.values().size(); index += 1) {
            final BigDecimal amount = pageData.values().get(index);
            final String label = renderTemplate(message("topup-quick-template"), Map.of(
                    "{amount}", formatAmount(amount),
                    "{currency}", appConfig.settings().currency()
            ));
            setButton(inventory, PAGE_SLOTS[index], Material.EMERALD, label, List.of("&7Нажмите, чтобы получить ссылку."), actions, target -> {
                target.closeInventory();
                requestTopupLink(target, amount);
            });
        }

        if (pageData.page() > 0) {
            setButton(inventory, 45, Material.ARROW, "&eПредыдущая страница", List.of(), actions, target -> openTopupMenu(target, pageData.page() - 1));
        }
        if (pageData.page() + 1 < pageData.totalPages()) {
            setButton(inventory, 53, Material.ARROW, "&eСледующая страница", List.of(), actions, target -> openTopupMenu(target, pageData.page() + 1));
        }

        setButton(inventory, 48, Material.PAPER, "&bКастомная сумма", List.of("&7Используйте: /fd topup <сумма>"), actions, target -> {
            target.closeInventory();
            reply(target, message("topup-usage"));
            reopenMainMenuIfNeeded(target.getUniqueId());
        });
        setButton(inventory, 49, Material.OAK_DOOR, message("menu-back"), List.of(), actions, this::openMainMenu);
        setButton(inventory, 50, Material.BARRIER, message("menu-close"), List.of(), actions, Player::closeInventory);

        openMenu(player, inventory, actions);
    }

    private void openCategoriesMenu(Player player, int page) {
        final List<AppConfig.CategoryConfig> categories = appConfig.categoriesWithEnabledItems()
                .values()
                .stream()
                .sorted(Comparator.comparingInt(AppConfig.CategoryConfig::slot))
                .toList();

        if (categories.isEmpty()) {
            reply(player, message("categories-empty"));
            return;
        }

        final Pagination.Page<AppConfig.CategoryConfig> pageData = Pagination.paginate(categories, page, PAGE_SLOTS.length);
        final Inventory inventory = Bukkit.createInventory(
                player,
                54,
                colorize(pageData.totalPages() > 1
                        ? message("menu-categories-title") + " &8(" + (pageData.page() + 1) + "/" + pageData.totalPages() + ")"
                        : message("menu-categories-title"))
        );
        decorateMenu(inventory);
        final Map<Integer, Consumer<Player>> actions = new HashMap<>();

        for (int index = 0; index < pageData.values().size(); index += 1) {
            final AppConfig.CategoryConfig category = pageData.values().get(index);
            final List<String> lore = new ArrayList<>(category.description());
            lore.add("&8ID: " + category.id());
            setButton(inventory, PAGE_SLOTS[index], category.material(), category.title(), lore, actions, target -> openItemsMenu(target, category.id(), 0, pageData.page()));
        }

        if (pageData.page() > 0) {
            setButton(inventory, 45, Material.ARROW, "&eПредыдущая страница", List.of(), actions, target -> openCategoriesMenu(target, pageData.page() - 1));
        }
        if (pageData.page() + 1 < pageData.totalPages()) {
            setButton(inventory, 53, Material.ARROW, "&eСледующая страница", List.of(), actions, target -> openCategoriesMenu(target, pageData.page() + 1));
        }
        setButton(inventory, 49, Material.OAK_DOOR, message("menu-back"), List.of(), actions, this::openMainMenu);
        setButton(inventory, 50, Material.BARRIER, message("menu-close"), List.of(), actions, Player::closeInventory);

        openMenu(player, inventory, actions);
    }

    private void openItemsMenu(Player player, String categoryId, int page, int categoryPage) {
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

        final Pagination.Page<AppConfig.ItemConfig> pageData = Pagination.paginate(items, page, PAGE_SLOTS.length);
        final String categoryTitle = renderTemplate(message("menu-items-title-template"), Map.of("{category_name}", plain(category.title())));
        final Inventory inventory = Bukkit.createInventory(
                player,
                54,
                colorize(pageData.totalPages() > 1 ? categoryTitle + " &8(" + (pageData.page() + 1) + "/" + pageData.totalPages() + ")" : categoryTitle)
        );
        decorateMenu(inventory);
        final Map<Integer, Consumer<Player>> actions = new HashMap<>();

        for (int index = 0; index < pageData.values().size(); index += 1) {
            final AppConfig.ItemConfig item = pageData.values().get(index);
            final List<String> lore = new ArrayList<>(item.description());
            lore.add("&7ID: &f" + item.id());
            lore.add(renderTemplate(message("selected-item-price-template"), Map.of(
                    "{price}", formatAmount(item.price()),
                    "{currency}", appConfig.settings().currency()
            )));
            setButton(inventory, PAGE_SLOTS[index], item.material(), item.title(), lore, actions, target -> {
                if (appConfig.settings().requirePurchaseConfirmation()) {
                    openPurchaseConfirmationMenu(target, item, pageData.page(), categoryPage);
                    return;
                }
                target.closeInventory();
                startPurchase(target, item);
            });
        }

        if (pageData.page() > 0) {
            setButton(inventory, 45, Material.ARROW, "&eПредыдущая страница", List.of(), actions, target -> openItemsMenu(target, category.id(), pageData.page() - 1, categoryPage));
        }
        if (pageData.page() + 1 < pageData.totalPages()) {
            setButton(inventory, 53, Material.ARROW, "&eСледующая страница", List.of(), actions, target -> openItemsMenu(target, category.id(), pageData.page() + 1, categoryPage));
        }
        setButton(inventory, 49, Material.OAK_DOOR, message("menu-back"), List.of(), actions, target -> openCategoriesMenu(target, categoryPage));
        setButton(inventory, 50, Material.BARRIER, message("menu-close"), List.of(), actions, Player::closeInventory);

        openMenu(player, inventory, actions);
    }

    private void openPurchaseConfirmationMenu(Player player, AppConfig.ItemConfig item, int itemPage, int categoryPage) {
        final Inventory inventory = Bukkit.createInventory(player, 27, colorize(message("menu-confirm-title")));
        final Map<Integer, Consumer<Player>> actions = new HashMap<>();

        setButton(inventory, 11, Material.LIME_CONCRETE, message("menu-confirm-purchase"), List.of(
                "&7Товар: &f" + plain(item.title()),
                "&7Цена: &f" + formatAmount(item.price()) + " " + appConfig.settings().currency()
        ), actions, target -> {
            target.closeInventory();
            startPurchase(target, item);
        });

        final List<String> previewLore = new ArrayList<>(item.description());
        previewLore.add("&8ID: " + item.id());
        previewLore.add("&7Цена: &f" + formatAmount(item.price()) + " " + appConfig.settings().currency());
        setButton(inventory, 13, item.material(), item.title(), previewLore, actions, target -> {
            // noop
        });
        setButton(inventory, 15, Material.RED_CONCRETE, message("menu-back-to-shop"), List.of(), actions, target -> openItemsMenu(target, item.categoryId(), itemPage, categoryPage));

        openMenu(player, inventory, actions);
    }

    private void requestBalance(Player player) {
        reply(player, message("balance-request"));

        runSinglePlayerOperation(player, identity -> {
            final BigDecimal cached = getCachedBalance(identity.playerId());
            if (cached != null) {
                runSync(() -> {
                    final Player online = Bukkit.getPlayer(identity.uuid());
                    if (online != null) {
                        reply(online, renderTemplate(message("balance-response-template"), Map.of(
                                "{balance}", formatAmount(cached),
                                "{currency}", appConfig.settings().currency()
                        )));
                    }
                    reopenMainMenuIfNeeded(identity.uuid());
                });
                return;
            }

            final ApiResult<GameApiClient.BalanceResponse> apiResult = apiClient.getBalance(identity.playerId());
            if (!apiResult.isSuccess() || apiResult.data() == null) {
                runSync(() -> {
                    final Player online = Bukkit.getPlayer(identity.uuid());
                    if (online != null) {
                        reply(online, ApiErrorResolver.resolve(apiResult, message("get-balance-failed")));
                    }
                    reopenMainMenuIfNeeded(identity.uuid());
                });
                return;
            }

            final BigDecimal balance = apiResult.data().balance();
            updateBalanceCache(identity.playerId(), balance);
            runSync(() -> {
                final Player online = Bukkit.getPlayer(identity.uuid());
                if (online != null) {
                    reply(online, renderTemplate(message("balance-response-template"), Map.of(
                            "{balance}", formatAmount(balance),
                            "{currency}", appConfig.settings().currency()
                    )));
                }
                reopenMainMenuIfNeeded(identity.uuid());
            });
        });
    }

    private void requestTopupLink(Player player, BigDecimal amount) {
        reply(player, message("topup-link-creating"));
        runSinglePlayerOperation(player, identity -> {
            final ApiResult<GameApiClient.TopupLinkResponse> apiResult = apiClient.createTopupLink(identity.playerId(), identity.playerName(), amount);
            if (!apiResult.isSuccess() || apiResult.data() == null) {
                runSync(() -> {
                    final Player online = Bukkit.getPlayer(identity.uuid());
                    if (online != null) {
                        reply(online, ApiErrorResolver.resolve(apiResult, message("create-topup-failed")));
                    }
                    reopenMainMenuIfNeeded(identity.uuid());
                });
                return;
            }

            registerTopupWatch(identity, apiResult.data().sessionId());
            runSync(() -> {
                final Player online = Bukkit.getPlayer(identity.uuid());
                if (online != null) {
                    sendTopupLinkMessage(online, amount, apiResult.data().checkoutUrl());
                }
                reopenMainMenuIfNeeded(identity.uuid());
            });
        });
    }

    private void startPurchase(Player player, AppConfig.ItemConfig item) {
        reply(player, renderTemplate(message("purchase-starting-template"), Map.of("{item_title}", plain(item.title()))));

        runSinglePlayerOperation(player, identity -> {
            final ApiResult<GameApiClient.PurchaseResponse> purchaseResult = apiClient.createPurchase(
                    identity.playerId(),
                    identity.playerName(),
                    item.id(),
                    plain(item.title()),
                    item.price(),
                    buildPurchaseDescription(item)
            );
            if (!purchaseResult.isSuccess() || purchaseResult.data() == null) {
                runSync(() -> {
                    final Player online = Bukkit.getPlayer(identity.uuid());
                    if (online != null) {
                        reply(online, ApiErrorResolver.resolve(purchaseResult, message("purchase-debit-failed")));
                    }
                    reopenMainMenuIfNeeded(identity.uuid());
                });
                return;
            }

            final BigDecimal balanceAfter = purchaseResult.data().balance();
            updateBalanceCache(identity.playerId(), balanceAfter);
            final List<String> commands = buildRenderedGrantCommands(identity, item, balanceAfter);

            runSync(() -> {
                final boolean grantSuccess = executeRenderedCommands(commands, item.id());
                final Player online = Bukkit.getPlayer(identity.uuid());

                if (grantSuccess) {
                    if (online != null) {
                        reply(online, renderTemplate(message("purchase-success-template"), Map.of(
                                "{item_title}", plain(item.title()),
                                "{balance}", formatAmount(balanceAfter),
                                "{currency}", appConfig.settings().currency()
                        )));
                    }
                    announcePurchase(identity, item, balanceAfter);
                    reopenMainMenuIfNeeded(identity.uuid());
                    return;
                }

                if (online != null) {
                    reply(online, message("purchase-grant-failed"));
                }
                reopenMainMenuIfNeeded(identity.uuid());
            });
        });
    }

    private void pollTopupSessions() {
        if (!isReady() || !appConfig.settings().topupWatchEnabled() || topupWatchSessions.isEmpty()) {
            return;
        }

        final long now = System.currentTimeMillis();
        final List<TopupWatchSession> sessions = new ArrayList<>(topupWatchSessions.values());

        for (var watch : sessions) {
            final boolean isExpiredByTimeout = watch.expiresAtMillis() <= now;
            final ApiResult<GameApiClient.TopupSessionResponse> statusResult = apiClient.getTopupSession(watch.sessionId());
            if (!statusResult.isSuccess() || statusResult.data() == null) {
                if (statusResult.statusCode() == 404) {
                    topupWatchSessions.remove(watch.sessionId());
                    if (isExpiredByTimeout) {
                        runSync(() -> {
                            final Player player = Bukkit.getPlayer(watch.playerUuid());
                            if (player != null) {
                                reply(player, renderTemplate(message("topup-watch-expired-template"), Map.of("{session_id}", watch.sessionId())));
                            }
                        });
                    }
                } else if (isExpiredByTimeout) {
                    topupWatchSessions.remove(watch.sessionId());
                    runSync(() -> {
                        final Player player = Bukkit.getPlayer(watch.playerUuid());
                        if (player != null) {
                            reply(player, renderTemplate(message("topup-watch-expired-template"), Map.of("{session_id}", watch.sessionId())));
                        }
                    });
                }
                continue;
            }

            final String status = statusResult.data().status().toUpperCase(Locale.ROOT);
            if ("COMPLETED".equals(status)) {
                topupWatchSessions.remove(watch.sessionId());
                BigDecimal balanceAfter = null;
                final ApiResult<GameApiClient.BalanceResponse> balanceResult = apiClient.getBalance(watch.playerId());
                if (balanceResult.isSuccess() && balanceResult.data() != null) {
                    balanceAfter = balanceResult.data().balance();
                    updateBalanceCache(watch.playerId(), balanceAfter);
                }
                final BigDecimal finalBalanceAfter = balanceAfter;
                runSync(() -> {
                    final Player player = Bukkit.getPlayer(watch.playerUuid());
                    if (player != null) {
                        reply(player, renderTemplate(message("topup-completed-template"), Map.of(
                                "{amount}", formatAmount(statusResult.data().amount()),
                                "{currency}", statusResult.data().currency(),
                                "{balance}", finalBalanceAfter == null ? "?" : formatAmount(finalBalanceAfter)
                        )));
                    }
                });
                continue;
            }

            if (isExpiredByTimeout && "PENDING".equals(status)) {
                topupWatchSessions.remove(watch.sessionId());
                runSync(() -> {
                    final Player player = Bukkit.getPlayer(watch.playerUuid());
                    if (player != null) {
                        reply(player, renderTemplate(message("topup-watch-expired-template"), Map.of("{session_id}", watch.sessionId())));
                    }
                });
                continue;
            }

            if ("FAILED".equals(status)) {
                topupWatchSessions.remove(watch.sessionId());
                runSync(() -> {
                    final Player player = Bukkit.getPlayer(watch.playerUuid());
                    if (player != null) {
                        reply(player, renderTemplate(message("topup-watch-failed-template"), Map.of("{session_id}", watch.sessionId())));
                    }
                });
            }
        }
    }

    private void registerTopupWatch(PlayerIdentity identity, String sessionId) {
        if (!isReady() || !appConfig.settings().topupWatchEnabled()) {
            return;
        }
        final long now = System.currentTimeMillis();
        topupWatchSessions.put(sessionId, new TopupWatchSession(
                sessionId,
                identity.uuid(),
                identity.playerId(),
                identity.playerName(),
                now,
                now + appConfig.settings().topupWatchTimeoutSeconds() * 1000L
        ));
    }

    private void restartBackgroundTasks() {
        stopBackgroundTasks();
        if (!isReady()) {
            return;
        }

        if (appConfig.settings().topupWatchEnabled()) {
            topupWatchTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                    this,
                    this::pollTopupSessions,
                    60L,
                    Math.max(20L, appConfig.settings().topupStatusPollIntervalSeconds() * 20L)
            );
        } else {
            topupWatchSessions.clear();
        }
    }

    private void stopBackgroundTasks() {
        if (topupWatchTask != null) {
            topupWatchTask.cancel();
            topupWatchTask = null;
        }
    }

    private boolean loadRuntimeConfig(boolean logErrors) {
        final ConfigLoader.LoadResult loadResult = ConfigLoader.load(getConfig());
        if (!loadResult.errors().isEmpty() || loadResult.config() == null) {
            if (logErrors) {
                getLogger().severe("FateDonate конфиг имеет ошибки:");
                for (var error : loadResult.errors()) {
                    getLogger().severe("- " + error);
                }
            }
            return false;
        }

        appConfig = loadResult.config();
        apiClient = new GameApiClient(appConfig.settings());
        return true;
    }

    private void runSinglePlayerOperation(Player player, Consumer<PlayerIdentity> operation) {
        final UUID playerUuid = player.getUniqueId();
        if (!activeOperations.add(playerUuid)) {
            reply(player, message("balance-request-in-progress"));
            return;
        }

        final PlayerIdentity identity = new PlayerIdentity(
                playerUuid,
                player.getName() == null || player.getName().isBlank()
                        ? playerUuid.toString().replace("-", "")
                        : player.getName().trim(),
                playerUuid.toString().replace("-", "")
        );

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                operation.accept(identity);
            } catch (Exception exception) {
                runSync(() -> {
                    final Player online = Bukkit.getPlayer(identity.uuid());
                    if (online != null) {
                        reply(online, message("internal-error"));
                    }
                });
                getLogger().severe("FateDonate неудачная операция: " + exception.getMessage());
            } finally {
                activeOperations.remove(playerUuid);
            }
        });
    }

    private List<String> buildRenderedGrantCommands(PlayerIdentity identity, AppConfig.ItemConfig item, BigDecimal balanceAfter) {
        final Map<String, String> placeholders = buildPlaceholders(identity, item, balanceAfter);
        final List<String> commands = new ArrayList<>();
        for (var template : item.grantCommands()) {
            final String command = renderTemplate(template, placeholders).trim();
            if (!command.isEmpty()) {
                commands.add(command);
            }
        }
        return commands;
    }

    private boolean executeRenderedCommands(List<String> commands, String itemId) {
        boolean executedAtLeastOne = false;
        for (var command : commands) {
            if (command == null || command.isBlank()) {
                continue;
            }

            try {
                final boolean success = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                if (!success) {
                    getLogger().warning("Неудачное выполнение команды у предмета " + itemId + ": " + command);
                    return false;
                }
                executedAtLeastOne = true;
            } catch (Exception exception) {
                getLogger().severe("Ошибка выполнения команды " + itemId + ": " + command + " (" + exception.getMessage() + ")");
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
        Bukkit.broadcastMessage(colorize(message("prefix") + " " + announcement));
    }

    private void showHelp(Player player) {
        reply(player, message("help-header"));
        reply(player, message("help-main-menu"));
        reply(player, message("help-balance"));
        reply(player, message("help-topup"));
        reply(player, message("help-shop"));
        reply(player, message("help-buy"));
        if (player.hasPermission(PERMISSION_ADMIN)) {
            reply(player, message("help-status"));
            reply(player, message("help-reload"));
        }
    }

    private void openMenu(Player player, Inventory inventory, Map<Integer, Consumer<Player>> actions) {
        player.openInventory(inventory);
        openMenus.put(player.getUniqueId(), new OpenMenuContext(inventory, Map.copyOf(actions)));
    }

    private void decorateMenu(Inventory inventory) {
        final Set<Integer> content = new HashSet<>();
        for (int slot : PAGE_SLOTS) {
            content.add(slot);
        }

        for (int slot = 0; slot < inventory.getSize(); slot += 1) {
            if (content.contains(slot)) {
                continue;
            }
            if (inventory.getItem(slot) != null && inventory.getItem(slot).getType() != Material.AIR) {
                continue;
            }

            final ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
            final ItemMeta meta = filler.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(colorize("&8 "));
                filler.setItemMeta(meta);
            }
            inventory.setItem(slot, filler);
        }
    }

    private void setButton(
            Inventory inventory,
            int slot,
            Material material,
            String title,
            List<String> lore,
            Map<Integer, Consumer<Player>> actions,
            Consumer<Player> action
    ) {
        if (slot < 0 || slot >= inventory.getSize()) {
            return;
        }

        final ItemStack itemStack = new ItemStack(material);
        final ItemMeta meta = itemStack.getItemMeta();
        if (meta == null) {
            return;
        }

        meta.setDisplayName(colorize(title));
        if (!lore.isEmpty()) {
            meta.setLore(lore.stream().map(this::colorize).collect(Collectors.toList()));
        }
        itemStack.setItemMeta(meta);
        inventory.setItem(slot, itemStack);

        if (action != null) {
            actions.put(slot, action);
        }
    }

    private boolean hasUsePermission(CommandSender sender) {
        return sender.hasPermission(PERMISSION_USE) || sender.hasPermission(PERMISSION_ADMIN);
    }

    private void reply(CommandSender sender, String text) {
        final String prefix = appConfig != null ? message("prefix") : "&6[FateDonate]&r";
        sender.sendMessage(colorize(prefix + " " + text));
    }

    private void sendTopupLinkMessage(Player player, BigDecimal amount, String checkoutUrl) {
        final String topupTemplate = renderTemplate(message("topup-link-template"), Map.of(
                "{amount}", formatAmount(amount),
                "{currency}", appConfig.settings().currency(),
                "{checkout_url}", "{checkout_url}"
        ));
        final String fullTemplate = message("prefix") + " " + topupTemplate;
        final int placeholderIndex = fullTemplate.indexOf("{checkout_url}");

        if (placeholderIndex < 0) {
            reply(player, renderTemplate(message("topup-link-template"), Map.of(
                    "{amount}", formatAmount(amount),
                    "{currency}", appConfig.settings().currency(),
                    "{checkout_url}", checkoutUrl
            )));
            player.sendMessage(
                    Component.text(checkoutUrl, NamedTextColor.AQUA, TextDecoration.UNDERLINED)
                            .clickEvent(ClickEvent.openUrl(checkoutUrl))
                            .hoverEvent(HoverEvent.showText(Component.text("Нажмите, чтобы открыть ссылку")))
            );
            return;
        }

        final String before = fullTemplate.substring(0, placeholderIndex);
        final String after = fullTemplate.substring(placeholderIndex + "{checkout_url}".length());
        final Component clickableUrl = Component.text(checkoutUrl, NamedTextColor.AQUA, TextDecoration.UNDERLINED)
                .clickEvent(ClickEvent.openUrl(checkoutUrl))
                .hoverEvent(HoverEvent.showText(Component.text("Нажмите, чтобы открыть ссылку")));

        player.sendMessage(
                LEGACY_SERIALIZER.deserialize(before)
                        .append(clickableUrl)
                        .append(LEGACY_SERIALIZER.deserialize(after))
        );
    }

    private String message(String key) {
        if (appConfig != null) {
            final String value = appConfig.message(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return DEFAULT_MESSAGES.getOrDefault(key, key);
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

    private void reopenMainMenuIfNeeded(UUID playerUuid) {
        if (!isReady() || !appConfig.settings().reopenMainMenuAfterAction()) {
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
        if (!isReady() || appConfig.settings().balanceCacheSeconds() <= 0) {
            balanceCache.remove(playerId);
            return null;
        }

        final BalanceCacheEntry entry = balanceCache.get(playerId);
        if (entry == null) {
            return null;
        }
        if (entry.expiresAtMillis() <= System.currentTimeMillis()) {
            balanceCache.remove(playerId);
            return null;
        }
        return entry.balance();
    }

    private void updateBalanceCache(String playerId, BigDecimal balance) {
        if (!isReady() || appConfig.settings().balanceCacheSeconds() <= 0) {
            balanceCache.remove(playerId);
            return;
        }
        final long expiresAt = System.currentTimeMillis() + appConfig.settings().balanceCacheSeconds() * 1000L;
        balanceCache.put(playerId, new BalanceCacheEntry(balance, expiresAt));
    }

    private Map<String, String> buildPlaceholders(PlayerIdentity identity, AppConfig.ItemConfig item, BigDecimal balanceAfter) {
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
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(normalizedInput)).toList();
    }

    private static String buildPurchaseDescription(AppConfig.ItemConfig item) {
        final String itemName = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', item.title()));
        if (item.durationDays() <= 0) {
            return "Покупка: " + itemName;
        }
        return "Покупка: " + itemName + " (" + item.durationDays() + "d)";
    }

    private boolean isReady() {
        return appConfig != null && apiClient != null;
    }

    private void runSync(Runnable runnable) {
        Bukkit.getScheduler().runTask(this, runnable);
    }
}
