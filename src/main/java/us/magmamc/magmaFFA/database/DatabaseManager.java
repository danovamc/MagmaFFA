package us.magmamc.magmaFFA.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import us.magmamc.magmaFFA.MagmaFFA;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class DatabaseManager {

    private final MagmaFFA plugin;
    private HikariDataSource dataSource;

    public DatabaseManager(MagmaFFA plugin) {
        this.plugin = plugin;
        setupPool();
        createTables();
    }

    private void setupPool() {
        HikariConfig config = new HikariConfig();
        String host = plugin.getConfig().getString("database.host", "localhost");
        String port = plugin.getConfig().getString("database.port", "3306");
        String database = plugin.getConfig().getString("database.name", "magmaffa");
        String username = plugin.getConfig().getString("database.username", "root");
        String password = plugin.getConfig().getString("database.password", "");

        config.setJdbcUrl("jdbc:mariadb://" + host + ":" + port + "/" + database);
        config.setUsername(username);
        config.setPassword(password);

        // Optimizaciones de Hikari
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.setMaximumPoolSize(10);
        config.setPoolName("MagmaFFA-Pool");

        dataSource = new HikariDataSource(config);
    }

    private void createTables() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            // Tabla de estadísticas (Nivel, XP)
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS magma_players (" +
                    "uuid VARCHAR(36) PRIMARY KEY, " +
                    "level INT DEFAULT 1, " +
                    "xp INT DEFAULT 0" +
                    ")");

            // Tabla de kits personales
            // Usamos TEXT para guardar el Base64 del inventario
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS magma_kits (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "uuid VARCHAR(36), " +
                    "ffa_name VARCHAR(64), " +
                    "kit_data MEDIUMTEXT, " +
                    "UNIQUE KEY unique_kit (uuid, ffa_name)" +
                    ")");

        } catch (SQLException e) {
            plugin.getLogger().severe("Error creando tablas SQL: " + e.getMessage());
        }
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}