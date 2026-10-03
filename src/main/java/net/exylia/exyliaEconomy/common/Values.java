package net.exylia.exyliaEconomy.common;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a message, a menu or an effect is about.
 *
 * <p>A named bag of values, built in one expression and handed to whichever of
 * the three needs it:
 *
 * <pre>{@code
 * Values values = Values.of().put("id", row.id());
 *
 * Messages.send(player, EconomyMessages.get().currencyCreated(), values);
 * plugin.getMenus().open(player, "menus/admin/currency_admin_list", values.map());
 * }</pre>
 *
 * <p>Those take the values in two different shapes — {@code Text.with} one at a
 * time, a menu a whole {@code Map} — and a screen usually feeds both. Building
 * the bag once and reading it twice is what stops a message and the menu it
 * opens from drifting apart.
 *
 * <p>Not thread-safe and not meant to be: one is built, used and dropped inside
 * a single handler.
 */
public final class Values {

    private final Map<String, Object> values = new LinkedHashMap<>();

    private Values() {
    }

    /** An empty bag. */
    public static Values of() {
        return new Values();
    }

    /** A bag holding one value. */
    public static Values of(String name, Object value) {
        return of().put(name, value);
    }

    /**
     * Adds a value.
     *
     * @param name  the placeholder name, without the percent signs
     * @param value what it stands for; {@code null} becomes an empty string,
     *              because a placeholder left unfilled is shown raw to a player
     * @return this bag
     */
    public Values put(String name, Object value) {
        values.put(name, value == null ? "" : value);
        return this;
    }

    /** The values, as a menu context takes them. */
    public Map<String, Object> map() {
        return values;
    }

    /**
     * Substitutes these values into a line, leaving everything else alone.
     *
     * <p>For text that is assembled before it is shown — a status label, an
     * effect line — where the line still carries its colours and its
     * {@code %papi%} placeholders, which are resolved when it is rendered.
     *
     * @param text the line
     * @return the line with these values in it
     */
    public String apply(String text) {
        if (text == null) return "";
        String filled = text;
        for (Map.Entry<String, Object> value : values.entrySet()) {
            filled = filled.replace("%" + value.getKey() + "%", String.valueOf(value.getValue()));
        }
        return filled;
    }
}
