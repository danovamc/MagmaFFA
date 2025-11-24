package us.magmamc.magmaFFA;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

public class FFALevelingSystem implements Listener {
    private final JavaPlugin plugin;
    private File levelConfigFile;
    private FileConfiguration levelConfig;
    private File playerDataFile;
    private FileConfiguration playerData;
    private final Map<Integer, Integer> xpRequirements = new HashMap<>();
    private final Map<Integer, List<String>> levelRewards = new HashMap<>();
    private final Map<UUID, PlayerLevelData> playerLevels = new HashMap<>();
    private final Map<UUID, Map<UUID, Long>> killCooldowns = new ConcurrentHashMap<>();
    private final Random random = new Random();
    private final int COOLDOWN_SECONDS = 150;
    private final String PREFIX;

    public FFALevelingSystem(JavaPlugin plugin, String prefix) {
        this.plugin = plugin;
        this.PREFIX = prefix;
        this.setupXpRequirements();
        this.loadLevelConfig();
        this.loadPlayerData();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::savePlayerData, 6000L, 6000L);
    }

    private void setupXpRequirements() {
        this.xpRequirements.put(1, 0);

        for(int level = 2; level <= 10; ++level) {
            this.xpRequirements.put(level, (level - 1) * 50);
        }

        for(int level = 11; level <= 25; ++level) {
            this.xpRequirements.put(level, this.xpRequirements.get(10) + (level - 10) * 150);
        }

    }

    private void loadLevelConfig() {
        this.levelConfigFile = new File(this.plugin.getDataFolder(), "levels.yml");
        if (!this.levelConfigFile.exists()) {
            this.levelConfigFile.getParentFile().mkdirs();
            this.plugin.saveResource("levels.yml", false);
        }

        this.levelConfig = YamlConfiguration.loadConfiguration(this.levelConfigFile);
        if (!this.levelConfig.contains("levels")) {
            this.setupDefaultLevelConfig();
            this.saveLevelConfig();
        }

        ConfigurationSection levelsSection = this.levelConfig.getConfigurationSection("levels");
        if (levelsSection != null) {
            for(String levelKey : levelsSection.getKeys(false)) {
                try {
                    int level = Integer.parseInt(levelKey);
                    List<String> rewards = levelsSection.getStringList(levelKey + ".rewards");
                    this.levelRewards.put(level, rewards);
                } catch (NumberFormatException var6) {
                    this.plugin.getLogger().warning("Invalid level in levels.yml: " + levelKey);
                }
            }
        }

    }

    private void setupDefaultLevelConfig() {
        for(int level = 1; level <= 25; ++level) {
            List<String> defaultRewards = new ArrayList<>();
            defaultRewards.add("say " + this.PREFIX + "¡El jugador %player% ha alcanzado el nivel " + level + "!");
            if (level % 5 == 0) {
                defaultRewards.add("give %player% diamond 1");
            }

            if (level == 25) {
                defaultRewards.add("give %player% netherite_ingot 1");
                defaultRewards.add("broadcast " + this.PREFIX + "¡%player% ha alcanzado el nivel máximo 25 en FFA!");
            }

            this.levelConfig.set("levels." + level + ".rewards", defaultRewards);
            this.levelConfig.set("levels." + level + ".xp_required", this.xpRequirements.get(level));
        }

    }

    private void saveLevelConfig() {
        try {
            this.levelConfig.save(this.levelConfigFile);
        } catch (IOException e) {
            this.plugin.getLogger().severe("Could not save levels config: " + e.getMessage());
        }

    }

    private void loadPlayerData() {
        this.playerDataFile = new File(this.plugin.getDataFolder(), "playerlevels.yml");
        if (!this.playerDataFile.exists()) {
            this.playerDataFile.getParentFile().mkdirs();

            try {
                this.playerDataFile.createNewFile();
            } catch (IOException e) {
                this.plugin.getLogger().severe("Could not create playerlevels.yml: " + e.getMessage());
            }
        }

        this.playerData = YamlConfiguration.loadConfiguration(this.playerDataFile);
        ConfigurationSection playersSection = this.playerData.getConfigurationSection("players");
        if (playersSection != null) {
            for(String uuidStr : playersSection.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(uuidStr);
                    int level = playersSection.getInt(uuidStr + ".level", 1);
                    int xp = playersSection.getInt(uuidStr + ".xp", 0);
                    this.playerLevels.put(uuid, new PlayerLevelData(level, xp));
                } catch (IllegalArgumentException var7) {
                    this.plugin.getLogger().warning("Invalid UUID in playerlevels.yml: " + uuidStr);
                }
            }
        }

    }

    public void savePlayerData() {
        this.playerData.set("players", null);

        for(Map.Entry<UUID, PlayerLevelData> entry : this.playerLevels.entrySet()) {
            String uuidStr = entry.getKey().toString();
            PlayerLevelData data = entry.getValue();
            this.playerData.set("players." + uuidStr + ".level", data.getLevel());
            this.playerData.set("players." + uuidStr + ".xp", data.getXp());
        }

        try {
            this.playerData.save(this.playerDataFile);
        } catch (IOException e) {
            this.plugin.getLogger().severe("Could not save player level data: " + e.getMessage());
        }

    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        killCooldowns.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer != null && !killer.equals(victim)) {
            UUID killerUUID = killer.getUniqueId();
            UUID victimUUID = victim.getUniqueId();
            if (this.isOnCooldown(killerUUID, victimUUID)) {
                long timeLeft = this.getRemainingCooldown(killerUUID, victimUUID);
                killer.sendMessage(this.PREFIX + "Cooldown activo para " + victim.getName() + ". Espera " + this.formatTime(timeLeft) + " para ganar XP.");
                return;
            }

            int xpGained = this.random.nextInt(16) + 5;
            this.addXp(killer, xpGained);
            this.setKillCooldown(killerUUID, victimUUID);
            killer.sendMessage(this.PREFIX + "Has ganado §e" + xpGained + " XP§f por eliminar a " + victim.getName());
        }

    }

    public void addXp(Player player, int amount) {
        UUID uuid = player.getUniqueId();
        PlayerLevelData data = this.playerLevels.getOrDefault(uuid, new PlayerLevelData(1, 0));
        int currentLevel = data.getLevel();
        int currentXp = data.getXp();
        if (currentLevel >= 25) {
            player.sendMessage(this.PREFIX + "Ya estás en el nivel máximo.");
        } else {
            data.setXp(currentXp + amount);
            this.checkLevelUp(player, data);
            this.playerLevels.put(uuid, data);
        }
    }

    private void checkLevelUp(Player player, PlayerLevelData data) {
        int currentLevel = data.getLevel();
        int currentXp = data.getXp();
        if (currentLevel < 25) {
            int nextLevel = currentLevel + 1;
            int requiredXp = this.xpRequirements.get(nextLevel);
            if (currentXp >= requiredXp) {
                data.setLevel(nextLevel);
                player.sendMessage(this.PREFIX + "§a¡Has subido al nivel " + nextLevel + "!");
                this.giveRewards(player, nextLevel);
                this.checkLevelUp(player, data);
            }

        }
    }

    private void giveRewards(Player player, int level) {
        List<String> rewards = this.levelRewards.get(level);
        if (rewards != null && !rewards.isEmpty()) {
            for(String command : rewards) {
                command = command.replace("%player%", player.getName());
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            }
        }

    }

    private void setKillCooldown(UUID killerUUID, UUID victimUUID) {
        this.killCooldowns.computeIfAbsent(killerUUID, (k) -> new ConcurrentHashMap<>()).put(victimUUID, System.currentTimeMillis());
    }

    private boolean isOnCooldown(UUID killerUUID, UUID victimUUID) {
        Map<UUID, Long> victimCooldowns = this.killCooldowns.get(killerUUID);
        if (victimCooldowns != null && victimCooldowns.containsKey(victimUUID)) {
            long lastKillTime = victimCooldowns.get(victimUUID);
            long currentTime = System.currentTimeMillis();
            return currentTime - lastKillTime < (this.COOLDOWN_SECONDS * 1000L);
        } else {
            return false;
        }
    }

    private long getRemainingCooldown(UUID killerUUID, UUID victimUUID) {
        Map<UUID, Long> victimCooldowns = this.killCooldowns.get(killerUUID);
        if (victimCooldowns != null && victimCooldowns.containsKey(victimUUID)) {
            long lastKillTime = victimCooldowns.get(victimUUID);
            long currentTime = System.currentTimeMillis();
            long elapsedMillis = currentTime - lastKillTime;
            long cooldownMillis = this.COOLDOWN_SECONDS * 1000L;
            return elapsedMillis >= cooldownMillis ? 0L : (cooldownMillis - elapsedMillis) / 1000L;
        } else {
            return 0L;
        }
    }

    private String formatTime(long seconds) {
        long minutes = seconds / 60L;
        long remainingSeconds = seconds % 60L;
        return minutes + "m " + remainingSeconds + "s";
    }

    public int getPlayerLevel(UUID uuid) {
        PlayerLevelData data = this.playerLevels.get(uuid);
        return data != null ? data.getLevel() : 1;
    }

    public int getPlayerXp(UUID uuid) {
        PlayerLevelData data = this.playerLevels.get(uuid);
        return data != null ? data.getXp() : 0;
    }

    public int getXpRequiredForNextLevel(UUID uuid) {
        PlayerLevelData data = this.playerLevels.get(uuid);
        if (data == null) {
            return this.xpRequirements.get(2);
        } else {
            int nextLevel = data.getLevel() + 1;
            return nextLevel > 25 ? 0 : this.xpRequirements.get(nextLevel);
        }
    }

    public int getProgressPercentage(UUID uuid) {
        PlayerLevelData data = this.playerLevels.get(uuid);
        if (data == null) {
            return 0;
        } else {
            int currentLevel = data.getLevel();
            if (currentLevel >= 25) {
                return 100;
            } else {
                int currentXp = data.getXp();
                int nextLevel = currentLevel + 1;
                int requiredXp = this.xpRequirements.get(nextLevel);
                int previousLevelXp = this.xpRequirements.get(currentLevel);
                int xpForThisLevel = requiredXp - previousLevelXp;
                int xpProgress = currentXp - previousLevelXp;
                return (int)((double)xpProgress / (double)xpForThisLevel * 100.0D);
            }
        }
    }

    public String getProgressBar(UUID uuid, int length, String completeChar, String incompleteChar) {
        int percent = this.getProgressPercentage(uuid);
        int completeBars = (int)((double)percent / 100.0D * (double)length);
        StringBuilder sb = new StringBuilder();

        for(int i = 0; i < length; ++i) {
            if (i < completeBars) {
                sb.append(completeChar);
            } else {
                sb.append(incompleteChar);
            }
        }

        return sb.toString();
    }

    public String getPlaceholder(UUID uuid, String identifier) {
        PlayerLevelData data = this.playerLevels.getOrDefault(uuid, new PlayerLevelData(1, 0));
        switch (identifier.toLowerCase()) {
            case "level":
                return String.valueOf(data.getLevel());
            case "xp":
                return String.valueOf(data.getXp());
            case "next_level_xp": { // Agregamos llaves {} aquí
                int nextLevel = data.getLevel() + 1;
                if (nextLevel > 25) {
                    return "MAX";
                }
                return String.valueOf(this.xpRequirements.get(nextLevel));
            }
            case "progress_percent":
                return String.valueOf(this.getProgressPercentage(uuid));
            case "progress_bar":
                return this.getProgressBar(uuid, 10, "§a|", "§7|");
            case "xp_needed": { // Agregamos llaves {} aquí también
                int nextLevel = data.getLevel() + 1;
                if (nextLevel > 25) {
                    return "0";
                }
                int needed = this.xpRequirements.get(nextLevel) - data.getXp();
                return String.valueOf(Math.max(0, needed));
            }
            default:
                return null;
        }
    }

    private static class PlayerLevelData {
        private int level;
        private int xp;

        public PlayerLevelData(int level, int xp) {
            this.level = level;
            this.xp = xp;
        }

        public int getLevel() {
            return this.level;
        }

        public void setLevel(int level) {
            this.level = level;
        }

        public int getXp() {
            return this.xp;
        }

        public void setXp(int xp) {
            this.xp = xp;
        }
    }
}