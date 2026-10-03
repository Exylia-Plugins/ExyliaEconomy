package net.exylia.exyliaEconomy.config;

import net.exylia.lib.config.Comment;
import net.exylia.lib.config.ConfigFile;
import net.exylia.lib.config.Languages;
import net.exylia.lib.economy.Economy;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Settings stored in {@code config.yml}.
 *
 * <p>Only what one server decides alone. The currencies and the economy's own settings live in
 * the database and are edited in game with {@code /economyadmin}, so every server of a network
 * reads the same ones.
 *
 * @param language the language of menus and messages
 * @param debug    whether the log explains what the plugin does
 * @param payConfirmAbove  per currency id, or {@code default}, the amount above which {@code /pay} asks first
 * @param offlinePayNotice whether a player is told on join what they were paid while away
 */
@Comment("ExyliaEconomy. The currencies are kept in the database and edited in game with /economyadmin.")
public record EconomyConfig(
        @Comment("Language of this plugin's menus and messages. 'default' follows the language set in")
        @Comment("ExyliaLib's config.yml; en, es or pt set this plugin alone. Each one is a folder under")
        @Comment("lang/ you can edit.")
        String language,

        @Comment("Explains in the console what the plugin is doing.")
        boolean debug,

        @Comment("A /pay above this amount asks the payer to confirm it within 30 seconds, by clicking")
        @Comment("[Confirm] or adding 'confirm' to the command. Keyed by currency id; 'default' is every currency")
        @Comment("not listed. Amounts read like the command's: 10000, 10k, 1.5m. 0 asks for nothing.")
        Map<String, String> payConfirmAbove,

        @Comment("Tells a player, as they join, what they were paid while they were away or on")
        @Comment("another server: one line per currency.")
        boolean offlinePayNotice) {

    /** The defaults the file is generated from. */
    public EconomyConfig() {
        this(Languages.DEFAULT, false, new LinkedHashMap<>(Map.of("default", "0")), true);
    }

    /** The amount above which a payment in this currency asks first, or {@code null} when it never does. */
    public @Nullable BigDecimal confirmAbove(String currency) {
        Map<String, String> limits = payConfirmAbove == null ? Map.of() : payConfirmAbove;
        String typed = limits.containsKey(currency) ? limits.get(currency) : limits.get("default");
        return Economy.parseAmount(typed);
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
