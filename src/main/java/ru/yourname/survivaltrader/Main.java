package ru.yourname.survivaltrader;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;

public class Main extends JavaPlugin {

    private ShopManager shopManager;
    private AuctionManager auctionManager;

    private File dataFile;
    private FileConfiguration data;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            try { dataFile.createNewFile(); } catch (IOException e) {
                getLogger().severe("Failed to create data.yml: " + e.getMessage());
            }
        }
        data = YamlConfiguration.loadConfiguration(dataFile);

        shopManager = new ShopManager(this);
        auctionManager = new AuctionManager(this);

        getServer().getPluginManager().registerEvents(shopManager, this);
        getServer().getPluginManager().registerEvents(auctionManager, this);

        if (getCommand("shop") != null) getCommand("shop").setExecutor(shopManager);
        if (getCommand("ah") != null) {
            getCommand("ah").setExecutor(auctionManager);
            getCommand("ah").setTabCompleter(auctionManager);
        }

        shopManager.loadData();
        auctionManager.loadData();

        shopManager.startUpdateTimer();
        auctionManager.startAuctionTimer();

        getLogger().info("SurvivalTrader enabled.");
    }

    @Override
    public void onDisable() {
        try {
            shopManager.saveData();
            auctionManager.saveData();
            data.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("Failed to save data.yml: " + e.getMessage());
        }
        getLogger().info("SurvivalTrader disabled.");
    }

    public FileConfiguration getDataConfig() {
        return data;
    }

    public void saveDataConfig() {
        try {
            data.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("Failed to save data.yml: " + e.getMessage());
        }
    }

    public ShopManager getShopManager() {
        return shopManager;
    }

    public AuctionManager getAuctionManager() {
        return auctionManager;
    }
}