package us.magmamc.magmaFFA;

import java.io.File;
import java.io.FileWriter; // Importante para la escritura segura
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitRunnable;

public class MagmaFFA extends JavaPlugin implements Listener {

    private File ffaConfigFile;
    private FileConfiguration ffaConfig;
    private final Map<String, ItemStack[]> defaultKits = new HashMap<>();
    private final Map<String, Map<UUID, ItemStack[]>> personalKits = new HashMap<>();
    private final Map<UUID, String> playerFFAs = new HashMap<>();
    private final Map<String, Location> spawns = new HashMap<>();
    private FFALevelingSystem levelingSystem;

    private static final String ADMIN_PERMISSION = "magmaffa.admin";
    private final String PREFIX = "§x§c§8§0§5§0§5§lF§x§e§3§0§3§0§3§lF§x§f§d§0§0§0§0§lA §8▸ §f";
    private final String TIP = "§e§lTIP §8▸ §f";

    private final Map<Inventory, String> kitEditorInventories = new HashMap<>();

    @Override
    public void onEnable() {
        this.loadFFAConfig();
        this.loadKitsAndSpawns();

        if (getCommand("ffa") != null) {
            this.getCommand("ffa").setExecutor(new FFACommandExecutor());
            this.getCommand("ffa").setTabCompleter(new FFATabCompleter());
        }

        this.levelingSystem = new FFALevelingSystem(this, PREFIX);

        if (this.getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            this.getLogger().info("PlaceholderAPI encontrado, registrando expansión...");
            new FFAPlaceholderExpansion(this, this.levelingSystem).register();
        }

        this.getServer().getPluginManager().registerEvents(this, this);
    }

    private void loadFFAConfig() {
        this.ffaConfigFile = new File(this.getDataFolder(), "ffa.yml");
        if (!this.ffaConfigFile.exists()) {
            this.ffaConfigFile.getParentFile().mkdirs();
            this.saveResource("ffa.yml", false);
        }
        this.ffaConfig = YamlConfiguration.loadConfiguration(this.ffaConfigFile);
    }

    private void loadKitsAndSpawns() {
        ConfigurationSection ffaSection = this.ffaConfig.getConfigurationSection("ffas");
        if (ffaSection != null) {
            for(String ffaName : ffaSection.getKeys(false)) {
                ConfigurationSection spawnSection = ffaSection.getConfigurationSection(ffaName + ".spawn");
                if (spawnSection != null) {
                    double x = spawnSection.getDouble("x");
                    double y = spawnSection.getDouble("y");
                    double z = spawnSection.getDouble("z");
                    float yaw = (float)spawnSection.getDouble("yaw");
                    float pitch = (float)spawnSection.getDouble("pitch");
                    String worldName = spawnSection.getString("world");
                    if (worldName != null && Bukkit.getWorld(worldName) != null) {
                        Location location = new Location(Bukkit.getWorld(worldName), x, y, z, yaw, pitch);
                        this.spawns.put(ffaName, location);
                    }
                }

                ConfigurationSection kitSection = ffaSection.getConfigurationSection(ffaName + ".defaultKit");
                if (kitSection != null) {
                    ItemStack[] kitItems = new ItemStack[41];
                    for(String slotStr : kitSection.getKeys(false)) {
                        int slot = Integer.parseInt(slotStr);
                        if (slot >= 0 && slot < kitItems.length) {
                            kitItems[slot] = kitSection.getItemStack(slotStr);
                        }
                    }
                    this.defaultKits.put(ffaName, kitItems);
                }

                ConfigurationSection personalKitsSection = ffaSection.getConfigurationSection(ffaName + ".personalKits");
                if (personalKitsSection != null) {
                    Map<UUID, ItemStack[]> playerKits = new HashMap<>();
                    for(String uuidStr : personalKitsSection.getKeys(false)) {
                        UUID uuid = UUID.fromString(uuidStr);
                        ConfigurationSection playerKitSection = personalKitsSection.getConfigurationSection(uuidStr);
                        if (playerKitSection != null) {
                            ItemStack[] kitItems = new ItemStack[41];
                            for(String slotStr : playerKitSection.getKeys(false)) {
                                int slot = Integer.parseInt(slotStr);
                                if (slot >= 0 && slot < kitItems.length) {
                                    kitItems[slot] = playerKitSection.getItemStack(slotStr);
                                }
                            }
                            playerKits.put(uuid, kitItems);
                        }
                    }
                    this.personalKits.put(ffaName, playerKits);
                }
            }
        }
    }

    private void saveAllKitsAndSpawnsSync() {
        this.ffaConfig.set("ffas", null);

        for(String ffaName : this.defaultKits.keySet()) {
            String basePath = "ffas." + ffaName;
            ItemStack[] kitItems = this.defaultKits.get(ffaName);

            for(int i = 0; i < kitItems.length; ++i) {
                if (kitItems[i] != null) {
                    this.ffaConfig.set(basePath + ".defaultKit." + i, kitItems[i]);
                }
            }

            Map<UUID, ItemStack[]> playerKits = this.personalKits.getOrDefault(ffaName, new HashMap<>());
            for(UUID playerUuid : playerKits.keySet()) {
                ItemStack[] playerKitItems = playerKits.get(playerUuid);
                for(int i = 0; i < playerKitItems.length; ++i) {
                    if (playerKitItems[i] != null) {
                        this.ffaConfig.set(basePath + ".personalKits." + playerUuid.toString() + "." + i, playerKitItems[i]);
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

    /**
     * MÉTODO BLINDADO: 100% SEGURO Y SIN LAG
     * 1. Genera el texto en el hilo principal (Snapshot seguro).
     * 2. Escribe el archivo en el hilo secundario (Sin lag).
     */
    private void saveConfigAsync() {
        // Paso 1: "Congelar" los datos a texto (String) en el Hilo Principal.
        // saveToString() es muy rápido (milisegundos) y seguro de usar aquí.
        final String data = this.ffaConfig.saveToString();

        // Paso 2: Escribir ese texto al disco duro en Hilo Secundario.
        // Esto puede tardar lo que quiera, el servidor no se enterará.
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try (FileWriter writer = new FileWriter(this.ffaConfigFile)) {
                writer.write(data);
            } catch (IOException e) {
                this.getLogger().severe("Error saving FFA config async: " + e.getMessage());
            }
        });
    }

    private String toSmallCaps(String text) {
        StringBuilder result = new StringBuilder();
        String normal = "abcdefghijklmnopqrstuvwxyz";
        String small = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀsᴛᴜᴠᴡxʏᴢ";

        for(char c : text.toCharArray()) {
            if (Character.isLetter(c)) {
                if (Character.isUpperCase(c)) {
                    result.append(c);
                } else {
                    int index = normal.indexOf(c);
                    if(index >= 0) {
                        result.append(small.charAt(index));
                    } else {
                        result.append(c);
                    }
                }
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player) {
            Player player = (Player)event.getWhoClicked();
            Inventory inventory = event.getInventory();
            if (this.isKitEditorInventory(inventory)) {
                String ffaName = this.getFFANameFromInventory(inventory);
                int slot = event.getRawSlot();

                if (event.getClickedInventory() == player.getInventory()) {
                    event.setCancelled(true);
                    return;
                }

                if (event.getClickedInventory() == inventory) {
                    if (slot >= 0 && slot <= 3 || slot >= 9 && slot < 18) {
                        event.setCancelled(true);
                        return;
                    }
                    if (slot == 6) {
                        event.setCancelled(true);
                        this.savePersonalKitFromEditor(player, inventory, ffaName);
                        player.closeInventory();
                        player.sendMessage(PREFIX + "¡KIT personal guardado correctamente!");
                        return;
                    }
                    if (slot == 7) {
                        event.setCancelled(true);
                        this.resetToDefaultKit(player, inventory, ffaName);
                        player.sendMessage(PREFIX + "Kit reestablecido al predeterminado.");
                        return;
                    }
                    if (slot == 8) {
                        event.setCancelled(true);
                        player.closeInventory();
                        player.sendMessage(PREFIX + "Editor de kit cerrado.");
                        return;
                    }
                }

                if (event.isShiftClick() || event.getClick().isKeyboardClick()) {
                    event.setCancelled(true);
                }
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (this.isKitEditorInventory(event.getInventory())) {
            int topInventorySize = event.getView().getTopInventory().getSize();
            boolean topInventoryAffected = false;
            boolean playerInventoryAffected = false;

            for(int slot : event.getRawSlots()) {
                if (slot < topInventorySize) {
                    topInventoryAffected = true;
                    if (slot >= 0 && slot <= 3 || slot >= 9 && slot < 18 || slot == 6 || slot == 7 || slot == 8) {
                        event.setCancelled(true);
                        return;
                    }
                } else {
                    playerInventoryAffected = true;
                }
            }

            if (topInventoryAffected && playerInventoryAffected) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if (player.getOpenInventory() != null && this.isKitEditorInventory(player.getOpenInventory().getTopInventory())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        playerFFAs.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        final Player victim = event.getEntity();
        final Player killer = victim.getKiller();

        event.getDrops().clear();

        if (this.playerFFAs.containsKey(victim.getUniqueId())) {
            this.playerFFAs.remove(victim.getUniqueId());
        }

        if (killer == null || killer.equals(victim)) {
            return;
        }

        if (this.playerFFAs.containsKey(killer.getUniqueId())) {
            String ffaName = this.playerFFAs.get(killer.getUniqueId());
            Map<UUID, ItemStack[]> playerKits = this.personalKits.getOrDefault(ffaName, new HashMap<>());
            UUID playerUuid = killer.getUniqueId();

            final ItemStack[] kitItems;
            if (playerKits.containsKey(playerUuid)) {
                kitItems = playerKits.get(playerUuid);
            } else {
                kitItems = this.defaultKits.get(ffaName);
            }

            new BukkitRunnable() {
                public void run() {
                    killer.getInventory().clear();

                    for(int i = 0; i < 36; ++i) {
                        if (kitItems[i] != null) {
                            killer.getInventory().setItem(i, kitItems[i].clone());
                        }
                    }

                    ItemStack[] armorItems = new ItemStack[4];
                    System.arraycopy(kitItems, 36, armorItems, 0, 4);
                    killer.getInventory().setArmorContents(armorItems);
                    if (kitItems[40] != null) {
                        killer.getInventory().setItemInOffHand(kitItems[40].clone());
                    }

                    double currentHealth = killer.getHealth();
                    double healthGained = 20.0 - currentHealth;
                    DecimalFormat df = new DecimalFormat("#,##");
                    String formattedHealth = df.format(healthGained);

                    killer.sendActionBar("§x§f§f§2§c§2§c§lFFA §8➡ §x§f§f§5§a§6§4+" + formattedHealth + "❤§x§e§4§e§4§e§4 por asesinar a §x§5§d§e§2§f§f" + victim.getName());
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
        UUID playerUuid = player.getUniqueId();
        Map<UUID, ItemStack[]> playerKits = this.personalKits.getOrDefault(ffaName, new HashMap<>());
        ItemStack[] kitItems;
        if (playerKits.containsKey(playerUuid)) {
            kitItems = playerKits.get(playerUuid);
        } else {
            ItemStack[] defaultKit = this.defaultKits.get(ffaName);
            kitItems = new ItemStack[41];
            for(int i = 0; i < defaultKit.length; ++i) {
                if (defaultKit[i] != null) {
                    kitItems[i] = defaultKit[i].clone();
                }
            }
        }

        String smallCapsTitle = this.toSmallCaps("editor de kit: " + ffaName);
        Inventory editorInventory = Bukkit.createInventory(null, 54, smallCapsTitle);
        this.kitEditorInventories.put(editorInventory, ffaName);

        ItemStack blackGlass = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta blackMeta = blackGlass.getItemMeta();
        blackMeta.setDisplayName(" ");
        blackGlass.setItemMeta(blackMeta);

        for(int i = 0; i < 4; ++i) {
            if (kitItems[39 - i] != null) {
                editorInventory.setItem(i, kitItems[39 - i].clone());
            }
        }

        if (kitItems[40] != null) {
            editorInventory.setItem(5, kitItems[40].clone());
        }

        ItemStack saveButton = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
        ItemMeta saveMeta = saveButton.getItemMeta();
        saveMeta.setDisplayName("§aGuardar KIT Personal");
        saveButton.setItemMeta(saveMeta);
        editorInventory.setItem(6, saveButton);

        ItemStack resetButton = new ItemStack(Material.YELLOW_STAINED_GLASS_PANE);
        ItemMeta resetMeta = resetButton.getItemMeta();
        resetMeta.setDisplayName("§eReestablecer al Kit Predeterminado");
        resetButton.setItemMeta(resetMeta);
        editorInventory.setItem(7, resetButton);

        ItemStack closeButton = new ItemStack(Material.RED_STAINED_GLASS_PANE);
        ItemMeta closeMeta = closeButton.getItemMeta();
        closeMeta.setDisplayName("§cCerrar");
        closeButton.setItemMeta(closeMeta);
        editorInventory.setItem(8, closeButton);

        for(int i = 9; i < 18; ++i) {
            editorInventory.setItem(i, blackGlass);
        }

        for(int i = 0; i < 36; ++i) {
            if (kitItems[i] != null) {
                int editorSlot = (i < 9) ? 45 + i : i + 9;
                editorInventory.setItem(editorSlot, kitItems[i].clone());
            }
        }

        player.openInventory(editorInventory);
    }

    private void resetToDefaultKit(Player player, Inventory editorInventory, String ffaName) {
        ItemStack[] defaultKit = this.defaultKits.get(ffaName);
        for(int i = 0; i < 4; ++i) {
            editorInventory.setItem(i, defaultKit[39 - i] != null ? defaultKit[39 - i].clone() : null);
        }
        editorInventory.setItem(5, defaultKit[40] != null ? defaultKit[40].clone() : null);
        for(int i = 0; i < 36; ++i) {
            int editorSlot = i < 9 ? 45 + i : i + 9;
            editorInventory.setItem(editorSlot, defaultKit[i] != null ? defaultKit[i].clone() : null);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (this.isKitEditorInventory(event.getInventory())) {
            this.kitEditorInventories.remove(event.getInventory());
        }
    }

    @Override
    public void onDisable() {
        // En el cierre del servidor, SIEMPRE guardamos de forma síncrona para no perder datos.
        this.saveAllKitsAndSpawnsSync();
        this.kitEditorInventories.clear();
        if (this.levelingSystem != null) {
            this.levelingSystem.savePlayerData();
        }
    }

    private void savePersonalKitFromEditor(Player player, Inventory editorInventory, String ffaName) {
        UUID playerUuid = player.getUniqueId();
        ItemStack[] kitItems = new ItemStack[41];

        for(int i = 0; i < 4; ++i) {
            kitItems[39 - i] = editorInventory.getItem(i);
        }
        kitItems[40] = editorInventory.getItem(5);
        for(int i = 0; i < 36; ++i) {
            int editorSlot = (i < 9) ? 45 + i : i + 9;
            kitItems[i] = editorInventory.getItem(editorSlot);
        }

        // 1. Memoria (Instantáneo)
        Map<UUID, ItemStack[]> playerKits = this.personalKits.computeIfAbsent(ffaName, k -> new HashMap<>());
        playerKits.put(playerUuid, kitItems);

        // 2. Config Object (Instantáneo)
        String basePath = "ffas." + ffaName + ".personalKits." + playerUuid.toString();
        for(int i = 0; i < kitItems.length; ++i) {
            if (kitItems[i] != null) {
                this.ffaConfig.set(basePath + "." + i, kitItems[i]);
            } else {
                this.ffaConfig.set(basePath + "." + i, null);
            }
        }

        // 3. Escribir a Disco (ASÍNCRONO Y SEGURO)
        this.saveConfigAsync();

        player.getInventory().clear();
    }

    public FFALevelingSystem getLevelingSystem() {
        return this.levelingSystem;
    }

    private class FFACommandExecutor implements CommandExecutor {
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Este comando solo puede ser usado por jugadores.");
                return true;
            }

            Player player = (Player) sender;
            if (args.length < 1) {
                this.sendHelp(player);
                return true;
            }

            String subCommand = args[0].toLowerCase();

            if (Arrays.asList("create", "setspawn", "setkit", "delete").contains(subCommand)) {
                if (!player.hasPermission(ADMIN_PERMISSION)) {
                    player.sendMessage(PREFIX + "No tienes permiso para usar este comando.");
                    return true;
                }

                if (args.length < 2) {
                    player.sendMessage(PREFIX + "Uso correcto: /ffa " + subCommand + " <nombre>");
                    return true;
                }

                String ffaName = args[1].toLowerCase();

                switch (subCommand) {
                    case "create":
                        if (MagmaFFA.this.defaultKits.containsKey(ffaName)) {
                            player.sendMessage(PREFIX + "Ya existe una arena con ese nombre.");
                        } else {
                            MagmaFFA.this.defaultKits.put(ffaName, new ItemStack[41]);
                            MagmaFFA.this.personalKits.put(ffaName, new HashMap<>());
                            player.sendMessage(PREFIX + "Has creado la arena §e" + ffaName.toUpperCase());
                        }
                        break;
                    case "setspawn":
                        if (!MagmaFFA.this.defaultKits.containsKey(ffaName)) {
                            player.sendMessage(PREFIX + "Arena no encontrada.");
                        } else {
                            MagmaFFA.this.spawns.put(ffaName, player.getLocation());
                            player.sendMessage(PREFIX + "Spawn establecido para §e" + ffaName.toUpperCase());
                        }
                        break;
                    case "setkit":
                        if (!MagmaFFA.this.defaultKits.containsKey(ffaName)) {
                            player.sendMessage(PREFIX + "Arena no encontrada.");
                        } else {
                            ItemStack[] playerItems = new ItemStack[41];
                            System.arraycopy(player.getInventory().getContents(), 0, playerItems, 0, 36);
                            System.arraycopy(player.getInventory().getArmorContents(), 0, playerItems, 36, 4);
                            playerItems[40] = player.getInventory().getItemInOffHand();
                            MagmaFFA.this.defaultKits.put(ffaName, playerItems);
                            player.sendMessage(PREFIX + "Kit actualizado para §e" + ffaName.toUpperCase());
                        }
                        break;
                    case "delete":
                        if (!MagmaFFA.this.defaultKits.containsKey(ffaName)) {
                            player.sendMessage(PREFIX + "Arena no encontrada.");
                        } else {
                            MagmaFFA.this.defaultKits.remove(ffaName);
                            MagmaFFA.this.personalKits.remove(ffaName);
                            MagmaFFA.this.spawns.remove(ffaName);
                            player.sendMessage(PREFIX + "Has eliminado la arena §e" + ffaName.toUpperCase());
                        }
                        break;
                }
                return true;
            }
            else if (subCommand.equals("editkit")) {
                if (args.length < 2) {
                    player.sendMessage(PREFIX + "Uso: /ffa editkit <nombre>");
                    return true;
                }
                String ffaName = args[1].toLowerCase();
                if (!MagmaFFA.this.defaultKits.containsKey(ffaName)) {
                    player.sendMessage(PREFIX + "Arena no encontrada.");
                } else {
                    MagmaFFA.this.openKitEditor(player, ffaName);
                }
                return true;
            }
            else {
                String ffaName = subCommand;
                if (!MagmaFFA.this.defaultKits.containsKey(ffaName)) {
                    this.sendHelp(player);
                    return true;
                } else if (!MagmaFFA.this.spawns.containsKey(ffaName)) {
                    player.sendMessage(PREFIX + "Spawn no establecido para §e" + ffaName.toUpperCase());
                    return true;
                } else {
                    Map<UUID, ItemStack[]> playerKits = MagmaFFA.this.personalKits.getOrDefault(ffaName, new HashMap<>());
                    ItemStack[] kitItems = playerKits.getOrDefault(player.getUniqueId(), MagmaFFA.this.defaultKits.get(ffaName));

                    if(!playerKits.containsKey(player.getUniqueId())) {
                        player.sendMessage(MagmaFFA.this.TIP + "Personaliza tu kit con §8(§7/ffa editkit " + ffaName + "§8)");
                    }

                    MagmaFFA.this.playerFFAs.put(player.getUniqueId(), ffaName);

                    player.getInventory().clear();
                    for(PotionEffect effect : player.getActivePotionEffects()) {
                        player.removePotionEffect(effect.getType());
                    }

                    if(kitItems != null) {
                        for(int i = 0; i < 36; ++i) {
                            if (kitItems[i] != null) player.getInventory().setItem(i, kitItems[i].clone());
                        }
                        ItemStack[] armorItems = new ItemStack[4];
                        System.arraycopy(kitItems, 36, armorItems, 0, 4);
                        player.getInventory().setArmorContents(armorItems);
                        if (kitItems[40] != null) player.getInventory().setItemInOffHand(kitItems[40].clone());
                    }

                    player.setSaturation(20.0F);
                    player.setHealth(20.0);
                    player.setFoodLevel(20);
                    player.teleport(MagmaFFA.this.spawns.get(ffaName));
                    Bukkit.broadcastMessage(PREFIX + "§e" + player.getName() + " §fha entrado a la arena §e" + ffaName.toUpperCase());
                    return true;
                }
            }
        }

        private void sendHelp(Player player) {
            player.sendMessage(PREFIX.replace(" ▸ ", " » ") + "Comandos:");
            if (player.hasPermission(ADMIN_PERMISSION)) {
                player.sendMessage("§8• §e/ffa create <nombre> §8- §7Crear arena");
                player.sendMessage("§8• §e/ffa setspawn <nombre> §8- §7Setear spawn");
                player.sendMessage("§8• §e/ffa setkit <nombre> §8- §7Setear kit default");
            }
            player.sendMessage("§8• §e/ffa editkit <nombre> §8- §7Editar kit personal");
            player.sendMessage("§8• §e/ffa <nombre> §8- §7Unirse a una arena");

            if (!MagmaFFA.this.defaultKits.isEmpty()) {
                player.sendMessage(PREFIX.replace(" ▸ ", " » ") + "Arenas disponibles:");
                for(String name : MagmaFFA.this.defaultKits.keySet()) {
                    player.sendMessage("§8• §e" + name.toUpperCase());
                }
            }
        }
    }

    private class FFATabCompleter implements TabCompleter {
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            List<String> completions = new ArrayList<>();
            if (!(sender instanceof Player)) return completions;
            Player player = (Player) sender;

            if (args.length == 1) {
                List<String> options = new ArrayList<>(MagmaFFA.this.defaultKits.keySet());
                options.add("editkit");
                if (player.hasPermission(ADMIN_PERMISSION)) {
                    options.add("create");
                    options.add("setspawn");
                    options.add("setkit");
                    options.add("delete");
                }
                return filterCompletions(options, args[0]);
            } else if (args.length == 2) {
                String subCommand = args[0].toLowerCase();
                if (subCommand.equals("editkit") ||
                        (player.hasPermission(ADMIN_PERMISSION) && Arrays.asList("setspawn", "setkit", "delete").contains(subCommand))) {
                    return filterCompletions(new ArrayList<>(MagmaFFA.this.defaultKits.keySet()), args[1]);
                }
            }
            return completions;
        }

        private List<String> filterCompletions(List<String> options, String input) {
            return options.stream()
                    .filter(option -> option.toLowerCase().startsWith(input.toLowerCase()))
                    .collect(Collectors.toList());
        }
    }
}