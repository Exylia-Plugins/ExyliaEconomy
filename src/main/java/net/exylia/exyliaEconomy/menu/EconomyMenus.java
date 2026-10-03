package net.exylia.exyliaEconomy.menu;

import net.exylia.exyliaEconomy.ExyliaEconomy;
import net.exylia.lib.config.Languages;
import net.exylia.lib.ui.Menus;
import net.exylia.lib.ui.PluginMenus;
import net.exylia.lib.ui.UiDefinition;
import net.exylia.lib.ui.UiEntry;
import net.exylia.lib.ui.UiSection;
import net.exylia.lib.ui.UiSession;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * This plugin's screens: read once from the jar, opened cheaply.
 *
 * <p>Every file under {@code lang/<code>/menus/} is compiled at enable and again on reload, so
 * opening one renders slots and never parses YAML.
 *
 * <h2>The admin screens are the plugin's, not the owner's</h2>
 * They are rewritten from the jar on every start, so a release always reaches them; their header
 * says so. The players' screens under {@code menus/user/} are the owner's: a release only adds
 * what is missing there.
 */
public final class EconomyMenus {

    /** Every screen, by the id the rest of the plugin opens it with: its path without the extension. */
    private static final List<String> IDS = List.of(
            CurrencyAdminMenus.LIST,
            CurrencyAdminMenus.EDIT,
            CurrencyAdminMenus.SETTINGS,
            WalletMenu.ID,
            TopMenu.ID,
            HistoryMenu.ID);

    private final ExyliaEconomy plugin;
    private final PluginMenus menus;

    public EconomyMenus(ExyliaEconomy plugin, String namespace) {
        this.plugin = plugin;
        this.menus = Menus.of(plugin.getPlugin(), namespace);
    }

    /** Writes the packaged screens out and compiles every one. */
    public void load() {
        Languages.replace(plugin.getPlugin(), ExyliaEconomy.class, "menus/admin");
        // The players' screens are the owner's to restyle: only what is missing is written.
        Languages.refresh(plugin.getPlugin(), ExyliaEconomy.class, "menus/user");
        for (String id : IDS) {
            YamlConfiguration config = read(id);
            if (config != null) menus.load(id, config);
        }
    }

    /**
     * Reads one menu file, or reports why it could not be read.
     *
     * <p>Parsed here rather than through {@code YamlConfiguration.loadConfiguration}, which
     * swallows a broken file and hands back an empty one: that registers a menu with no title and
     * no items. A file that does not parse is named in the log and left unregistered instead.
     */
    private YamlConfiguration read(String id) {
        File file = Languages.file(plugin.getPlugin(), id + ".yml");
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.load(file);
            return config;
        } catch (IOException | InvalidConfigurationException failure) {
            plugin.getDebug().error("The menu \"" + id + "\" could not be read, so it is not"
                    + " registered. Fix " + file.getPath() + " and reload.", failure);
            return null;
        }
    }

    /** Forgets every compiled screen and reads them all again. */
    public void reload() {
        menus.unload();
        load();
    }

    public void open(Player player, String id, Map<String, Object> context) {
        open(player, id, context, Map.<String, List<UiEntry>>of());
    }

    public void open(Player player, String id, Map<String, Object> context, List<UiEntry> rows) {
        open(player, id, context, Map.of(UiSection.MAIN, rows));
    }

    /**
     * Opens a menu with its list already filled.
     *
     * <p>Through {@code openNow} so the rows are in the menu before it is drawn: handed over
     * afterwards, every list slot is drawn twice, once as the pagination filler. The hop to the
     * player's thread is done here, because a caller coming back from a database read is not on it.
     */
    private void open(Player player, String id, Map<String, Object> context,
                      Map<String, ? extends List<UiEntry>> sections) {
        Optional<UiDefinition> definition = menus.definition(id);
        if (definition.isEmpty()) {
            plugin.getDebug().warn("Something asked to open the menu \"" + id + "\", which is not loaded.");
            return;
        }
        if (plugin.getTasks().isOwnedBy(player)) {
            menus.openNow(player, definition.get(), context, sections);
            return;
        }
        plugin.getTasks().runAtEntity(player, () -> menus.openNow(player, definition.get(), context, sections));
    }

    /** The session this player has open, when it is one of ours. */
    public Optional<UiSession> session(Player player) {
        return menus.session(player);
    }
}
