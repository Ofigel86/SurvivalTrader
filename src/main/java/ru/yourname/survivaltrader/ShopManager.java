package ru.yourname.survivaltrader;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class ShopManager implements Listener, CommandExecutor {

    private final Main plugin;
    private final Random random = new Random();

    // Данные магазина
    private List<Map<String, Object>> currentItems = new ArrayList<>();
    private Map<String, String> previousBidItems = new HashMap<>();
    private final Map<UUID, Long> lastActionTime = new HashMap<>();
    private Map<String, Integer> globalPurchases = new HashMap<>();
    private Map<UUID, Map<String, Integer>> playerPurchases = new HashMap<>();
    private long lastUpdateTime;

    // Навигация (страницы)
    private final Map<UUID, Integer> shopPage = new HashMap<>();
    private static final int[] PRODUCT_SLOTS = {
            10,11,12,13,14,15,
            19,20,21,22,23,24,
            28,29,30,31,32,33,
            37,38,39,40,41,42
    }; // 24 слота под товары (инвентарь 6x9 = 54)
    private static final int INV_SIZE = 54;

    // PDC-ключи
    private NamespacedKey KEY_TYPE;
    private NamespacedKey KEY_INDEX;

    public ShopManager(Main plugin) {
        this.plugin = plugin;
        this.KEY_TYPE = new NamespacedKey(plugin, "st_type");
        this.KEY_INDEX = new NamespacedKey(plugin, "st_index");
    }

    public void startUpdateTimer() {
        long periodTicks = plugin.getConfig().getLong("shop.update-interval", 3600) * 20L;
        long initialDelay = initialDelayTicks("shop.lastUpdateTime", periodTicks);

        new BukkitRunnable() {
            @Override
            public void run() {
                closeOpenShopInventories();
                updateItems();
                lastUpdateTime = System.currentTimeMillis();
                plugin.getDataConfig().set("shop.lastUpdateTime", lastUpdateTime);
                plugin.saveDataConfig();
                String msg = plugin.getConfig().getString("messages.shop-update", "Shop updated!");
                Bukkit.broadcastMessage(msg);
            }
        }.runTaskTimer(plugin, initialDelay == 0 ? 1L : initialDelay, periodTicks);

        if (initialDelay == 0 && currentItems.isEmpty()) {
            updateItems();
            lastUpdateTime = System.currentTimeMillis();
            plugin.getDataConfig().set("shop.lastUpdateTime", lastUpdateTime);
            plugin.saveDataConfig();
        }
    }

    private long initialDelayTicks(String dataKey, long periodTicks) {
        long lastMs = plugin.getDataConfig().getLong(dataKey, 0L);
        if (lastMs <= 0) return 0L;
        long elapsedTicks = (System.currentTimeMillis() - lastMs) / 50L;
        if (elapsedTicks >= periodTicks) return 0L;
        return periodTicks - elapsedTicks;
    }

    private String shopTitle() {
        return plugin.getConfig().getString("gui.shop-title", "Survival Shop");
    }

    private void closeOpenShopInventories() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory() != null && shopTitle().equals(p.getOpenInventory().getTitle())) {
                p.closeInventory();
            }
        }
    }

    private void updateItems() {
        currentItems = new ArrayList<>();
        globalPurchases.clear();
        playerPurchases.clear();

        List<?> possible = plugin.getConfig().getList("shop.possible-items");
        if (possible == null || possible.isEmpty()) {
            plugin.getLogger().warning("shop.possible-items is empty or missing in config.yml");
            return;
        }

        // Копируем список из конфига (чтобы не мутировать его)
        List<Map<String, Object>> pool = new ArrayList<>();
        for (Object o : possible) {
            if (o instanceof Map<?, ?> m) {
                Map<String, Object> copy = new HashMap<>();
                for (Map.Entry<?, ?> e : m.entrySet()) copy.put(String.valueOf(e.getKey()), e.getValue());
                pool.add(copy);
            }
        }
        Collections.shuffle(pool, random);

        // Белый список валют (только дешёвые ресурсы)
        List<String> allowedBid = plugin.getConfig().getStringList("shop.allowed-bid-items");
        if (allowedBid.isEmpty()) allowedBid = Arrays.asList("DIRT", "COBBLESTONE", "STONE", "SAND", "OAK_LOG");

        int count = Math.max(1, plugin.getConfig().getInt("shop.items-per-update", 3));
        for (int i = 0; i < count && i < pool.size(); i++) {
            Map<String, Object> item = pool.get(i);
            String itemName = String.valueOf(item.get("item"));
            String chosen = chooseBidItem(itemName, allowedBid);
            item.put("bid-item", chosen);
            currentItems.add(item);
            previousBidItems.put(itemName, chosen);
            globalPurchases.put(itemName, 0);
        }
    }

    private String chooseBidItem(String itemName, List<String> bidPool) {
        String prev = previousBidItems.get(itemName);
        List<String> list = new ArrayList<>(bidPool);
        if (prev != null) list.remove(prev);
        if (list.isEmpty()) list = bidPool;
        return list.get(random.nextInt(list.size()));
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) return true;
        if (!player.hasPermission("survivaltrader.shop")) {
            player.sendMessage("§cНет прав.");
            return true;
        }
        if (checkAntiSpam(player, plugin.getConfig().getInt("shop.anti-spam-cooldown", 10))) return true;
        openShop(player, shopPage.getOrDefault(player.getUniqueId(), 0));
        long left = getShopSecondsLeft();
        player.sendMessage(plugin.getConfig().getString("messages.shop-timeleft", "Обновление магазина через %time%.")
                .replace("%time%", formatDuration(left)));
        return true;
    }

    private void openShop(Player player, int page) {
        int total = currentItems.size();
        int perPage = PRODUCT_SLOTS.length;
        int maxPage = Math.max(0, (total - 1) / Math.max(1, perPage));
        if (page < 0) page = 0;
        if (page > maxPage) page = maxPage;
        shopPage.put(player.getUniqueId(), page);

        Inventory inv = Bukkit.createInventory(null, INV_SIZE, shopTitle());

        // Рамка и фон
        fillBorder(inv, Material.BLACK_STAINED_GLASS_PANE, Material.GRAY_STAINED_GLASS_PANE);

        // Товары
        int start = page * perPage;
        for (int i = 0; i < perPage && (start + i) < total; i++) {
            int slot = PRODUCT_SLOTS[i];
            Map<String, Object> it = currentItems.get(start + i);
            try {
                Material type = Material.valueOf(String.valueOf(it.get("item")));
                int amount = Integer.parseInt(String.valueOf(it.get("amount")));
                int price = Integer.parseInt(String.valueOf(it.get("price")));
                String bid = String.valueOf(it.get("bid-item"));

                ItemStack stack = new ItemStack(type, amount);
                ItemMeta meta = stack.getItemMeta();
                if (meta != null) {
                    meta.setDisplayName("§b" + pretty(type.name()));
                    List<String> lore = new ArrayList<>();
                    lore.add("§7Цена: §6" + price + " §e" + pretty(bid));
                    lore.add("§8— — — — — — — — —");
                    lore.add("§7ЛКМ: купить");
                    meta.setLore(lore);
                    // тэги
                    meta.getPersistentDataContainer().set(KEY_TYPE, PersistentDataType.STRING, "product");
                    meta.getPersistentDataContainer().set(KEY_INDEX, PersistentDataType.INTEGER, start + i);
                    stack.setItemMeta(meta);
                }
                inv.setItem(slot, stack);
            } catch (Exception ignored) {}
        }

        // Информация и навигация
        int pages = Math.max(1, maxPage + 1);
        String pageInfo = "§7Страница: §f" + (page + 1) + "§7/§f" + pages;

        // Инфо-кнопка (по центру низа)
        ItemStack info = makeItem(Material.BOOK, "§aИнформация", Arrays.asList(
                "§7Валюта: §fпростые ресурсы",
                "§eDIRT/COBBLESTONE/STONE/SAND/OAK_LOG",
                "§7До обновления: §f" + formatDuration(getShopSecondsLeft()),
                pageInfo
        ));
        setTag(info, "info", null);
        inv.setItem(49, info);

        // Назад/вперёд
        ItemStack prev = makeItem(Material.ARROW, page > 0 ? "§aПредыдущая страница" : "§7Предыдущая страница", Collections.singletonList(" "));
        setTag(prev, page > 0 ? "prev" : "disabled", null);
        inv.setItem(45, prev);

        ItemStack next = makeItem(Material.SPECTRAL_ARROW, page < maxPage ? "§aСледующая страница" : "§7Следующая страница", Collections.singletonList(" "));
        setTag(next, page < maxPage ? "next" : "disabled", null);
        inv.setItem(53, next);

        // Кнопка обновления ассортимента (визуал)
        ItemStack refresh = makeItem(Material.CLOCK, "§bОбновление", Arrays.asList(
                "§7Авто-обновление каждые §f" + plugin.getConfig().getLong("shop.update-interval", 3600) + "§7 сек.",
                "§7До обновления: §f" + formatDuration(getShopSecondsLeft())
        ));
        setTag(refresh, "refresh", null);
        inv.setItem(48, refresh);

        player.openInventory(inv);
    }

    private void fillBorder(Inventory inv, Material border, Material background) {
        ItemStack borderPane = makePane(border);
        ItemStack backPane = makePane(background);

        // сначала фон
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, backPane);

        // верх/низ
        for (int c = 0; c < 9; c++) {
            inv.setItem(c, borderPane);
            inv.setItem(45 + c, borderPane);
        }
        // бока
        for (int r = 0; r < 6; r++) {
            inv.setItem(r * 9, borderPane);
            inv.setItem(r * 9 + 8, borderPane);
        }
    }

    private ItemStack makePane(Material mat) {
        ItemStack pane = new ItemStack(mat);
        ItemMeta m = pane.getItemMeta();
        if (m != null) {
            m.setDisplayName(" ");
            pane.setItemMeta(m);
        }
        return pane;
    }

    private ItemStack makeItem(Material mat, String name, List<String> lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta im = item.getItemMeta();
        if (im != null) {
            if (name != null) im.setDisplayName(name);
            if (lore != null) im.setLore(lore);
            item.setItemMeta(im);
        }
        return item;
    }

    private void setTag(ItemStack stack, String type, Integer index) {
        ItemMeta im = stack.getItemMeta();
        if (im == null) return;
        im.getPersistentDataContainer().set(KEY_TYPE, PersistentDataType.STRING, type);
        if (index != null) im.getPersistentDataContainer().set(KEY_INDEX, PersistentDataType.INTEGER, index);
        stack.setItemMeta(im);
    }

    private String getTagType(ItemStack stack) {
        if (stack == null) return null;
        ItemMeta im = stack.getItemMeta();
        if (im == null) return null;
        return im.getPersistentDataContainer().get(KEY_TYPE, PersistentDataType.STRING);
    }

    private Integer getTagIndex(ItemStack stack) {
        if (stack == null) return null;
        ItemMeta im = stack.getItemMeta();
        if (im == null) return null;
        return im.getPersistentDataContainer().get(KEY_INDEX, PersistentDataType.INTEGER);
    }

    private String pretty(String key) {
        String s = key.toLowerCase().replace('_', ' ');
        if (s.isEmpty()) return key;
        return Character.toUpperCase(s.charAt(0)) + (s.length() > 1 ? s.substring(1) : "");
    }

    @EventHandler
    public void onShopClick(InventoryClickEvent e) {
        if (e.getView() == null || e.getView().getTitle() == null) return;
        if (!shopTitle().equals(e.getView().getTitle())) return;

        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player player)) return;

        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        String type = getTagType(clicked);
        if (type == null) return;

        if (checkAntiSpam(player, plugin.getConfig().getInt("shop.anti-spam-cooldown", 10))) return;

        switch (type) {
            case "product" -> {
                Integer idx = getTagIndex(clicked);
                if (idx == null) return;
                if (idx < 0 || idx >= currentItems.size()) return;
                buyProduct(player, currentItems.get(idx));
            }
            case "prev" -> openShop(player, shopPage.getOrDefault(player.getUniqueId(), 0) - 1);
            case "next" -> openShop(player, shopPage.getOrDefault(player.getUniqueId(), 0) + 1);
            case "refresh" -> {
                // просто перерисуем страницу (инфо о времени)
                openShop(player, shopPage.getOrDefault(player.getUniqueId(), 0));
            }
            default -> {}
        }
    }

    private void buyProduct(Player player, Map<String, Object> it) {
        String itemName = String.valueOf(it.get("item"));
        int amount = Integer.parseInt(String.valueOf(it.get("amount")));
        int price = Integer.parseInt(String.valueOf(it.get("price")));
        String bidStr = String.valueOf(it.get("bid-item"));

        Material type;
        Material bidType;
        try {
            type = Material.valueOf(itemName);
            bidType = Material.valueOf(bidStr);
        } catch (Exception ex) {
            player.sendMessage("§cОшибка товара.");
            return;
        }

        int globalCount = globalPurchases.getOrDefault(itemName, 0);
        if (globalCount >= plugin.getConfig().getInt("shop.global-max-purchases-per-item", 999)) {
            player.sendMessage("§cТовар распродан!");
            return;
        }

        Map<String, Integer> pMap = playerPurchases.getOrDefault(player.getUniqueId(), new HashMap<>());
        int pCount = pMap.getOrDefault(itemName, 0);
        if (pCount >= plugin.getConfig().getInt("shop.max-purchases-per-item", 999)) {
            player.sendMessage(plugin.getConfig().getString("messages.limit-reached", "Лимит достигнут!"));
            return;
        }

        if (!player.getInventory().contains(bidType, price)) {
            player.sendMessage(plugin.getConfig().getString("messages.no-items", "Недостаточно %biditem% (нужно %price%)!")
                    .replace("%biditem%", bidType.name()).replace("%price%", String.valueOf(price)));
            return;
        }

        player.getInventory().removeItem(new ItemStack(bidType, price));
        HashMap<Integer, ItemStack> overflow = player.getInventory().addItem(new ItemStack(type, amount));
        if (!overflow.isEmpty()) {
            // Вернём валюту, если не поместилось
            player.getInventory().addItem(new ItemStack(bidType, price));
            player.sendMessage(plugin.getConfig().getString("messages.inventory-full", "Недостаточно места в инвентаре!"));
            return;
        }

        player.sendMessage(plugin.getConfig().getString("messages.success-buy", "Обмен успешен!")
                .replace("%price%", String.valueOf(price))
                .replace("%biditem%", bidType.name())
                .replace("%amount%", String.valueOf(amount))
                .replace("%item%", type.name()));

        globalPurchases.put(itemName, globalCount + 1);
        pMap.put(itemName, pCount + 1);
        playerPurchases.put(player.getUniqueId(), pMap);
    }

    @EventHandler
    public void onShopDrag(InventoryDragEvent e) {
        if (e.getView() == null || e.getView().getTitle() == null) return;
        if (shopTitle().equals(e.getView().getTitle())) e.setCancelled(true);
    }

    private long getShopSecondsLeft() {
        long dur = plugin.getConfig().getLong("shop.update-interval", 3600);
        if (lastUpdateTime <= 0) return dur;
        long elapsed = (System.currentTimeMillis() - lastUpdateTime) / 1000L;
        return Math.max(0, dur - elapsed);
    }

    private static String formatDuration(long seconds) {
        long h = seconds / 3600; seconds %= 3600;
        long m = seconds / 60; long s = seconds % 60;
        if (h > 0) return String.format("%dч %02dм %02dс", h, m, s);
        if (m > 0) return String.format("%dм %02дс", m, s).replace("д", "m");
        return s + "с";
    }

    private boolean checkAntiSpam(Player player, int cooldownSec) {
        long now = System.currentTimeMillis();
        long last = lastActionTime.getOrDefault(player.getUniqueId(), 0L);
        long cdMs = Math.max(0, cooldownSec) * 1000L;
        if (now - last < cdMs) {
            long left = (cdMs - (now - last) + 999) / 1000;
            player.sendMessage(plugin.getConfig().getString("messages.anti-spam", "Подожди %time% сек.")
                    .replace("%time%", String.valueOf(left)));
            return true;
        }
        lastActionTime.put(player.getUniqueId(), now);
        return false;
    }

    public void saveData() {
        FileConfiguration data = plugin.getDataConfig();
        data.set("shop.currentItems", currentItems);
        data.set("shop.previousBidItems", previousBidItems);
        data.set("shop.globalPurchases", globalPurchases);

        Map<String, Map<String, Integer>> tmp = new HashMap<>();
        for (Map.Entry<UUID, Map<String, Integer>> e : playerPurchases.entrySet()) {
            tmp.put(e.getKey().toString(), e.getValue());
        }
        data.set("shop.playerPurchases", tmp);
        data.set("shop.lastUpdateTime", lastUpdateTime);
    }

    @SuppressWarnings("unchecked")
    public void loadData() {
        FileConfiguration data = plugin.getDataConfig();

        currentItems = new ArrayList<>();
        Object raw = data.get("shop.currentItems");
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    Map<String, Object> copy = new HashMap<>();
                    for (Map.Entry<?, ?> e : m.entrySet()) copy.put(String.valueOf(e.getKey()), e.getValue());
                    currentItems.add(copy);
                }
            }
        }

        previousBidItems = new HashMap<>();
        if (data.isConfigurationSection("shop.previousBidItems")) {
            data.getConfigurationSection("shop.previousBidItems").getValues(false).forEach((k, v) ->
                    previousBidItems.put(k, String.valueOf(v)));
        }

        globalPurchases = new HashMap<>();
        if (data.isConfigurationSection("shop.globalPurchases")) {
            data.getConfigurationSection("shop.globalPurchases").getValues(false).forEach((k, v) -> {
                try { globalPurchases.put(k, Integer.parseInt(String.valueOf(v))); } catch (Exception ignored) {}
            });
        }

        playerPurchases = new HashMap<>();
        if (data.isConfigurationSection("shop.playerPurchases")) {
            data.getConfigurationSection("shop.playerPurchases").getValues(false).forEach((k, v) -> {
                try {
                    UUID id = UUID.fromString(k);
                    Map<String, Integer> inner = new HashMap<>();
                    if (v instanceof Map<?, ?> map) {
                        for (Map.Entry<?, ?> e : map.entrySet()) {
                            try { inner.put(String.valueOf(e.getKey()), Integer.parseInt(String.valueOf(e.getValue()))); } catch (Exception ignored) {}
                        }
                    }
                    playerPurchases.put(id, inner);
                } catch (Exception ignored) {}
            });
        }

        lastUpdateTime = data.getLong("shop.lastUpdateTime", 0L);

        if (currentItems.isEmpty()) updateItems();
    }
}
