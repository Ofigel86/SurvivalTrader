package ru.yourname.survivaltrader;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.NamespacedKey;
import org.bukkit.block.ShulkerBox;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

public class AuctionManager implements CommandExecutor, Listener, TabCompleter {

    private final Main plugin;
    private final Random random = new Random();

    // PDC keys for GUI
    private final NamespacedKey KEY_TYPE;
    private final NamespacedKey KEY_VAL;

    private Material currentBidItem;
    private int currentMinBid;
    private ItemStack currentLot;
    private boolean isTroll = false;

    // ставки
    private final Map<UUID, Integer> effectiveBids = new HashMap<>();
    private final Map<UUID, Integer> deposits = new HashMap<>();
    private final Map<UUID, Integer> playerBidCounts = new HashMap<>();
    private final Map<UUID, Long> lastActionTime = new HashMap<>();

    // оффлайн-награды и возвраты
    private final Map<UUID, List<ItemStack>> pendingRewards = new HashMap<>();
    private final Map<UUID, Map<String, Integer>> pendingReturns = new HashMap<>();

    private long lastAuctionStart;

    // GUI layout
    private static final int INV_SIZE = 54;
    private static final int LOT_SLOT = 22;
    private static final int INFO_SLOT = 49;
    private static final int PREVIEW_SLOT = 48;
    private static final int NEXT_BID_SLOT = 51;
    private static final int INC1_SLOT = 40;
    private static final int INC5_SLOT = 41;
    private static final int INC16_SLOT = 42;
    private static final int INC64_SLOT = 43;
    private static final int[] BIDDER_SLOTS = { 28, 29, 37, 38, 46, 47 };

    // BossBar
    private BossBar bossBar;
    private BukkitTask bossbarTask;

    public AuctionManager(Main plugin) {
        this.plugin = plugin;
        this.KEY_TYPE = new NamespacedKey(plugin, "auc_type");
        this.KEY_VAL = new NamespacedKey(plugin, "auc_val");
    }

    private String auctionTitle() {
        return plugin.getConfig().getString("gui.auction-title", "Auction");
    }

    public void startAuctionTimer() {
        long periodTicks = plugin.getConfig().getLong("auction.duration", 300) * 20L;
        long initialDelay = initialDelayTicks("auction.lastAuctionStart", periodTicks);

        new BukkitRunnable() {
            @Override
            public void run() {
                endAuction(true);
                startNewAuction();
                lastAuctionStart = System.currentTimeMillis();
                plugin.getDataConfig().set("auction.lastAuctionStart", lastAuctionStart);
                plugin.saveDataConfig();
            }
        }.runTaskTimer(plugin, initialDelay == 0 ? 1L : initialDelay, periodTicks);

        if (initialDelay == 0 && (currentLot == null || currentBidItem == null)) {
            startNewAuction();
            lastAuctionStart = System.currentTimeMillis();
            plugin.getDataConfig().set("auction.lastAuctionStart", lastAuctionStart);
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

    private void startNewAuction() {
        effectiveBids.clear();
        deposits.clear();
        playerBidCounts.clear();

        int chance = Math.max(0, Math.min(100, plugin.getConfig().getInt("auction.troll-chance", 0)));
        isTroll = random.nextInt(100) < chance;

        currentBidItem = resolveBidItem();
        currentMinBid = Math.max(1, plugin.getConfig().getInt("auction.min-bid-base", 1));

        if (isTroll) {
            currentLot = createTrollLot();
            Bukkit.broadcastMessage(plugin.getConfig().getString("messages.new-auction-troll", "Новый аукцион (тролль)")
                    .replace("%fakebiditem%", plugin.getConfig().getString("auction.fake-bid-item", "DIRT"))
                    .replace("%minbid%", String.valueOf(currentMinBid)));
        } else {
            List<String> opLots = plugin.getConfig().getStringList("auction.op-lots");
            if (opLots.isEmpty()) {
                opLots = Arrays.asList("TOTEM_OF_UNDYING","ELYTRA","NETHERITE_SWORD","BEACON","ENCHANTED_GOLDEN_APPLE","ENCHANTED_BOOK");
            }

            boolean genDiamond = plugin.getConfig().getBoolean("auction.generate-diamond-gear", true);
            if (genDiamond && random.nextBoolean()) {
                currentLot = generateDiamondGear();
            } else {
                String lotName = opLots.get(random.nextInt(opLots.size()));
                currentLot = new ItemStack(Material.valueOf(lotName));
                ItemMeta meta = currentLot.getItemMeta();
                if (meta != null) {
                    meta.setDisplayName("§b" + pretty(lotName) + " §6[OP]");
                    currentLot.setItemMeta(meta);
                }
            }

            String lotShown = (currentLot.hasItemMeta() && currentLot.getItemMeta().hasDisplayName())
                    ? currentLot.getItemMeta().getDisplayName() : currentLot.getType().name();

            Bukkit.broadcastMessage(plugin.getConfig().getString("messages.new-auction", "Новый аукцион!")
                    .replace("%lot%", lotShown)
                    .replace("%biditem%", currentBidItem.name())
                    .replace("%minbid%", String.valueOf(currentMinBid)));
        }

        // BossBar
        if (bossbarEnabled()) {
            ensureBossBarCreated();
            updateBossBar(); // сразу обновим
            startBossBarTask(); // и запустим апдейтер
        } else {
            clearBossBar();
        }
    }

    private Material resolveBidItem() {
        // Если тролль — для логичности выставим валюту = fake-bid-item (видно в BossBar и GUI)
        if (isTroll) {
            String fake = plugin.getConfig().getString("auction.fake-bid-item", "DIRT");
            Material mm = Material.matchMaterial(fake);
            return mm != null ? mm : Material.DIRT;
        }

        // Ротация валют: daily | hourly | off
        String rotation = plugin.getConfig().getString("auction.bid-rotation", "off").toLowerCase(Locale.ROOT);
        List<String> items = plugin.getConfig().getStringList("auction.bid-items");
        if (items.isEmpty()) items = Arrays.asList("DIAMOND","EMERALD","NETHERITE_INGOT","NETHERITE_SCRAP","ANCIENT_DEBRIS","ENCHANTED_GOLDEN_APPLE");

        if (!rotation.equals("off")) {
            LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
            int idx = 0;
            if (rotation.equals("daily")) {
                idx = (now.getDayOfYear()) % items.size();
            } else if (rotation.equals("hourly")) {
                idx = (now.getDayOfYear() * 24 + now.getHour()) % items.size();
            }
            Material m = Material.matchMaterial(items.get(idx));
            if (m != null) return m;
        }

        // Бэкап-логика (если rotation=off): fixed | random
        String mode = plugin.getConfig().getString("auction.bid-item-mode", "random").toLowerCase(Locale.ROOT);
        if (mode.equals("fixed")) {
            String fixed = plugin.getConfig().getString("auction.fixed-bid-item", items.get(0));
            Material fm = Material.matchMaterial(fixed);
            return fm != null ? fm : Material.matchMaterial(items.get(0));
        } else {
            String pick = items.get(random.nextInt(items.size()));
            Material rm = Material.matchMaterial(pick);
            return rm != null ? rm : Material.DIAMOND;
        }
    }

    private String pretty(String key) {
        return key.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private ItemStack generateDiamondGear() {
        Material[] pool = new Material[] {
                Material.DIAMOND_SWORD, Material.DIAMOND_AXE, Material.DIAMOND_PICKAXE,
                Material.DIAMOND_SHOVEL, Material.DIAMOND_HELMET, Material.DIAMOND_CHESTPLATE,
                Material.DIAMOND_LEGGINGS, Material.DIAMOND_BOOTS, Material.DIAMOND_HOE,
                Material.BOW, Material.CROSSBOW
        };
        Material type = pool[random.nextInt(pool.length)];
        ItemStack it = new ItemStack(type, 1);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§b" + pretty(type.name()) + " §6[OP]");
            meta.setLore(Collections.singletonList("§7Алмазный предмет с усиленными чарами"));
            it.setItemMeta(meta);
        }
        if (type == Material.DIAMOND_SWORD) {
            it.addUnsafeEnchantment(Enchantment.SHARPNESS, 5);
            it.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
            it.addUnsafeEnchantment(Enchantment.MENDING, 1);
        } else if (type == Material.BOW) {
            it.addUnsafeEnchantment(Enchantment.POWER, 5);
            it.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
            it.addUnsafeEnchantment(Enchantment.MENDING, 1);
        } else if (type == Material.CROSSBOW) {
            it.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
            it.addUnsafeEnchantment(Enchantment.MENDING, 1);
        } else if (type.toString().contains("PICKAXE") || type.toString().contains("AXE") || type.toString().contains("SHOVEL") || type.toString().contains("HOE")) {
            it.addUnsafeEnchantment(Enchantment.EFFICIENCY, 5);
            it.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
            it.addUnsafeEnchantment(Enchantment.MENDING, 1);
            if (type != Material.DIAMOND_AXE) it.addUnsafeEnchantment(Enchantment.FORTUNE, 3);
        } else {
            it.addUnsafeEnchantment(Enchantment.PROTECTION, 4);
            it.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
            it.addUnsafeEnchantment(Enchantment.MENDING, 1);
        }
        return it;
    }

    private ItemStack createTrollLot() {
        ItemStack shulker = new ItemStack(Material.SHULKER_BOX);
        BlockStateMeta meta = (BlockStateMeta) shulker.getItemMeta();
        if (meta == null) return shulker;
        ShulkerBox box = (ShulkerBox) meta.getBlockState();

        ItemStack[] armor = {
                new ItemStack(Material.NETHERITE_HELMET),
                new ItemStack(Material.NETHERITE_CHESTPLATE),
                new ItemStack(Material.NETHERITE_LEGGINGS),
                new ItemStack(Material.NETHERITE_BOOTS)
        };
        for (ItemStack piece : armor) {
            ItemMeta pm = piece.getItemMeta();
            if (pm != null) {
                pm.setDisplayName("§6Сет Бога");
                pm.addEnchant(Enchantment.PROTECTION, 4, true);
                pm.addEnchant(Enchantment.UNBREAKING, 3, true);
                piece.setItemMeta(pm);
            }
            box.getInventory().addItem(piece);
        }
        box.getInventory().addItem(new ItemStack(Material.DIAMOND, 64));
        box.getInventory().addItem(new ItemStack(Material.NETHERITE_INGOT, 64));
        box.getInventory().addItem(new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 32));

        meta.setBlockState(box);
        shulker.setItemMeta(meta);
        return shulker;
    }

    // GUI

    private void openAuctionGui(Player player) {
        if (currentLot == null || currentBidItem == null) {
            player.sendMessage("§eСейчас аукцион не активен.");
            return;
        }
        Inventory inv = Bukkit.createInventory(null, INV_SIZE, auctionTitle());
        fillBorder(inv, Material.BLACK_STAINED_GLASS_PANE, Material.GRAY_STAINED_GLASS_PANE);

        // Лот в центре
        inv.setItem(LOT_SLOT, currentLot.clone());

        // Инфо
        ItemStack info = makeItem(Material.BOOK, "§aИнформация", Arrays.asList(
                "§7Валюта: §b" + currentBidItem.name(),
                "§7Мин. ставка: §6" + currentMinBid,
                "§7Текущий максимум: §f" + getMaxEffectiveBid(),
                "§7Ваша ставка: §f" + deposits.getOrDefault(player.getUniqueId(), 0),
                "§7До конца: §f" + formatDuration(getAuctionSecondsLeft()),
                "§8Используйте кнопки справа, чтобы повысить ставку"
        ));
        tag(info, "info", 0);
        inv.setItem(INFO_SLOT, info);

        // Предпросмотр
        ItemStack preview = makeItem(Material.ENDER_EYE, "§bПредпросмотр лота", Collections.singletonList("§7Открыть содержимое/детали"));
        tag(preview, "preview", 0);
        inv.setItem(PREVIEW_SLOT, preview);

        // Кнопка “next bid” = max+1
        int nextMin = Math.max(getMaxEffectiveBid() + 1, currentMinBid);
        ItemStack nextBid = makeItem(currentBidItem, "§aСделать ставку: §6" + nextMin, Collections.singletonList("§7Повыcить до следующего минимума"));
        tag(nextBid, "bid-next", nextMin);
        inv.setItem(NEXT_BID_SLOT, nextBid);

        // Инкременты
        inv.setItem(INC1_SLOT, makeIncButton(1));
        inv.setItem(INC5_SLOT, makeIncButton(5));
        inv.setItem(INC16_SLOT, makeIncButton(16));
        inv.setItem(INC64_SLOT, makeIncButton(64));

        // Топ-ставившие
        placeBidders(inv);

        // Добавим игрока в BossBar, если включен
        if (bossbarEnabled() && bossBar != null) {
            bossBar.addPlayer(player);
        }

        player.openInventory(inv);
    }

    private void placeBidders(Inventory inv) {
        List<Map.Entry<UUID, Integer>> list = new ArrayList<>(effectiveBids.entrySet());
        list.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

        int idx = 0;
        for (int slot : BIDDER_SLOTS) {
            if (idx >= list.size()) break;
            UUID uid = list.get(idx).getKey();
            int eff = list.get(idx).getValue();
            int dep = deposits.getOrDefault(uid, 0);

            OfflinePlayer off = Bukkit.getOfflinePlayer(uid);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            ItemMeta im = head.getItemMeta();
            if (im instanceof SkullMeta sm) {
                sm.setOwningPlayer(off);
                sm.setDisplayName("§f" + (off.getName() == null ? "Игрок" : off.getName()));
                sm.setLore(Arrays.asList(
                        "§7Ставка: §6" + eff + " §e" + currentBidItem.name(),
                        "§7Депозит: §f" + dep
                ));
                head.setItemMeta(sm);
            } else if (im != null) {
                im.setDisplayName("§f" + (off.getName() == null ? "Игрок" : off.getName()));
                im.setLore(Arrays.asList(
                        "§7Ставка: §6" + eff + " §e" + currentBidItem.name(),
                        "§7Депозит: §f" + dep
                ));
                head.setItemMeta(im);
            }
            inv.setItem(slot, head);
            idx++;
        }
    }

    private ItemStack makeIncButton(int inc) {
        ItemStack it = new ItemStack(currentBidItem);
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.setDisplayName("§a+ " + inc + " §e" + currentBidItem.name());
            im.setLore(Collections.singletonList("§7Попытаться повысить ставку на " + inc));
            it.setItemMeta(im);
        }
        tag(it, "bid-inc", inc);
        return it;
    }

    private void fillBorder(Inventory inv, Material border, Material background) {
        ItemStack borderPane = makePane(border);
        ItemStack backPane = makePane(background);

        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, backPane);

        int rows = inv.getSize() / 9;
        for (int c = 0; c < 9; c++) {
            inv.setItem(c, borderPane);
            inv.setItem((rows - 1) * 9 + c, borderPane);
        }
        for (int r = 0; r < rows; r++) {
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

    private void tag(ItemStack item, String type, int val) {
        ItemMeta im = item.getItemMeta();
        if (im == null) return;
        im.getPersistentDataContainer().set(KEY_TYPE, PersistentDataType.STRING, type);
        im.getPersistentDataContainer().set(KEY_VAL, PersistentDataType.INTEGER, val);
        item.setItemMeta(im);
    }

    private String getTagType(ItemStack item) {
        if (item == null) return null;
        ItemMeta im = item.getItemMeta();
        if (im == null) return null;
        return im.getPersistentDataContainer().get(KEY_TYPE, PersistentDataType.STRING);
    }

    private int getTagVal(ItemStack item) {
        if (item == null) return 0;
        ItemMeta im = item.getItemMeta();
        if (im == null) return 0;
        Integer v = im.getPersistentDataContainer().get(KEY_VAL, PersistentDataType.INTEGER);
        return v == null ? 0 : v;
    }

    // Команды

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) return true;
        if (!player.hasPermission("survivaltrader.ah")) {
            player.sendMessage("§cНет прав.");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("list") || args[0].equalsIgnoreCase("gui")) {
            openAuctionGui(player);
            return true;
        }

        if (args[0].equalsIgnoreCase("preview")) {
            openPreview(player);
            return true;
        }

        if (args[0].equalsIgnoreCase("bid") && args.length > 1) {
            if (checkAntiSpam(player, plugin.getConfig().getInt("auction.anti-spam-cooldown", 10))) return true;
            try {
                int amount = Integer.parseInt(args[1]);
                placeBid(player, amount);
                // обновим BossBar сразу
                updateBossBar();
            } catch (NumberFormatException e) {
                player.sendMessage("§cНеверное число!");
            }
            return true;
        }

        return false;
    }

    // Клики в GUI

    @EventHandler
    public void onAuctionClick(InventoryClickEvent e) {
        if (e.getView() == null || e.getView().getTitle() == null) return;
        if (!auctionTitle().equals(e.getView().getTitle())) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player player)) return;

        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        String type = getTagType(clicked);
        int val = getTagVal(clicked);

        if (type == null) return;
        if (checkAntiSpam(player, plugin.getConfig().getInt("auction.anti-spam-cooldown", 10))) return;

        switch (type) {
            case "preview" -> openPreview(player);
            case "bid-next" -> {
                int next = Math.max(getMaxEffectiveBid() + 1, currentMinBid);
                placeBid(player, next);
                updateBossBar();
                Bukkit.getScheduler().runTask(plugin, () -> openAuctionGui(player));
            }
            case "bid-inc" -> {
                int curr = deposits.getOrDefault(player.getUniqueId(), 0);
                int target = curr + Math.max(1, val);
                placeBid(player, target);
                updateBossBar();
                Bukkit.getScheduler().runTask(plugin, () -> openAuctionGui(player));
            }
            default -> {}
        }
    }

    @EventHandler
    public void onAuctionDrag(InventoryDragEvent e) {
        if (e.getView() == null || e.getView().getTitle() == null) return;
        if (auctionTitle().equals(e.getView().getTitle())) e.setCancelled(true);
    }

    // Предпросмотр

    private void openPreview(Player player) {
        if (currentLot == null) {
            player.sendMessage("§eСейчас аукцион не активен.");
            return;
        }
        String title = plugin.getConfig().getString("gui.auction-preview-title", "Auction Preview");

        if (currentLot.getType() == Material.SHULKER_BOX) {
            if (!(currentLot.getItemMeta() instanceof BlockStateMeta meta) || !(meta.getBlockState() instanceof ShulkerBox box)) {
                player.sendMessage("§eПредпросмотр недоступен.");
                return;
            }
            Inventory inv = Bukkit.createInventory(null, 54, title);
            fillBorder(inv, Material.BLACK_STAINED_GLASS_PANE, Material.GRAY_STAINED_GLASS_PANE);

            ItemStack[] contents = box.getInventory().getContents();
            int idx = 0;
            for (int r = 1; r <= 4; r++) {
                for (int c = 1; c <= 7; c++) {
                    int slot = r * 9 + c;
                    if (idx < contents.length && contents[idx] != null) inv.setItem(slot, contents[idx].clone());
                    idx++;
                }
            }

            ItemStack info = makeItem(Material.PAPER, "§aИнформация", Arrays.asList(
                    "§7Лот: §bШалкер с сетом",
                    "§7Валюта: §b" + currentBidItem.name(),
                    "§7Мин. ставка: §6" + currentMinBid,
                    "§7Текущий максимум: §f" + getMaxEffectiveBid(),
                    "§7До конца: §f" + formatDuration(getAuctionSecondsLeft())
            ));
            inv.setItem(49, info);
            player.openInventory(inv);
        } else {
            Inventory inv = Bukkit.createInventory(null, 27, title);
            fillBorder(inv, Material.BLACK_STAINED_GLASS_PANE, Material.GRAY_STAINED_GLASS_PANE);

            inv.setItem(13, currentLot.clone());
            ItemStack info = makeItem(Material.PAPER, "§aИнформация", Arrays.asList(
                    "§7Лот: §b" + (currentLot.hasItemMeta() && currentLot.getItemMeta().hasDisplayName()
                            ? currentLot.getItemMeta().getDisplayName() : currentLot.getType().name()),
                    "§7Валюта: §b" + currentBidItem.name(),
                    "§7Мин. ставка: §6" + currentMinBid,
                    "§7Текущий максимум: §f" + getMaxEffectiveBid(),
                    "§7До конца: §f" + formatDuration(getAuctionSecondsLeft())
            ));
            inv.setItem(22, info);
            player.openInventory(inv);
        }
    }

    // Логика ставок

    private void placeBid(Player player, int amount) {
        if (amount <= 0) {
            player.sendMessage("§cСтавка должна быть > 0.");
            return;
        }
        if (currentBidItem == null || currentLot == null) {
            player.sendMessage("§cАукцион ещё не запущен.");
            return;
        }

        int count = playerBidCounts.getOrDefault(player.getUniqueId(), 0);
        if (count >= plugin.getConfig().getInt("auction.max-bids-per-player", 999)) {
            player.sendMessage(plugin.getConfig().getString("messages.limit-reached", "Лимит достигнут!"));
            return;
        }

        int prevDeposit = deposits.getOrDefault(player.getUniqueId(), 0);
        if (amount < prevDeposit) {
            player.sendMessage(plugin.getConfig().getString("messages.cannot-decrease-bid", "Нельзя уменьшать свою ставку."));
            return;
        }

        int currentMax = getMaxEffectiveBid();
        if (amount <= currentMax || amount < currentMinBid) {
            int minNeed = Math.max(currentMinBid, currentMax + 1);
            player.sendMessage(plugin.getConfig().getString("messages.bid-too-low", "Ставка мала! Минимум: %min%")
                    .replace("%min%", String.valueOf(minNeed)));
            return;
        }

        int delta = amount - prevDeposit;
        if (delta > 0) {
            if (!player.getInventory().contains(currentBidItem, delta)) {
                player.sendMessage(plugin.getConfig().getString("messages.no-items", "Недостаточно %biditem% (нужно %price%)!")
                        .replace("%biditem%", currentBidItem.name()).replace("%price%", String.valueOf(delta)));
                return;
            }
            player.getInventory().removeItem(new ItemStack(currentBidItem, delta));
        }

        deposits.put(player.getUniqueId(), amount);
        effectiveBids.put(player.getUniqueId(), amount);
        playerBidCounts.put(player.getUniqueId(), count + 1);

        player.sendMessage(plugin.getConfig().getString("messages.bid-success", "Ставка принята!")
                .replace("%effective%", String.valueOf(amount)));
    }

    private int getMaxEffectiveBid() {
        return effectiveBids.values().stream().max(Integer::compareTo).orElse(0);
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

    private void showStatus(Player player) {
        if (currentLot == null || currentBidItem == null) { player.sendMessage("§eСейчас аукцион не активен."); return; }
        String lotShown = (currentLot.hasItemMeta() && currentLot.getItemMeta().hasDisplayName())
                ? currentLot.getItemMeta().getDisplayName() : currentLot.getType().name();
        player.sendMessage("§aЛот: " + lotShown);
        player.sendMessage("§aСтавки в: " + currentBidItem.name() + " (мин. " + currentMinBid + ", макс: " + getMaxEffectiveBid() + ")");
        player.sendMessage(plugin.getConfig().getString("messages.auction-timeleft", "До конца аукциона: %time%.")
                .replace("%time%", formatDuration(getAuctionSecondsLeft())));
    }

    private long getAuctionSecondsLeft() {
        long dur = plugin.getConfig().getLong("auction.duration", 300);
        if (lastAuctionStart <= 0) return dur;
        long elapsed = (System.currentTimeMillis() - lastAuctionStart) / 1000L;
        return Math.max(0, dur - elapsed);
    }

    public void endAuction(boolean announceIfEmpty) {
        if (currentLot == null || currentBidItem == null) {
            clearBossBar();
            return;
        }

        if (effectiveBids.isEmpty()) {
            if (announceIfEmpty) Bukkit.broadcastMessage(plugin.getConfig().getString("messages.no-bids", "Аукцион завершён без ставок."));
            returnDepositsToAll();
            clearBossBar();
            saveData(); plugin.saveDataConfig();
            return;
        }

        if (isTroll) {
            Bukkit.broadcastMessage(plugin.getConfig().getString("messages.auction-end-troll", "Тролль! Ставки возвращены."));
            returnDepositsToAll();
            clearBossBar();
            saveData(); plugin.saveDataConfig();
            return;
        }

        UUID winner = null; int maxEff = 0;
        for (Map.Entry<UUID, Integer> e : effectiveBids.entrySet()) {
            if (e.getValue() > maxEff) { maxEff = e.getValue(); winner = e.getKey(); }
        }

        boolean persistOffline = plugin.getConfig().getBoolean("auction.persist-returns-for-offline-losers", true);

        for (Map.Entry<UUID, Integer> e : deposits.entrySet()) {
            UUID uid = e.getKey();
            if (winner != null && uid.equals(winner)) continue;
            int dep = e.getValue(); if (dep <= 0) continue;
            Player p = Bukkit.getPlayer(uid);
            if (p != null) {
                p.getInventory().addItem(new ItemStack(currentBidItem, dep));
                p.sendMessage(plugin.getConfig().getString("messages.returned", "Возвращено: %amount% %biditem%.")
                        .replace("%amount%", String.valueOf(dep)).replace("%biditem%", currentBidItem.name()));
            } else if (persistOffline) {
                pendingReturns.computeIfAbsent(uid, k -> new HashMap<>())
                        .merge(currentBidItem.name(), dep, Integer::sum);
            }
        }

        if (winner != null) {
            String lotName = (currentLot.hasItemMeta() && currentLot.getItemMeta().hasDisplayName())
                    ? currentLot.getItemMeta().getDisplayName() : currentLot.getType().name();

            Player w = Bukkit.getPlayer(winner);
            if (w != null) {
                HashMap<Integer, ItemStack> overflow = w.getInventory().addItem(currentLot.clone());
                if (!overflow.isEmpty()) {
                    pendingRewards.computeIfAbsent(winner, k -> new ArrayList<>()).add(currentLot.clone());
                    w.sendMessage(plugin.getConfig().getString("messages.reward-saved", "Ваш приз сохранён и будет выдан при входе."));
                }
                Bukkit.broadcastMessage(plugin.getConfig().getString("messages.auction-end",
                        "Аукцион завершён! Победитель: %winner% с %amount% %biditem%. Лот: %lot%.")
                        .replace("%winner%", w.getName())
                        .replace("%amount%", String.valueOf(maxEff))
                        .replace("%biditem%", currentBidItem.name())
                        .replace("%lot%", lotName));
            } else {
                pendingRewards.computeIfAbsent(winner, k -> new ArrayList<>()).add(currentLot.clone());
                String name = Bukkit.getOfflinePlayer(winner).getName();
                Bukkit.broadcastMessage(plugin.getConfig().getString("messages.auction-end",
                        "Аукцион завершён! Победитель: %winner% с %amount% %biditem%. Лот: %lot%.")
                        .replace("%winner%", name == null ? "Игрок" : name)
                        .replace("%amount%", String.valueOf(maxEff))
                        .replace("%biditem%", currentBidItem.name())
                        .replace("%lot%", lotName));
            }
        }

        clearBossBar();
        saveData(); plugin.saveDataConfig();
    }

    private void returnDepositsToAll() {
        boolean persistOffline = plugin.getConfig().getBoolean("auction.persist-returns-for-offline-losers", true);
        for (Map.Entry<UUID, Integer> e : deposits.entrySet()) {
            UUID uid = e.getKey(); int dep = e.getValue(); if (dep <= 0) continue;
            Player p = Bukkit.getPlayer(uid);
            if (p != null) {
                p.getInventory().addItem(new ItemStack(currentBidItem, dep));
                p.sendMessage(plugin.getConfig().getString("messages.returned", "Возвращено: %amount% %biditem%.")
                        .replace("%amount%", String.valueOf(dep)).replace("%biditem%", currentBidItem.name()));
            } else if (persistOffline) {
                pendingReturns.computeIfAbsent(uid, k -> new HashMap<>())
                        .merge(currentBidItem.name(), dep, Integer::sum);
            }
        }
    }

    // Join: награды и возвраты + BossBar для новых игроков

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        UUID id = e.getPlayer().getUniqueId();

        // BossBar: если активен — добавить игрока
        if (bossbarEnabled() && bossBar != null) {
            bossBar.addPlayer(e.getPlayer());
        }

        // Награды
        List<ItemStack> list = pendingRewards.get(id);
        if (list != null && !list.isEmpty()) {
            Iterator<ItemStack> it = list.iterator();
            while (it.hasNext()) {
                ItemStack reward = it.next();
                HashMap<Integer, ItemStack> overflow = e.getPlayer().getInventory().addItem(reward.clone());
                if (overflow.isEmpty()) {
                    it.remove();
                } else {
                    break;
                }
            }
            if (list.isEmpty()) {
                pendingRewards.remove(id);
                e.getPlayer().sendMessage(plugin.getConfig().getString("messages.reward-claimed", "Вы получили сохранённый приз с аукциона!"));
            } else {
                pendingRewards.put(id, list);
            }
        }

        // Возвраты
        Map<String, Integer> returns = pendingReturns.get(id);
        if (returns != null && !returns.isEmpty()) {
            Iterator<Map.Entry<String, Integer>> it = returns.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Integer> en = it.next();
                String matName = en.getKey();
                int amt = en.getValue();
                Material mat = Material.matchMaterial(matName);
                if (mat == null || amt <= 0) { it.remove(); continue; }

                int maxStack = mat.getMaxStackSize();
                int left = amt;
                while (left > 0) {
                    int give = Math.min(left, maxStack);
                    ItemStack stack = new ItemStack(mat, give);
                    HashMap<Integer, ItemStack> overflow = e.getPlayer().getInventory().addItem(stack);
                    int notAdded = 0; for (ItemStack s : overflow.values()) notAdded += s.getAmount();
                    int delivered = give - notAdded;

                    if (delivered > 0) {
                        e.getPlayer().sendMessage(plugin.getConfig().getString("messages.returned", "Возвращено: %amount% %biditem%.")
                                .replace("%amount%", String.valueOf(delivered)).replace("%biditem%", mat.name()));
                    }

                    left -= delivered;
                    if (delivered == 0) break;
                }

                if (left <= 0) it.remove();
                else { en.setValue(left); break; }
            }
            if (returns.isEmpty()) pendingReturns.remove(id);
        }

        saveData(); plugin.saveDataConfig();
    }

    // Сохранение/загрузка

    public void saveData() {
        FileConfiguration data = plugin.getDataConfig();

        Map<String, Integer> eff = new HashMap<>();
        effectiveBids.forEach((k, v) -> eff.put(k.toString(), v));
        data.set("auction.effectiveBids", eff);

        Map<String, Integer> dep = new HashMap<>();
        deposits.forEach((k, v) -> dep.put(k.toString(), v));
        data.set("auction.deposits", dep);

        Map<String, Integer> cnt = new HashMap<>();
        playerBidCounts.forEach((k, v) -> cnt.put(k.toString(), v));
        data.set("auction.playerBidCounts", cnt);

        data.set("auction.isTroll", isTroll);
        data.set("auction.currentBidItem", currentBidItem == null ? null : currentBidItem.name());
        data.set("auction.currentMinBid", currentMinBid);
        data.set("auction.currentLot", currentLot == null ? null : currentLot.serialize());
        data.set("auction.lastAuctionStart", lastAuctionStart);

        Map<String, Object> rewards = new HashMap<>();
        for (Map.Entry<UUID, List<ItemStack>> e : pendingRewards.entrySet()) {
            List<Map<String, Object>> ser = new ArrayList<>();
            for (ItemStack it : e.getValue()) ser.add(it.serialize());
            rewards.put(e.getKey().toString(), ser);
        }
        data.set("auction.pendingRewards", rewards);

        Map<String, Object> returns = new HashMap<>();
        for (Map.Entry<UUID, Map<String, Integer>> e : pendingReturns.entrySet()) {
            returns.put(e.getKey().toString(), new HashMap<>(e.getValue()));
        }
        data.set("auction.pendingReturns", returns);
    }

    @SuppressWarnings("unchecked")
    public void loadData() {
        FileConfiguration data = plugin.getDataConfig();

        effectiveBids.clear();
        Object eff = data.get("auction.effectiveBids");
        if (eff instanceof Map<?, ?> m) {
            m.forEach((k, v) -> {
                try { effectiveBids.put(UUID.fromString(String.valueOf(k)), Integer.parseInt(String.valueOf(v))); } catch (Exception ignored) {}
            });
        }

        deposits.clear();
        Object dep = data.get("auction.deposits");
        if (dep instanceof Map<?, ?> m2) {
            m2.forEach((k, v) -> {
                try { deposits.put(UUID.fromString(String.valueOf(k)), Integer.parseInt(String.valueOf(v))); } catch (Exception ignored) {}
            });
        }

        playerBidCounts.clear();
        Object cnt = data.get("auction.playerBidCounts");
        if (cnt instanceof Map<?, ?> m3) {
            m3.forEach((k, v) -> {
                try { playerBidCounts.put(UUID.fromString(String.valueOf(k)), Integer.parseInt(String.valueOf(v))); } catch (Exception ignored) {}
            });
        }

        isTroll = data.getBoolean("auction.isTroll", false);
        String bidItemStr = data.getString("auction.currentBidItem");
        currentBidItem = (bidItemStr == null) ? null : Material.matchMaterial(bidItemStr);
        currentMinBid = data.getInt("auction.currentMinBid", 1);

        currentLot = null;
        if (data.isConfigurationSection("auction.currentLot")) {
            Map<String, Object> lotMap = data.getConfigurationSection("auction.currentLot").getValues(false);
            if (lotMap != null && !lotMap.isEmpty()) {
                try { currentLot = ItemStack.deserialize(lotMap); } catch (Exception ignored) {}
            }
        }

        lastAuctionStart = data.getLong("auction.lastAuctionStart", 0L);

        pendingRewards.clear();
        if (data.isConfigurationSection("auction.pendingRewards")) {
            data.getConfigurationSection("auction.pendingRewards").getValues(false).forEach((k, v) -> {
                try {
                    UUID id = UUID.fromString(k);
                    List<ItemStack> list = new ArrayList<>();
                    if (v instanceof List<?> l) {
                        for (Object o : l) {
                            if (o instanceof Map<?, ?> map) {
                                try {
                                    ItemStack it = ItemStack.deserialize((Map<String, Object>) map);
                                    list.add(it);
                                } catch (Exception ignored) {}
                            }
                        }
                    }
                    if (!list.isEmpty()) pendingRewards.put(id, list);
                } catch (Exception ignored) {}
            });
        }

        pendingReturns.clear();
        if (data.isConfigurationSection("auction.pendingReturns")) {
            data.getConfigurationSection("auction.pendingReturns").getValues(false).forEach((k, v) -> {
                try {
                    UUID id = UUID.fromString(k);
                    Map<String, Integer> inner = new HashMap<>();
                    if (v instanceof Map<?, ?> map) {
                        map.forEach((mk, mv) -> {
                            try { inner.put(String.valueOf(mk), Integer.parseInt(String.valueOf(mv))); } catch (Exception ignored) {}
                        });
                    }
                    if (!inner.isEmpty()) pendingReturns.put(id, inner);
                } catch (Exception ignored) {}
            });
        }
    }

    // Tab-complete
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!"ah".equalsIgnoreCase(command.getName())) return Collections.emptyList();
        if (args.length == 1) {
            return StringUtil.copyPartialMatches(args[0], Arrays.asList("list", "gui", "preview", "bid"), new ArrayList<>());
        }
        if (args.length == 2 && "bid".equalsIgnoreCase(args[0])) {
            int min = Math.max(currentMinBid, getMaxEffectiveBid() + 1);
            List<String> nums = Arrays.asList(String.valueOf(min), String.valueOf(min + 5), String.valueOf(min + 10));
            return StringUtil.copyPartialMatches(args[1], nums, new ArrayList<>());
        }
        return Collections.emptyList();
    }

    // BossBar

    private boolean bossbarEnabled() {
        return plugin.getConfig().getBoolean("auction.bossbar.enabled", true);
    }

    private BarColor bossbarColor() {
        String c = plugin.getConfig().getString("auction.bossbar.color", "GREEN").toUpperCase(Locale.ROOT);
        try { return BarColor.valueOf(c); } catch (Exception ignored) { return BarColor.GREEN; }
    }

    private BarStyle bossbarStyle() {
        String s = plugin.getConfig().getString("auction.bossbar.style", "SEGMENTED_10").toUpperCase(Locale.ROOT);
        try { return BarStyle.valueOf(s); } catch (Exception ignored) { return BarStyle.SOLID; }
    }

    private void ensureBossBarCreated() {
        String title = bossbarTitle();
        if (bossBar == null) {
            bossBar = Bukkit.createBossBar(title, bossbarColor(), bossbarStyle());
        } else {
            bossBar.setTitle(title);
            bossBar.setColor(bossbarColor());
            bossBar.setStyle(bossbarStyle());
        }
        // показать всем игрокам (можно сделать режим показа по желанию)
        for (Player p : Bukkit.getOnlinePlayers()) bossBar.addPlayer(p);
        bossBar.setVisible(true);
    }

    private String bossbarTitle() {
        String tpl = plugin.getConfig().getString("auction.bossbar.title", "Аукцион: %lot% | %biditem% | макс %max% | %left%");
        String lotShown = (currentLot != null && currentLot.hasItemMeta() && currentLot.getItemMeta().hasDisplayName())
                ? currentLot.getItemMeta().getDisplayName()
                : (currentLot == null ? "—" : currentLot.getType().name());
        return tpl
                .replace("%lot%", lotShown)
                .replace("%biditem%", currentBidItem == null ? "—" : currentBidItem.name())
                .replace("%max%", String.valueOf(getMaxEffectiveBid()))
                .replace("%left%", formatDuration(getAuctionSecondsLeft()));
    }

    private void updateBossBar() {
        if (!bossbarEnabled() || bossBar == null) return;
        bossBar.setTitle(bossbarTitle());
        long dur = Math.max(1, plugin.getConfig().getLong("auction.duration", 300));
        double left = getAuctionSecondsLeft();
        double progress = Math.max(0.0, Math.min(1.0, left / dur));
        bossBar.setProgress(progress);

        // цвет по прогрессу
        if (progress > 0.5) bossBar.setColor(BarColor.GREEN);
        else if (progress > 0.2) bossBar.setColor(BarColor.YELLOW);
        else bossBar.setColor(BarColor.RED);
    }

    private void startBossBarTask() {
        stopBossBarTask();
        bossbarTask = Bukkit.getScheduler().runTaskTimer(plugin, this::updateBossBar, 20L, 20L);
    }

    private void stopBossBarTask() {
        if (bossbarTask != null) {
            bossbarTask.cancel();
            bossbarTask = null;
        }
    }

    public void clearBossBar() {
        stopBossBarTask();
        if (bossBar != null) {
            bossBar.removeAll();
            bossBar.setVisible(false);
            bossBar = null;
        }
    }

    private static String formatDuration(long seconds) {
        long h = seconds / 3600; seconds %= 3600;
        long m = seconds / 60; long s = seconds % 60;
        if (h > 0) return String.format("%dч %02dм %02dс", h, m, s);
        if (m > 0) return String.format("%dм %02dс", m, s);
        return s + "с";
    }
}
