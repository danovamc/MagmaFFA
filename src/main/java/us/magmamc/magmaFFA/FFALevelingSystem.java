package us.magmamc.magmaFFA;

import java.io.File;
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
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class FFALevelingSystem implements Listener {
    private final MagmaFFA plugin;
    private File levelConfigFile;
    private FileConfiguration levelConfig;

    private final Map<Integer, Integer> xpRequirements = new HashMap<>();
    private final Map<Integer, List<String>> levelRewards = new HashMap<>();
    private final Map<UUID, Map<UUID, Long>> killCooldowns = new ConcurrentHashMap<>();
    private final Random random = new Random();
    private final int COOLDOWN_SECONDS = 150;
    private final String PREFIX;

    public FFALevelingSystem(MagmaFFA plugin, String prefix) {
        this.plugin = plugin;
        this.PREFIX = prefix;
        this.setupXpRequirements();
        this.loadLevelConfig();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    // --- NUEVO MÉTODO PARA RELOAD ---
    public void reloadConfig() {
        this.levelRewards.clear();
        // xpRequirements se mantiene igual porque está "hardcoded" en setupXpRequirements,
        // pero recargamos rewards y el archivo.
        this.loadLevelConfig();
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
            try { this.levelConfig.save(this.levelConfigFile); } catch (Exception e) { e.printStackTrace(); }
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
            if (level % 5 == 0) defaultRewards.add("give %player% diamond 1");
            if (level == 25) {
                defaultRewards.add("give %player% netherite_ingot 1");
                defaultRewards.add("broadcast " + this.PREFIX + "¡%player% ha alcanzado el nivel máximo 25 en FFA!");
            }
            this.levelConfig.set("levels." + level + ".rewards", defaultRewards);
            this.levelConfig.set("levels." + level + ".xp_required", this.xpRequirements.get(level));
        }
    }

    // --- EVENTOS OPTIMIZADOS ---

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        plugin.getPlayerManager().loadPlayer(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        killCooldowns.remove(e.getPlayer().getUniqueId());
        plugin.getPlayerManager().saveAndUnloadPlayer(e.getPlayer().getUniqueId());
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
        int currentLevel = plugin.getPlayerManager().getLevel(uuid);
        int currentXp = plugin.getPlayerManager().getXp(uuid);

        if (currentLevel >= 25) {
            player.sendMessage(this.PREFIX + "Ya estás en el nivel máximo.");
        } else {
            int newXp = currentXp + amount;
            plugin.getPlayerManager().setXp(uuid, newXp);
            this.checkLevelUp(player, currentLevel, newXp);
        }
    }

    private void checkLevelUp(Player player, int currentLevel, int currentXp) {
        if (currentLevel < 25) {
            int nextLevel = currentLevel + 1;
            int requiredXp = this.xpRequirements.get(nextLevel);
            if (currentXp >= requiredXp) {
                plugin.getPlayerManager().setLevel(player.getUniqueId(), nextLevel);
                player.sendMessage(this.PREFIX + "§a¡Has subido al nivel " + nextLevel + "!");
                this.giveRewards(player, nextLevel);
                this.checkLevelUp(player, nextLevel, currentXp);
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
            return System.currentTimeMillis() - lastKillTime < (this.COOLDOWN_SECONDS * 1000L);
        }
        return false;
    }

    private long getRemainingCooldown(UUID killerUUID, UUID victimUUID) {
        Map<UUID, Long> victimCooldowns = this.killCooldowns.get(killerUUID);
        if (victimCooldowns != null && victimCooldowns.containsKey(victimUUID)) {
            long lastKillTime = victimCooldowns.get(victimUUID);
            long elapsedMillis = System.currentTimeMillis() - lastKillTime;
            long cooldownMillis = this.COOLDOWN_SECONDS * 1000L;
            return elapsedMillis >= cooldownMillis ? 0L : (cooldownMillis - elapsedMillis) / 1000L;
        }
        return 0L;
    }

    private String formatTime(long seconds) {
        return (seconds / 60L) + "m " + (seconds % 60L) + "s";
    }

    // --- PLACEHOLDERS ---

    public String getPlaceholder(UUID uuid, String identifier) {
        int level = plugin.getPlayerManager().getLevel(uuid);
        int xp = plugin.getPlayerManager().getXp(uuid);

        switch (identifier.toLowerCase()) {
            case "level": return String.valueOf(level);
            case "xp": return String.valueOf(xp);
            case "next_level_xp": {
                int nextLevel = level + 1;
                if (nextLevel > 25) return "MAX";
                return String.valueOf(this.xpRequirements.get(nextLevel));
            }
            case "progress_percent":
                return String.valueOf(getProgressPercentage(level, xp));
            case "progress_bar":
                return this.getProgressBar(level, xp, 10, "§a|", "§7|");
            case "xp_needed": {
                int nextLevel = level + 1;
                if (nextLevel > 25) return "0";
                return String.valueOf(Math.max(0, this.xpRequirements.get(nextLevel) - xp));
            }
            default: return null;
        }
    }

    private int getProgressPercentage(int currentLevel, int currentXp) {
        if (currentLevel >= 25) return 100;
        int nextLevel = currentLevel + 1;
        int requiredXp = this.xpRequirements.get(nextLevel);
        int previousLevelXp = this.xpRequirements.get(currentLevel);
        int xpForThisLevel = requiredXp - previousLevelXp;
        int xpProgress = currentXp - previousLevelXp;
        return (int)((double)xpProgress / (double)xpForThisLevel * 100.0D);
    }

    private String getProgressBar(int level, int xp, int length, String completeChar, String incompleteChar) {
        int percent = getProgressPercentage(level, xp);
        int completeBars = (int)((double)percent / 100.0D * (double)length);
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < length; ++i) {
            sb.append(i < completeBars ? completeChar : incompleteChar);
        }
        return sb.toString();
    }
}