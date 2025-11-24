package us.magmamc.magmaFFA;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull; // Opcional, pero buena práctica si usas Paper
import org.jetbrains.annotations.Nullable;

public class FFAPlaceholderExpansion extends PlaceholderExpansion {

    private final MagmaFFA plugin;
    private final FFALevelingSystem levelingSystem;

    public FFAPlaceholderExpansion(MagmaFFA plugin, FFALevelingSystem levelingSystem) {
        this.plugin = plugin;
        this.levelingSystem = levelingSystem;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "magmaffa";
    }

    @Override
    public @NotNull String getAuthor() {
        // Mejora: Usa String.join para que salga "Autor1, Autor2" en vez de "[Autor1, Autor2]"
        return String.join(", ", this.plugin.getDescription().getAuthors());
    }

    @Override
    public @NotNull String getVersion() {
        return this.plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true; // Importante para que no se "desenganche" si recargas PAPI
    }

    @Override
    public @Nullable String onPlaceholderRequest(Player player, @NotNull String identifier) {
        if (player == null) {
            return "";
        }

        // Delegamos la búsqueda del placeholder al sistema de niveles que ya arreglamos
        return this.levelingSystem.getPlaceholder(player.getUniqueId(), identifier);
    }
}