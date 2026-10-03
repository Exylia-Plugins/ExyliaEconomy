package net.exylia.exyliaEconomy.config;

import net.exylia.lib.config.Comment;
import net.exylia.lib.config.ConfigFile;
import net.exylia.lib.config.Languages;

/**
 * Settings stored in {@code config.yml}.
 *
 * <p>Only what one server decides alone. The currencies and the economy's own settings live in
 * the database and are edited in game with {@code /economyadmin}, so every server of a network
 * reads the same ones.
 *
 * @param language the language of menus and messages
 * @param debug    whether the log explains what the plugin does
 */
@Comment("ExyliaEconomy. The currencies are kept in the database and edited in game with /economyadmin.")
public record EconomyConfig(
        @Comment("Language of this plugin's menus and messages. 'default' follows the language set in")
        @Comment("ExyliaLib's config.yml; en, es or pt set this plugin alone. Each one is a folder under")
        @Comment("lang/ you can edit.")
        String language,

        @Comment("Explains in the console what the plugin is doing.")
        boolean debug) {

    /** The defaults the file is generated from. */
    public EconomyConfig() {
        this(Languages.DEFAULT, false);
    }

    private static ConfigFile<EconomyConfig> file;

    /** Keeps the loaded file so {@link #get()} reads the current snapshot. */
    public static void install(ConfigFile<EconomyConfig> loaded) {
        file = loaded;
    }

    /** The current snapshot: a field access, never a re-parse. */
    public static EconomyConfig get() {
        return file == null ? new EconomyConfig() : file.get();
    }
}
