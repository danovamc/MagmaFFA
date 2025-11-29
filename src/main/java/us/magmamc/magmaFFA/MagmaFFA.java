package us.magmamc.magmaFFA;

import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

// Adventure & MiniMessage imports
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey; // Import necesario
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType; // Import necesario
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import us.magmamc.magmaFFA.database.DatabaseManager;
import us.magmamc.magmaFFA.managers.PlayerManager;

public class MagmaFFA extends JavaPlugin implements Listener {

    private File ffaConfigFile;
    private FileConfiguration ffaConfig;

    private File messagesConfigFile;
    private FileConfiguration messagesConfig;

    private final Map<String, ItemStack[]> defaultKits = new HashMap<>();
    private final Map<UUID, String> playerFFAs = new HashMap<>();
    private final Map<String, Location> spawns = new HashMap<>();

    private Location lobbyLocation;

    private final Map<UUID, BukkitTask> pendingTeleports = new HashMap<>();
    private final Set<UUID> teleportingPlayers = new HashSet<>();
    private final Set<UUID> spectators = new HashSet<>();

    private FFALevelingSystem levelingSystem;
    private DatabaseManager databaseManager;
    private PlayerManager playerManager;

    private static final String ADMIN_PERMISSION = "magmaffa.admin";

    private static final String NORMAL_CHARS = "abcdefghijklmnopqrstuvwxyz";
    private static final String SMALL_CHARS = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀsᴛᴜᴠᴡxʏᴢ";

    private final Map<Inventory, String> kitEditorInventories = new HashMap<>();

    // Instancia de MiniMessage
    private final MiniMessage mm = MiniMessage.miniMessage();

    // Key para identificar el item del lobby de forma segura
    private final NamespacedKey lobbyItemKey = new NamespacedKey(this, "lobby_selector");

    @Override
    public void onEnable() {
        saveDefaultConfig();

        try {
            this.databaseManager = new DatabaseManager(this);
            this.playerManager = new PlayerManager(this);
        } catch (Exception e) {
            getLogger().severe("Error iniciando base de datos. Desactivando plugin.");
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.loadFFAConfig();
        this.loadMessagesConfig();
        this.loadKitsAndSpawns();
        this.loadLobbyLocation();

        if (getCommand("ffa") != null) {
            this.getCommand("ffa").setExecutor(new FFACommandExecutor());
            this.getCommand("ffa").setTabCompleter(new FFATabCompleter());
        }

        if (getCommand("spawn") != null) {
            this.getCommand("spawn").setExecutor(new SpawnCommand());
        }

        if (getCommand("spectate") != null) {
            this.getCommand("spectate").setExecutor(new SpectateCommand());
        }

        this.levelingSystem = new FFALevelingSystem(this);

        if (this.getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            this.getLogger().info("PlaceholderAPI encontrado, registrando expansión...");
            new FFAPlaceholderExpansion(this, this.levelingSystem).register();
        }

        this.getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        if (this.ffaConfig != null) {
            this.saveKitsAndSpawnsConfig();
        }
        if (playerManager != null) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                playerManager.saveAndUnloadPlayerSync(p.getUniqueId());
            }
        }
        if (databaseManager != null) {
            databaseManager.close();
        }
        pendingTeleports.values().forEach(BukkitTask::cancel);
        pendingTeleports.clear();
    }

    // --- MINI MESSAGE HELPERS ---
    public Component getComponent(String key) {
        String msg = messagesConfig.getString("messages." + key);
        if (msg == null) return Component.text("Mensaje no encontrado: " + key);
        String prefix = messagesConfig.getString("prefix", "");
        return mm.deserialize("<!italic>" + prefix + msg);
    }

    public Component getRawComponent(String text) {
        return mm.deserialize("<!italic>" + text);
    }

    public Component parse(String text) {
        return mm.deserialize("<!italic>" + text);
    }

    public String getMessageString(String key) {
        return messagesConfig.getString("messages." + key, "Missing: " + key);
    }

    public String getPrefixString() {
        return messagesConfig.getString("prefix", "");
    }

    public DatabaseManager getDatabaseManager() { return databaseManager; }
    public PlayerManager getPlayerManager() { return playerManager; }
    public FFALevelingSystem getLevelingSystem() { return levelingSystem; }

    public void reloadAllConfigs() {
        reloadConfig();
        loadFFAConfig();
        loadMessagesConfig();
        defaultKits.clear();
        spawns.clear();
        loadKitsAndSpawns();
        loadLobbyLocation();
        if (levelingSystem != null) {
            levelingSystem.reloadConfig();
        }
        getLogger().info("Configuraciones recargadas correctamente.");
    }

    private void loadMessagesConfig() {
        this.messagesConfigFile = new File(this.getDataFolder(), "messages.yml");
        if (!this.messagesConfigFile.exists()) {
            this.messagesConfigFile.getParentFile().mkdirs();
            this.saveResource("messages.yml", false);
        }
        this.messagesConfig = YamlConfiguration.loadConfiguration(this.messagesConfigFile);
    }

    private void loadFFAConfig() {
        this.ffaConfigFile = new File(this.getDataFolder(), "ffa.yml");
        if (!this.ffaConfigFile.exists()) {
            this.ffaConfigFile.getParentFile().mkdirs();
            this.saveResource("ffa.yml", false);
        }
        this.ffaConfig = YamlConfiguration.loadConfiguration(this.ffaConfigFile);
    }

    private void loadLobbyLocation() {
        if (getConfig().contains("lobby.location")) {
            String worldName = getConfig().getString("lobby.location.world");
            if (worldName != null && Bukkit.getWorld(worldName) != null) {
                double x = getConfig().getDouble("lobby.location.x");
                double y = getConfig().getDouble("lobby.location.y");
                double z = getConfig().getDouble("lobby.location.z");
                float yaw = (float) getConfig().getDouble("lobby.location.yaw");
                float pitch = (float) getConfig().getDouble("lobby.location.pitch");
                this.lobbyLocation = new Location(Bukkit.getWorld(worldName), x, y, z, yaw, pitch);
            }
        }
    }

    private void saveLobbyLocation() {
        if (this.lobbyLocation != null) {
            getConfig().set("lobby.location.world", this.lobbyLocation.getWorld().getName());
            getConfig().set("lobby.location.x", this.lobbyLocation.getX());
            getConfig().set("lobby.location.y", this.lobbyLocation.getY());
            getConfig().set("lobby.location.z", this.lobbyLocation.getZ());
            getConfig().set("lobby.location.yaw", this.lobbyLocation.getYaw());
            getConfig().set("lobby.location.pitch", this.lobbyLocation.getPitch());
            saveConfig();
        }
    }

    private void loadKitsAndSpawns() {
        ConfigurationSection ffaSection = this.ffaConfig.getConfigurationSection("ffas");
        if (ffaSection != null) {
            for(String ffaName : ffaSection.getKeys(false)) {
                ItemStack[] kitItems = new ItemStack[41];
                ConfigurationSection spawnSection = ffaSection.getConfigurationSection(ffaName + ".spawn");
                if (spawnSection != null) {
                    String worldName = spawnSection.getString("world");
                    if (worldName != null && Bukkit.getWorld(worldName) != null) {
                        Location location = new Location(Bukkit.getWorld(worldName),
                                spawnSection.getDouble("x"), spawnSection.getDouble("y"), spawnSection.getDouble("z"),
                                (float)spawnSection.getDouble("yaw"), (float)spawnSection.getDouble("pitch"));
                        this.spawns.put(ffaName, location);
                    }
                }
                ConfigurationSection kitSection = ffaSection.getConfigurationSection(ffaName + ".defaultKit");
                if (kitSection != null) {
                    for(String slotStr : kitSection.getKeys(false)) {
                        try {
                            int slot = Integer.parseInt(slotStr);
                            if (slot >= 0 && slot < kitItems.length) {
                                kitItems[slot] = kitSection.getItemStack(slotStr);
                            }
                        } catch (NumberFormatException ignored) {}
                    }
                }
                this.defaultKits.put(ffaName, kitItems);
            }
        }
    }

    private void saveKitsAndSpawnsConfig() {
        if (this.ffaConfig == null || this.defaultKits == null) return;
        this.ffaConfig.set("ffas", null);
        for(String ffaName : this.defaultKits.keySet()) {
            String basePath = "ffas." + ffaName;
            this.ffaConfig.createSection(basePath);
            ItemStack[] kitItems = this.defaultKits.get(ffaName);
            if (kitItems != null) {
                for(int i = 0; i < kitItems.length; ++i) {
                    if (kitItems[i] != null) {
                        this.ffaConfig.set(basePath + ".defaultKit." + i, kitItems[i]);
                    }
                }
            }
            Location spawn = this.spawns.get(ffaName);
            if (spawn != null) {
                this.ffaConfig.set(basePath + ".spawn.x", spawn.getX());
                this.ffaConfig.set(basePath + ".spawn.y", spawn.getY());
                this.ffaConfig.set(basePath + ".spawn.z", spawn.getZ());
                this.ffaConfig.set(basePath + ".spawn.yaw", spawn.getYaw());
                this.ffaConfig.set(basePath + ".spawn.pitch", spawn.getPitch());
                this.ffaConfig.set(basePath + ".spawn.world", spawn.getWorld().getName());
            }
        }
        try {
            this.ffaConfig.save(this.ffaConfigFile);
        } catch (IOException e) {
            this.getLogger().severe("Could not save FFA config: " + e.getMessage());
        }
    }

    private String toSmallCaps(String text) {
        char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (c >= 'a' && c <= 'z') {
                chars[i] = SMALL_CHARS.charAt(c - 'a');
            } else if (c >= 'A' && c <= 'Z') {
                chars[i] = SMALL_CHARS.charAt(c - 'A');
            }
        }
        return new String(chars);
    }

    private void giveLobbyItems(Player player) {
        if (!getConfig().getBoolean("lobby.join-item.enabled", true)) return;

        String matName = getConfig().getString("lobby.join-item.material", "COMPASS");
        Material mat = Material.getMaterial(matName);
        if (mat == null) mat = Material.COMPASS;

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();

        String nameCfg = getConfig().getString("lobby.join-item.name", "<green>Selector");
        meta.displayName(getRawComponent(nameCfg));

        List<String> loreCfg = getConfig().getStringList("lobby.join-item.lore");
        List<Component> loreComponents = new ArrayList<>();
        for (String l : loreCfg) {
            loreComponents.add(getRawComponent(l));
        }
        meta.lore(loreComponents);

        // MARCA EL ÍTEM CON UN TAG PERSISTENTE PARA IDENTIFICARLO FÁCILMENTE
        meta.getPersistentDataContainer().set(lobbyItemKey, PersistentDataType.BYTE, (byte) 1);

        item.setItemMeta(meta);

        int slot = getConfig().getInt("lobby.join-item.slot", 4);
        player.getInventory().setItem(slot, item);
    }

    // MÉTODO AUXILIAR PARA IDENTIFICAR EL ÍTEM DEL LOBBY
    private boolean isLobbyItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        // 1. Chequeo por PersistentDataContainer (lo más seguro)
        if (item.getItemMeta().getPersistentDataContainer().has(lobbyItemKey, PersistentDataType.BYTE)) return true;

        // 2. Chequeo por Material (Backup si el item es viejo o el PDC falló)
        String matName = getConfig().getString("lobby.join-item.material", "COMPASS");
        Material mat = Material.getMaterial(matName);
        return item.getType() == mat;
    }

    public void sendToSpawn(Player player) {
        if (this.lobbyLocation == null) {
            player.sendMessage(getRawComponent("<red>El spawn no ha sido establecido. Usa /ffa setlobby"));
            return;
        }

        if (pendingTeleports.containsKey(player.getUniqueId())) {
            pendingTeleports.get(player.getUniqueId()).cancel();
            pendingTeleports.remove(player.getUniqueId());
            teleportingPlayers.remove(player.getUniqueId());
        }

        // SALIR DE ESPECTADOR
        if (spectators.contains(player.getUniqueId())) {
            disableSpectatorMode(player);
        }

        player.teleport(this.lobbyLocation);

        if (getConfig().getBoolean("spawn-settings.on-spawn.clear-fire", true)) {
            player.setFireTicks(0);
        }

        if (getConfig().getBoolean("spawn-settings.on-spawn.clear-inventory", true)) {
            player.getInventory().clear();
            giveLobbyItems(player);
        } else {
            giveLobbyItems(player);
        }

        if (getConfig().getBoolean("spawn-settings.on-spawn.clear-effects", true)) {
            for(PotionEffect effect : player.getActivePotionEffects()) {
                player.removePotionEffect(effect.getType());
            }
        }

        if (getConfig().getBoolean("spawn-settings.on-spawn.clear-ender-pearls", true)) {
            player.setCooldown(Material.ENDER_PEARL, 0);
        }

        player.setHealth(20.0);
        player.setFoodLevel(20);
        player.setGameMode(GameMode.SURVIVAL);

        this.playerFFAs.remove(player.getUniqueId());

        player.sendMessage(getComponent("spawn-teleport"));
    }

    // ... (Resto de métodos de espectador y spawn sin cambios) ...
    private void enableSpectatorMode(Player player, Player target) {
        if (playerFFAs.containsKey(player.getUniqueId())) {
            playerFFAs.remove(player.getUniqueId());
            player.getInventory().clear();
        }
        spectators.add(player.getUniqueId());
        player.setGameMode(GameMode.ADVENTURE);
        player.setAllowFlight(true);
        player.setFlying(true);
        player.getInventory().clear();
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.hidePlayer(this, player);
        }
        player.teleport(target.getLocation());
        String msg = getPrefixString() + getMessageString("spectator-mode").replace("%player%", target.getName());
        player.sendMessage(parse(msg));
        String tip = getPrefixString() + getMessageString("spectator-leave-tip");
        player.sendMessage(parse(tip));
    }

    private void disableSpectatorMode(Player player) {
        spectators.remove(player.getUniqueId());
        player.setFlying(false);
        player.setAllowFlight(false);
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.showPlayer(this, player);
        }
    }

    private void startSpawnCountdown(Player player) {
        if (pendingTeleports.containsKey(player.getUniqueId())) {
            player.sendMessage(getComponent("already-teleporting"));
            return;
        }
        int seconds = getConfig().getInt("spawn-settings.countdown.timer", 3);
        if (seconds <= 0 || player.hasPermission(ADMIN_PERMISSION)) {
            sendToSpawn(player);
            return;
        }
        String msg = getPrefixString() + getMessageString("spawn-countdown").replace("%seconds%", String.valueOf(seconds));
        player.sendMessage(parse(msg));
        teleportingPlayers.add(player.getUniqueId());
        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                teleportingPlayers.remove(player.getUniqueId());
                pendingTeleports.remove(player.getUniqueId());
                sendToSpawn(player);
            }
        }.runTaskLater(this, seconds * 20L);
        pendingTeleports.put(player.getUniqueId(), task);
    }

    // --- EVENTOS ---

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (getConfig().getBoolean("spawn-settings.countdown.cancel-on-move", true)) {
            if (event.getFrom().getBlockX() != event.getTo().getBlockX() ||
                    event.getFrom().getBlockY() != event.getTo().getBlockY() ||
                    event.getFrom().getBlockZ() != event.getTo().getBlockZ()) {

                Player player = event.getPlayer();
                if (teleportingPlayers.contains(player.getUniqueId())) {
                    BukkitTask task = pendingTeleports.remove(player.getUniqueId());
                    if (task != null) {
                        task.cancel();
                        teleportingPlayers.remove(player.getUniqueId());
                        player.sendMessage(getComponent("teleport-cancelled"));
                    }
                }
            }
        }
    }

    // ... (Eventos de espectador sin cambios) ...
    @EventHandler public void onSpectatorInteract(PlayerInteractEvent event) { if (spectators.contains(event.getPlayer().getUniqueId())) event.setCancelled(true); }
    @EventHandler public void onSpectatorDamage(EntityDamageByEntityEvent event) { if (event.getDamager() instanceof Player && spectators.contains(event.getDamager().getUniqueId())) event.setCancelled(true); if (event.getEntity() instanceof Player && spectators.contains(event.getEntity().getUniqueId())) event.setCancelled(true); }
    @EventHandler public void onSpectatorDamageSelf(EntityDamageEvent event) { if (event.getEntity() instanceof Player && spectators.contains(event.getEntity().getUniqueId())) event.setCancelled(true); }
    @EventHandler public void onSpectatorBreak(BlockBreakEvent event) { if (spectators.contains(event.getPlayer().getUniqueId())) event.setCancelled(true); }
    @EventHandler public void onSpectatorPlace(BlockPlaceEvent event) { if (spectators.contains(event.getPlayer().getUniqueId())) event.setCancelled(true); }
    @EventHandler public void onSpectatorPickup(PlayerPickupItemEvent event) { if (spectators.contains(event.getPlayer().getUniqueId())) event.setCancelled(true); }
    @EventHandler public void onSpectatorDrop(PlayerDropItemEvent event) { if (spectators.contains(event.getPlayer().getUniqueId())) event.setCancelled(true); }
    @EventHandler public void onSpectatorHunger(FoodLevelChangeEvent event) { if (event.getEntity() instanceof Player && spectators.contains(event.getEntity().getUniqueId())) event.setCancelled(true); }

    // CORRECCIÓN: Detección robusta del ítem del lobby
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (spectators.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            return;
        }

        if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            Player player = event.getPlayer();
            ItemStack item = event.getItem();

            if (isLobbyItem(item)) {
                String cmd = getConfig().getString("lobby.join-item.command");
                if (cmd != null && !cmd.isEmpty()) {
                    String finalCmd = cmd.replace("%player%", player.getName());
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCmd);
                    event.setCancelled(true);
                }
            }
        }
    }

    @EventHandler
    public void onPlayerSwapHandItems(PlayerSwapHandItemsEvent event) {
        if (spectators.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            return;
        }

        if (!getConfig().getBoolean("lobby.join-item.prevent-move", true)) return;
        if (event.getPlayer().hasPermission(ADMIN_PERMISSION)) return;

        if (isLobbyItem(event.getMainHandItem())) {
            event.setCancelled(true);
            return;
        }
        if (isLobbyItem(event.getOffHandItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player && spectators.contains(event.getWhoClicked().getUniqueId())) {
            event.setCancelled(true);
            return;
        }

        if (getConfig().getBoolean("lobby.join-item.prevent-move", true)
                && !event.getWhoClicked().hasPermission(ADMIN_PERMISSION)) {

            if (isLobbyItem(event.getCurrentItem())) {
                if (!isKitEditorInventory(event.getView().getTopInventory())) {
                    event.setCancelled(true);
                }
            }

            if (event.getClick() == ClickType.NUMBER_KEY) {
                int hotbarSlot = event.getHotbarButton();
                if (hotbarSlot >= 0) {
                    ItemStack hotbarItem = event.getWhoClicked().getInventory().getItem(hotbarSlot);
                    if (isLobbyItem(hotbarItem)) {
                        if (!isKitEditorInventory(event.getView().getTopInventory())) {
                            event.setCancelled(true);
                        }
                    }
                }
            }
        }

        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player)event.getWhoClicked();
        Inventory inventory = event.getInventory();

        if (this.isKitEditorInventory(inventory)) {
            String ffaName = this.getFFANameFromInventory(inventory);
            int slot = event.getRawSlot();

            if (event.getClickedInventory() == player.getInventory()) {
                event.setCancelled(true); return;
            }

            if (event.getClickedInventory() == inventory) {
                if (slot >= 0 && slot <= 3 || slot >= 9 && slot < 18) {
                    event.setCancelled(true); return;
                }
                if (slot == 6) {
                    event.setCancelled(true);
                    this.savePersonalKitFromEditor(player, inventory, ffaName);
                    player.closeInventory();
                    player.sendMessage(getComponent("kit-saved"));
                    return;
                }
                if (slot == 7) {
                    event.setCancelled(true);
                    this.resetToDefaultKit(player, inventory, ffaName);
                    player.sendMessage(getComponent("kit-reset"));
                    return;
                }
                if (slot == 8) {
                    event.setCancelled(true);
                    player.closeInventory();
                    return;
                }
            }
            if (event.isShiftClick() || event.getClick().isKeyboardClick()) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (this.isKitEditorInventory(event.getInventory())) {
            int topSize = event.getView().getTopInventory().getSize();
            for(int slot : event.getRawSlots()) {
                if (slot < topSize) {
                    if (slot <= 3 || (slot >= 9 && slot < 18) || slot == 6 || slot == 7 || slot == 8) {
                        event.setCancelled(true); return;
                    }
                }
            }
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        for (UUID uuid : spectators) {
            Player spec = Bukkit.getPlayer(uuid);
            if (spec != null) {
                event.getPlayer().hidePlayer(this, spec);
            }
        }

        if (getConfig().getBoolean("spawn-settings.force-spawn-on-join", true)) {
            sendToSpawn(event.getPlayer());
        } else if (!playerFFAs.containsKey(event.getPlayer().getUniqueId())) {
            if (getConfig().getBoolean("spawn-settings.on-spawn.clear-inventory", true)) {
                event.getPlayer().getInventory().clear();
            }
            giveLobbyItems(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        if (getConfig().getBoolean("spawn-settings.always-respawn-at-spawn", true)) {
            if (this.lobbyLocation != null) {
                event.setRespawnLocation(this.lobbyLocation);
            }
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!event.getPlayer().isOnline()) return;

                if (!playerFFAs.containsKey(event.getPlayer().getUniqueId())) {
                    if (getConfig().getBoolean("spawn-settings.on-spawn.clear-inventory", true)) {
                        event.getPlayer().getInventory().clear();
                    }
                    giveLobbyItems(event.getPlayer());

                    if (getConfig().getBoolean("spawn-settings.always-respawn-at-spawn", true) && lobbyLocation != null) {
                        Location loc = event.getPlayer().getLocation();
                        if (loc.getWorld() != lobbyLocation.getWorld() ||
                                loc.distanceSquared(lobbyLocation) > 1 ||
                                Math.abs(loc.getYaw() - lobbyLocation.getYaw()) > 10) {
                            event.getPlayer().teleport(lobbyLocation);
                        }
                    }
                }
            }
        }.runTaskLater(this, 2L);
    }

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (event.getPlayer().getOpenInventory() != null && this.isKitEditorInventory(event.getPlayer().getOpenInventory().getTopInventory())) {
            event.setCancelled(true);
        }

        if (spectators.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            return;
        }

        if (isLobbyItem(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onCraftItem(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();

        if (this.playerFFAs.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage(getComponent("crafting-disabled"));
        }
        else if (!player.hasPermission(ADMIN_PERMISSION)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        playerFFAs.remove(event.getPlayer().getUniqueId());
        spectators.remove(event.getPlayer().getUniqueId());

        if (playerManager != null) {
            playerManager.saveAndUnloadPlayer(event.getPlayer().getUniqueId());
        }

        if (pendingTeleports.containsKey(event.getPlayer().getUniqueId())) {
            pendingTeleports.get(event.getPlayer().getUniqueId()).cancel();
            pendingTeleports.remove(event.getPlayer().getUniqueId());
            teleportingPlayers.remove(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        final Player victim = event.getEntity();
        final Player killer = victim.getKiller();
        event.getDrops().clear();

        if (spectators.contains(victim.getUniqueId())) {
            event.getDrops().clear();
            return;
        }

        if (getConfig().getBoolean("spawn-settings.auto-respawn", true)) {
            Bukkit.getScheduler().runTaskLater(this, () -> victim.spigot().respawn(), 1L);
        }

        boolean wasInFFA = this.playerFFAs.containsKey(victim.getUniqueId());
        if (wasInFFA) {
            this.playerFFAs.remove(victim.getUniqueId());
        }

        if (killer == null || killer.equals(victim)) return;

        if (this.playerFFAs.containsKey(killer.getUniqueId())) {
            String ffaName = this.playerFFAs.get(killer.getUniqueId());
            UUID playerUuid = killer.getUniqueId();
            ItemStack[] personalKit = playerManager.getPersonalKit(playerUuid, ffaName);
            final ItemStack[] kitItems = (personalKit != null) ? personalKit : this.defaultKits.get(ffaName);

            if (kitItems == null) return;

            new BukkitRunnable() {
                public void run() {
                    killer.getInventory().clear();
                    for(int i = 0; i < 36; ++i) {
                        if (kitItems[i] != null) killer.getInventory().setItem(i, kitItems[i].clone());
                    }
                    ItemStack[] armorItems = new ItemStack[4];
                    System.arraycopy(kitItems, 36, armorItems, 0, 4);
                    killer.getInventory().setArmorContents(armorItems);
                    if (kitItems[40] != null) killer.getInventory().setItemInOffHand(kitItems[40].clone());

                    double healthGained = 20.0 - killer.getHealth();
                    DecimalFormat df = new DecimalFormat("#,##");

                    String actionbarStr = getMessageString("death-message-actionbar")
                            .replace("%health%", df.format(healthGained))
                            .replace("%player%", victim.getName());

                    killer.sendActionBar(parse(actionbarStr));
                    killer.setFoodLevel(20);
                    killer.setHealth(20.0);
                }
            }.runTaskLater(this, 1L);
        }
    }

    private boolean isKitEditorInventory(Inventory inventory) {
        return this.kitEditorInventories.containsKey(inventory);
    }

    private String getFFANameFromInventory(Inventory inventory) {
        return this.kitEditorInventories.getOrDefault(inventory, "");
    }

    private void openKitEditor(Player player, String ffaName) {
        UUID uuid = player.getUniqueId();
        ItemStack[] personalKit = playerManager.getPersonalKit(uuid, ffaName);
        ItemStack[] kitItems;

        if (personalKit != null) {
            kitItems = personalKit;
        } else {
            ItemStack[] defaultKit = this.defaultKits.get(ffaName);
            kitItems = new ItemStack[41];
            for(int i = 0; i < defaultKit.length; ++i) {
                if (defaultKit[i] != null) kitItems[i] = defaultKit[i].clone();
            }
        }

        String titleText = "Editor de Kit: " + ffaName;
        Component title = getRawComponent(titleText);
        Inventory editorInventory = Bukkit.createInventory(null, 54, title);

        this.kitEditorInventories.put(editorInventory, ffaName);

        setupEditorGui(editorInventory, kitItems);
        player.openInventory(editorInventory);
    }

    private void setupEditorGui(Inventory inv, ItemStack[] kitItems) {
        ItemStack blackGlass = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta blackMeta = blackGlass.getItemMeta();
        blackMeta.displayName(Component.text(" "));
        blackGlass.setItemMeta(blackMeta);
        for(int i = 0; i < 4; ++i) {
            if (kitItems[39 - i] != null) inv.setItem(i, kitItems[39 - i].clone());
        }
        if (kitItems[40] != null) inv.setItem(5, kitItems[40].clone());
        inv.setItem(6, createButton(Material.LIME_STAINED_GLASS_PANE, "<green>Guardar KIT Personal"));
        inv.setItem(7, createButton(Material.YELLOW_STAINED_GLASS_PANE, "<yellow>Reestablecer al Kit Predeterminado"));
        inv.setItem(8, createButton(Material.RED_STAINED_GLASS_PANE, "<red>Cerrar"));
        for(int i = 9; i < 18; ++i) inv.setItem(i, blackGlass);
        for(int i = 0; i < 36; ++i) {
            if (kitItems[i] != null) {
                int editorSlot = (i < 9) ? 45 + i : i + 9;
                inv.setItem(editorSlot, kitItems[i].clone());
            }
        }
    }

    private ItemStack createButton(Material mat, String name) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(getRawComponent(name));
        item.setItemMeta(meta);
        return item;
    }

    private void resetToDefaultKit(Player player, Inventory editorInventory, String ffaName) {
        ItemStack[] defaultKit = this.defaultKits.get(ffaName);
        setupEditorGui(editorInventory, defaultKit);
    }

    private void savePersonalKitFromEditor(Player player, Inventory editorInventory, String ffaName) {
        ItemStack[] kitItems = new ItemStack[41];
        for(int i = 0; i < 4; ++i) kitItems[39 - i] = editorInventory.getItem(i);
        kitItems[40] = editorInventory.getItem(5);
        for(int i = 0; i < 36; ++i) {
            int editorSlot = (i < 9) ? 45 + i : i + 9;
            kitItems[i] = editorInventory.getItem(editorSlot);
        }
        playerManager.savePersonalKit(player.getUniqueId(), ffaName, kitItems);
        player.getInventory().clear();
        giveLobbyItems(player);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        this.kitEditorInventories.remove(event.getInventory());
    }

    private class SpawnCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player)) return true;
            Player player = (Player) sender;

            if (getConfig().getBoolean("spawn-settings.countdown.enabled", true)) {
                startSpawnCountdown(player);
            } else {
                sendToSpawn(player);
            }
            return true;
        }
    }

    private class SpectateCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player)) return true;
            Player player = (Player) sender;

            if (args.length != 1) {
                player.sendMessage(getComponent("no-permission"));
                return true;
            }

            Player target = Bukkit.getPlayer(args[0]);
            if (target == null || !target.isOnline()) {
                player.sendMessage(getComponent("player-not-found"));
                return true;
            }

            if (target.equals(player)) {
                player.sendMessage(getComponent("spectate-self"));
                return true;
            }

            enableSpectatorMode(player, target);
            return true;
        }
    }

    private class FFACommandExecutor implements CommandExecutor {
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(getComponent("player-only"));
                return true;
            }
            Player player = (Player) sender;
            if (args.length < 1) {
                sendHelp(player);
                return true;
            }

            String subCommand = args[0].toLowerCase();

            if (Arrays.asList("create", "setspawn", "setkit", "delete", "setlobby", "reload").contains(subCommand)) {
                if (!player.hasPermission(ADMIN_PERMISSION)) {
                    player.sendMessage(getComponent("no-permission")); return true;
                }

                switch (subCommand) {
                    case "create":
                        if (defaultKits.containsKey(args[1].toLowerCase())) player.sendMessage(getComponent("arena-exists"));
                        else {
                            String newName = args[1].toLowerCase();
                            defaultKits.put(newName, new ItemStack[41]);
                            MagmaFFA.this.saveKitsAndSpawnsConfig();
                            String msg = getPrefixString() + getMessageString("arena-created").replace("%name%", newName);
                            player.sendMessage(parse(msg));
                        }
                        break;
                    case "setspawn":
                        if (!defaultKits.containsKey(args[1].toLowerCase())) player.sendMessage(getComponent("arena-not-found"));
                        else {
                            spawns.put(args[1].toLowerCase(), player.getLocation());
                            MagmaFFA.this.saveKitsAndSpawnsConfig();
                            player.sendMessage(getComponent("spawn-arena-set"));
                        }
                        break;
                    case "setkit":
                        if (!defaultKits.containsKey(args[1].toLowerCase())) player.sendMessage(getComponent("arena-not-found"));
                        else {
                            String kitName = args[1].toLowerCase();
                            ItemStack[] items = new ItemStack[41];
                            System.arraycopy(player.getInventory().getContents(), 0, items, 0, 36);
                            System.arraycopy(player.getInventory().getArmorContents(), 0, items, 36, 4);
                            items[40] = player.getInventory().getItemInOffHand();
                            defaultKits.put(kitName, items);
                            MagmaFFA.this.saveKitsAndSpawnsConfig();
                            player.sendMessage(getComponent("kit-updated"));
                        }
                        break;
                    case "delete":
                        if (!defaultKits.containsKey(args[1].toLowerCase())) player.sendMessage(getComponent("arena-not-found"));
                        else {
                            String delName = args[1].toLowerCase();
                            defaultKits.remove(delName);
                            spawns.remove(delName);
                            MagmaFFA.this.saveKitsAndSpawnsConfig();
                            player.sendMessage(getComponent("arena-deleted"));
                        }
                        break;
                    case "setlobby":
                        MagmaFFA.this.lobbyLocation = player.getLocation();
                        MagmaFFA.this.saveLobbyLocation();
                        player.sendMessage(getComponent("spawn-set"));
                        break;
                    case "reload":
                        MagmaFFA.this.reloadAllConfigs();
                        player.sendMessage(getComponent("config-reloaded"));
                        break;
                }
                return true;
            }
            else if (subCommand.equals("editkit")) {
                if (args.length < 2) {
                    player.sendMessage(parse(getPrefixString() + "Uso: /ffa editkit <nombre>")); return true;
                }
                String ffaName = args[1].toLowerCase();
                if (!MagmaFFA.this.defaultKits.containsKey(ffaName)) {
                    player.sendMessage(getComponent("arena-not-found"));
                } else {
                    MagmaFFA.this.openKitEditor(player, ffaName);
                }
                return true;
            }
            else if (subCommand.equals("join")) {
                if (args.length < 2) {
                    player.sendMessage(parse(getPrefixString() + "Uso: /ffa join <nombre>")); return true;
                }
                String ffaName = args[1].toLowerCase();
                if (!MagmaFFA.this.defaultKits.containsKey(ffaName)) {
                    player.sendMessage(getComponent("arena-not-found"));
                    return true;
                }
                if (!MagmaFFA.this.spawns.containsKey(ffaName)) {
                    player.sendMessage(parse(getPrefixString() + "<red>Spawn no establecido.")); return true;
                }

                ItemStack[] personalKit = playerManager.getPersonalKit(player.getUniqueId(), ffaName);
                ItemStack[] kitItems = (personalKit != null) ? personalKit : defaultKits.get(ffaName);

                if (personalKit == null) {
                    String tip = getPrefixString() + getMessageString("tip-editkit").replace("%arena%", ffaName);
                    player.sendMessage(parse(tip));
                }

                MagmaFFA.this.playerFFAs.put(player.getUniqueId(), ffaName);
                joinArena(player, ffaName, kitItems);
                return true;
            }
            // --- NUEVO COMANDO JOINBEST ---
            else if (subCommand.equals("joinbest") || subCommand.equals("best")) {
                if (!player.hasPermission("magmaffa.player")) { // VERIFICACIÓN DE PERMISO
                    player.sendMessage(getComponent("no-permission"));
                    return true;
                }

                if (defaultKits.isEmpty()) {
                    player.sendMessage(getComponent("no-arenas-available"));
                    return true;
                }

                String bestArena = null;
                int maxPlayers = -1;

                // Iterar sobre las arenas disponibles para encontrar la más poblada
                for (String arenaName : defaultKits.keySet()) {
                    if (!spawns.containsKey(arenaName)) continue; // Saltar si no tiene spawn

                    int count = 0;
                    // Contar jugadores en esta arena usando el mapa interno
                    for (String activeArena : playerFFAs.values()) {
                        if (activeArena.equalsIgnoreCase(arenaName)) {
                            count++;
                        }
                    }

                    if (count > maxPlayers) {
                        maxPlayers = count;
                        bestArena = arenaName;
                    }
                }

                if (bestArena == null) {
                    player.sendMessage(getComponent("no-arenas-available"));
                    return true;
                }

                String msg = getPrefixString() + getMessageString("joining-best-arena")
                        .replace("%arena%", bestArena)
                        .replace("%count%", String.valueOf(maxPlayers));
                player.sendMessage(parse(msg));

                // Reutilizar lógica de join
                ItemStack[] personalKit = playerManager.getPersonalKit(player.getUniqueId(), bestArena);
                ItemStack[] kitItems = (personalKit != null) ? personalKit : defaultKits.get(bestArena);

                if (personalKit == null) {
                    String tip = getPrefixString() + getMessageString("tip-editkit").replace("%arena%", bestArena);
                    player.sendMessage(parse(tip));
                }

                MagmaFFA.this.playerFFAs.put(player.getUniqueId(), bestArena);
                joinArena(player, bestArena, kitItems);
                return true;
            }
            else {
                sendHelp(player);
                return true;
            }
        }

        private void joinArena(Player player, String ffaName, ItemStack[] kitItems) {
            player.getInventory().clear();
            for(PotionEffect effect : player.getActivePotionEffects()) player.removePotionEffect(effect.getType());

            if(kitItems != null) {
                for(int i = 0; i < 36; ++i) if (kitItems[i] != null) player.getInventory().setItem(i, kitItems[i].clone());
                ItemStack[] armorItems = new ItemStack[4];
                System.arraycopy(kitItems, 36, armorItems, 0, 4);
                player.getInventory().setArmorContents(armorItems);
                if (kitItems[40] != null) player.getInventory().setItemInOffHand(kitItems[40].clone());
            }

            player.setSaturation(20.0F);
            player.setHealth(20.0);
            player.setFoodLevel(20);
            player.teleport(MagmaFFA.this.spawns.get(ffaName));

            String joinMsg = getPrefixString() + getMessageString("join-arena")
                    .replace("%player%", player.getName())
                    .replace("%name%", ffaName.toUpperCase());
            Bukkit.broadcast(parse(joinMsg));
        }

        private void sendHelp(Player player) {
            player.sendMessage(parse(getPrefixString() + "Comandos:"));
            if (player.hasPermission(ADMIN_PERMISSION)) {
                player.sendMessage(parse("<dark_gray>• <yellow>/ffa reload <dark_gray>- <gray>Recargar configuración"));
                player.sendMessage(parse("<dark_gray>• <yellow>/ffa setlobby <dark_gray>- <gray>Setear Spawn Principal"));
                player.sendMessage(parse("<dark_gray>• <yellow>/ffa create <nombre>"));
                player.sendMessage(parse("<dark_gray>• <yellow>/ffa setspawn <nombre>"));
                player.sendMessage(parse("<dark_gray>• <yellow>/ffa setkit <nombre>"));
            }
            player.sendMessage(parse("<dark_gray>• <yellow>/spawn <dark_gray>- <gray>Ir al Lobby"));
            player.sendMessage(parse("<dark_gray>• <yellow>/ffa join <nombre> <dark_gray>- <gray>Entrar a una arena"));
            player.sendMessage(parse("<dark_gray>• <yellow>/ffa joinbest <dark_gray>- <gray>Entrar a la arena con más jugadores"));
            player.sendMessage(parse("<dark_gray>• <yellow>/spectate <jugador> <dark_gray>- <gray>Espectar a un jugador"));
            player.sendMessage(parse("<dark_gray>• <yellow>/ffa editkit <nombre>"));
            if (!MagmaFFA.this.defaultKits.isEmpty()) {
                player.sendMessage(parse(getPrefixString() + "Arenas: <gray>" + String.join(", ", defaultKits.keySet())));
            }
        }
    }

    private class FFATabCompleter implements TabCompleter {
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            if (!(sender instanceof Player)) return new ArrayList<>();
            Player player = (Player) sender;
            List<String> options = new ArrayList<>();

            if (args.length == 1) {
                options.add("join");
                options.add("joinbest");
                options.add("editkit");
                if (player.hasPermission(ADMIN_PERMISSION)) {
                    options.addAll(Arrays.asList("create", "setspawn", "setkit", "delete", "setlobby", "reload"));
                }
                return options.stream().filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase())).collect(Collectors.toList());
            } else if (args.length == 2) {
                String sub = args[0].toLowerCase();
                if (Arrays.asList("join", "editkit", "setspawn", "setkit", "delete").contains(sub)) {
                    return new ArrayList<>(MagmaFFA.this.defaultKits.keySet());
                }
            }
            return new ArrayList<>();
        }
    }
}