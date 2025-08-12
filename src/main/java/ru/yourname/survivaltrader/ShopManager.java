package ru.yourname.survivaltrader;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Statistic;
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
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class ShopManager implements Listener, CommandExecutor {

    private final Main plugin;
    private final Random random = new Random();

    private List<Map<String, Object>> currentItems = new ArrayList<>();
    private Map<String, String> previousBidItems = new HashMap<>();
    private final Map<UUID, Long> lastActionTime = new HashMap<>();
    private Map<String, Integer> globalPurchases = new HashMap<>();
    private Map<UUID, Map<String, Integer>> playerPurchases = new HashMap<>();
    private long lastUpdateTime;

    public ShopManager(Main plugin) {
        this.plugin = plugin;
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

        List<Map<String, Object>> pool = new ArrayList<>();
        for (Object o : possible) {
            if (o instanceof Map<?, ?> m) {
                Map<String, Object> copy = new HashMap<>();
                for (Map.Entry<?, ?> e : m.entrySet()) copy.put(String.valueOf(e.getKey()), e.getValue());
                pool.add(copy);
            }
        }
        Collections.shuffle(pool, random);

        List<String> bidPool = plugin.getConfig().getStringList("possible-bid-items");
        if (bidPool.isEmpty()) bidPool = Arrays.asList("DIRT", "COBBLESTONE", "OAK_LOG");

        int count = Math.max(1, plugin.getConfig().getInt("shop.items-per-update", 3));
        for (int i = 0; i < count && i < pool.size(); i++) {
            Map<String, Object> item = pool.get(i);
            String itemName = String.valueOf(item.get("item"));
            String chosen = chooseBidItem(itemName, bidPool);
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
        openShop(player);
        long left = getShopSecondsLeft();
        player.sendMessage(plugin.getConfig().getString("messages.shop-timeleft", "Обновление магазина через %time%.")
                .replace("%time%", formatDuration(left)));
        return true;
    }

    private void openShop(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, shopTitle());
        for (int i = 0; i < currentItems.size() && i < inv.getSize(); i++) {
            Map<String, Object> it = currentItems.get(i);
            try {
                Material type = Material.valueOf(String.valueOf(it.get("item")));
                int amount = Integer.parseInt(String.valueOf(it.get("amount")));
                int price = Integer.parseInt(String.valueOf(it.get("price")));
                String bid = String.valueOf(it.get("bid-item"));

                ItemStack stack = new ItemStack(type, amount);
                ItemMeta meta = stack.getItemMeta();
                if (meta != null) {
                    List<String> lore = new ArrayList<>();
                    lore.add("§7Цена: " + price + " " + bid);
                    lore.add("§7Клик для покупки");
                    meta.setLore(lore);
                    stack.setItemMeta(meta);
                }
                inv.setItem(i, stack);
            } catch (Exception ignored) {}
        }
        player.openInventory(inv);
    }

    @EventHandler
    public void onShopClick(InventoryClickEvent e) {
        if (e.getView() == null || e.getView().getTitle() == null) return;
        if (!shopTitle().equals(e.getView().getTitle())) return;
        if (e.getClickedInventory() == null || !e.getClickedInventory().equals(e.getView().getTopInventory())) {
            e.setCancelled(true);
            return;
        }
        e.setCancelled(true);

        if (!(e.getWhoClicked() instanceof Player player)) return;
        if (checkAntiSpam(player, plugin.getConfig().getInt("shop.anti-spam-cooldown", 10))) return;

        int slot = e.getRawSlot();
        if (slot < 0 || slot >= e.getView().getTopInventory().getSize()) return;
        if (slot >= currentItems.size()) return;

        Map<String, Object> it = currentItems.get(slot);
        String itemName = String.valueOf(it.get("item"));
        int amount = Integer.parseInt(String.valueOf(it.get("amount")));
        int basePrice = Integer.parseInt(String.valueOf(it.get("price")));
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

        int price = basePrice;
        if (isNewbie(player)) {
            int discount = plugin.getConfig().getInt("shop.newbie-discount", 0);
            price = (int) Math.max(1, Math.round(basePrice * (1 - discount / 100.0)));
            player.sendMessage(plugin.getConfig().getString("messages.newbie-bonus", "Новичок: скидка %discount%!")
                    .replace("%discount%", String.valueOf(discount)));
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

    private boolean isNewbie(Player player) {
        long playTicks = player.getStatistic(Statistic.PLAY_ONE_MINUTE);
        long seconds = playTicks / 20L;
        int level = player.getLevel();
        return seconds < plugin.getConfig().getLong("shop.newbie-time", 3600)
                || level < plugin.getConfig().getInt("shop.newbie-level", 10);
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
        if (m > 0) return String.format("%dм %02dс", m, s);
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