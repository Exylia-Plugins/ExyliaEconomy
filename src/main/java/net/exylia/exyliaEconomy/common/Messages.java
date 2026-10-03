package net.exylia.exyliaEconomy.common;

import net.exylia.exyliaEconomy.ExyliaEconomy;
import net.exylia.lib.text.Text;
import org.bukkit.command.CommandSender;

import java.util.Map;

/**
 * Sending one of this plugin's lines.
 *
 * <p>Always {@code Text.from} and never {@code Text.of}: every line in
 * {@code messages.yml} opens with {@code %prefix%}, and only {@code from} knows
 * which plugin's prefix that is. The effect tags a line may open with —
 * {@code [sound:NAME|volume|pitch]} — are read by the same call, so a message
 * that plays a sound needs nothing else.
 *
 * <p>A blank line sends nothing at all. That is how a server owner turns one
 * message off: emptying the value in the file, rather than having to find the
 * code that sends it.
 */
public final class Messages {

    private Messages() {
        throw new AssertionError("No instances.");
    }

    /** Sends a line to one player or to the console. */
    public static void send(CommandSender to, String message) {
        if (to == null || isBlank(message)) return;
        Text.from(ExyliaEconomy.getInstance().getPlugin(), message).send(to);
    }

    /** Sends a line with the values it is about. */
    public static void send(CommandSender to, String message, Values values) {
        if (to == null || isBlank(message)) return;
        build(message, values).send(to);
    }

    /**
     * The line, with its values filled in.
     *
     * <p>The names in a {@link Values} bag are written without percent signs,
     * because that is the spelling a menu context takes; {@code Text.with}
     * wants the exact text it replaces, so the signs go back on here.
     */
    private static Text build(String message, Values values) {
        Text text = Text.from(ExyliaEconomy.getInstance().getPlugin(), message);
        if (values != null) {
            for (Map.Entry<String, Object> value : values.map().entrySet()) {
                text = text.withFormatted("%" + value.getKey() + "%", value.getValue());
            }
        }
        return text;
    }

    private static boolean isBlank(String message) {
        return message == null || message.isBlank();
    }
}
