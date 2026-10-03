package net.exylia.exyliaEconomy.service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The commands that ask before they move money: one open question per sender.
 *
 * <p>A confirmation is bound to exactly what was asked — who, to whom, which currency, how much —
 * so a {@code confirm} typed for anything else, or after the window, confirms nothing. Asking
 * again replaces the open question, and a confirmation is used up by the payment it allows.
 *
 * <p>Kept in memory on the server that asked: the click that confirms runs on that same server.
 */
final class Confirmations {

    /** How long a question stays open. */
    static final long WINDOW_MILLIS = 30_000L;

    private static final Map<String, Open> OPEN = new ConcurrentHashMap<>();

    private record Open(String what, long until) { }

    private Confirmations() {
        throw new AssertionError("No instances.");
    }

    /** What an amount is, for binding: {@code 2.5k}, {@code 2500} and {@code 2500.00} are one. */
    static String amount(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }

    /** Opens a question for a sender, replacing theirs. */
    static void ask(String who, String what, long now) {
        OPEN.values().removeIf(open -> open.until() < now);
        OPEN.put(who, new Open(what, now + WINDOW_MILLIS));
    }

    /** Whether the sender's open question is this one and still open; using it up when it is. */
    static boolean confirm(String who, String what, long now) {
        Open open = OPEN.get(who);
        if (open == null || open.until() < now || !open.what().equals(what)) return false;
        return OPEN.remove(who, open);
    }
}
