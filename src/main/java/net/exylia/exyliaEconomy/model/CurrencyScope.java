package net.exylia.exyliaEconomy.model;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Which servers share one player's balance of a stored currency.
 *
 * <p>The group is the server's {@code network-group} in ExyliaLib's
 * {@code config.yml}: two survivals in group {@code survival} share one balance,
 * a boxpvp server in group {@code boxpvp} keeps another.
 */
public enum CurrencyScope {
    /** One balance on every server sharing the database. */
    GLOBAL,
    /** One balance per network group; a server with no group keeps its own. */
    GROUP,
    /** One balance per server. */
    SERVER;

    /** The next one an admin's click cycles to. */
    public CurrencyScope next() {
        return values()[(ordinal() + 1) % values().length];
    }

    /**
     * Reads a stored or typed scope.
     *
     * @param text      {@code global}, {@code group} or {@code server}, any case
     * @param networked what a row written before scopes existed said instead
     */
    public static CurrencyScope parse(@Nullable String text, boolean networked) {
        if (text != null && !text.isBlank()) {
            try {
                return valueOf(text.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // An unknown word falls back to the switch it replaced.
            }
        }
        return networked ? GLOBAL : SERVER;
    }
}
