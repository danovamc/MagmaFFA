package us.magmamc.magmaFFA.managers;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import us.magmamc.magmaFFA.MagmaFFA;
import us.magmamc.magmaFFA.utils.InventorySerializer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerManager {

    private final MagmaFFA plugin;
    // Cache en memoria: Solo jugadores conectados
    private final Map<UUID, PlayerData> playerDataCache = new ConcurrentHashMap<>();
    // Cache de kits: UUID -> (FFA Name -> Kit Items)
    private final Map<UUID, Map<String, ItemStack[]>> kitCache = new ConcurrentHashMap<>();

    public PlayerManager(MagmaFFA plugin) {
        this.plugin = plugin;
        startAutoSaveTask();
    }

    // --- TAREA DE AUTO-GUARDADO (Optimización) ---

    private void startAutoSaveTask() {
        // Leer configuración
        int intervalMinutes = plugin.getConfig().getInt("optimization.auto-save-interval", 10);

        // Si es 0 o menos, desactivar
        if (intervalMinutes <= 0) return;

        long intervalTicks = intervalMinutes * 60L * 20L; // Minutos * Segundos * Ticks

        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (playerDataCache.isEmpty()) return;

            plugin.getLogger().info("Ejecutando auto-guardado de datos (Optimizado)...");
            int count = 0;

            // Iteramos sobre los jugadores en caché (solo los conectados)
            for (UUID uuid : playerDataCache.keySet()) {
                savePlayerSyncToDb(uuid); // Guardamos sin limpiar memoria
                count++;
            }

        }, intervalTicks, intervalTicks);
    }

    // --- LOGICA DE CACHÉ ---

    public void loadPlayer(UUID uuid) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            // 1. Cargar Stats
            try (Connection conn = plugin.getDatabaseManager().getConnection()) {
                PreparedStatement stmt = conn.prepareStatement("SELECT level, xp FROM magma_players WHERE uuid = ?");
                stmt.setString(1, uuid.toString());
                ResultSet rs = stmt.executeQuery();

                if (rs.next()) {
                    playerDataCache.put(uuid, new PlayerData(rs.getInt("level"), rs.getInt("xp")));
                } else {
                    playerDataCache.put(uuid, new PlayerData(1, 0)); // Default
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }

            // 2. Cargar Kits
            try (Connection conn = plugin.getDatabaseManager().getConnection()) {
                PreparedStatement stmt = conn.prepareStatement("SELECT ffa_name, kit_data FROM magma_kits WHERE uuid = ?");
                stmt.setString(1, uuid.toString());
                ResultSet rs = stmt.executeQuery();

                Map<String, ItemStack[]> kits = new HashMap<>();
                while (rs.next()) {
                    String ffaName = rs.getString("ffa_name");
                    String base64 = rs.getString("kit_data");
                    kits.put(ffaName, InventorySerializer.fromBase64(base64));
                }
                kitCache.put(uuid, kits);
            } catch (SQLException e) {
                e.printStackTrace();
            }
        });
    }

    /**
     * Guarda los datos del jugador en la DB pero MANTIENE la caché.
     * Útil para auto-save o checkpoints.
     */
    public void savePlayer(UUID uuid) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> savePlayerSyncToDb(uuid));
    }

    /**
     * Guarda los datos y LIMPIA la caché de forma ASÍNCRONA.
     * Útil para cuando el jugador se desconecta (Quit) durante el juego normal.
     */
    public void saveAndUnloadPlayer(UUID uuid) {
        // Guardamos async
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> savePlayerSyncToDb(uuid));

        // Limpiamos memoria inmediatamente (ya que el jugador se fue)
        playerDataCache.remove(uuid);
        kitCache.remove(uuid);
    }

    /**
     * Guarda los datos y LIMPIA la caché de forma SÍNCRONA.
     * Útil y OBLIGATORIO para onDisable (apagado del servidor).
     */
    public void saveAndUnloadPlayerSync(UUID uuid) {
        savePlayerSyncToDb(uuid);
        playerDataCache.remove(uuid);
        kitCache.remove(uuid);
    }

    // Método interno que realiza la escritura SQL real
    private void savePlayerSyncToDb(UUID uuid) {
        if (!playerDataCache.containsKey(uuid)) return;

        PlayerData data = playerDataCache.get(uuid);
        Map<String, ItemStack[]> kits = kitCache.get(uuid);

        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            // Guardar Stats (Upsert)
            PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO magma_players (uuid, level, xp) VALUES (?, ?, ?) " +
                            "ON DUPLICATE KEY UPDATE level = ?, xp = ?");
            stmt.setString(1, uuid.toString());
            stmt.setInt(2, data.getLevel());
            stmt.setInt(3, data.getXp());
            stmt.setInt(4, data.getLevel());
            stmt.setInt(5, data.getXp());
            stmt.executeUpdate();

            // Guardar Kits (Si existen)
            if (kits != null && !kits.isEmpty()) {
                PreparedStatement kitStmt = conn.prepareStatement(
                        "INSERT INTO magma_kits (uuid, ffa_name, kit_data) VALUES (?, ?, ?) " +
                                "ON DUPLICATE KEY UPDATE kit_data = ?");

                for (Map.Entry<String, ItemStack[]> entry : kits.entrySet()) {
                    String base64 = InventorySerializer.toBase64(entry.getValue());
                    kitStmt.setString(1, uuid.toString());
                    kitStmt.setString(2, entry.getKey());
                    kitStmt.setString(3, base64);
                    kitStmt.setString(4, base64);
                    kitStmt.addBatch();
                }
                kitStmt.executeBatch();
            }

        } catch (SQLException e) {
            plugin.getLogger().severe("Error guardando datos SQL para " + uuid + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    // --- GETTERS Y SETTERS ---

    public int getLevel(UUID uuid) {
        return playerDataCache.getOrDefault(uuid, new PlayerData(1, 0)).getLevel();
    }

    public int getXp(UUID uuid) {
        return playerDataCache.getOrDefault(uuid, new PlayerData(1, 0)).getXp();
    }

    public void setLevel(UUID uuid, int level) {
        playerDataCache.computeIfAbsent(uuid, k -> new PlayerData(1, 0)).setLevel(level);
    }

    public void setXp(UUID uuid, int xp) {
        playerDataCache.computeIfAbsent(uuid, k -> new PlayerData(1, 0)).setXp(xp);
    }

    public ItemStack[] getPersonalKit(UUID uuid, String ffaName) {
        if (kitCache.containsKey(uuid)) {
            return kitCache.get(uuid).get(ffaName);
        }
        return null;
    }

    public void savePersonalKit(UUID uuid, String ffaName, ItemStack[] items) {
        kitCache.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).put(ffaName, items);
        // Guardar async inmediatamente para seguridad
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection conn = plugin.getDatabaseManager().getConnection()) {
                PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO magma_kits (uuid, ffa_name, kit_data) VALUES (?, ?, ?) " +
                                "ON DUPLICATE KEY UPDATE kit_data = ?");
                String base64 = InventorySerializer.toBase64(items);
                stmt.setString(1, uuid.toString());
                stmt.setString(2, ffaName);
                stmt.setString(3, base64);
                stmt.setString(4, base64);
                stmt.executeUpdate();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        });
    }

    public static class PlayerData {
        private int level;
        private int xp;

        public PlayerData(int level, int xp) {
            this.level = level;
            this.xp = xp;
        }

        public int getLevel() { return level; }
        public void setLevel(int level) { this.level = level; }
        public int getXp() { return xp; }
        public void setXp(int xp) { this.xp = xp; }
    }
}