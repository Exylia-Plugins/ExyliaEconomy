package net.exylia.exyliaEconomy.placeholder;

import net.exylia.exyliaEconomy.manager.StoredEconomy;
import net.exylia.lib.economy.CurrencyInfo;
import net.exylia.lib.economy.Economy;
import net.exylia.lib.placeholder.Placeholders;
import net.exylia.lib.placeholder.Request;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code %economy_...%}: balances and leaderboards of every currency.
 *
 * <pre>
 * %economy_balance%              the default currency, formatted
 * %economy_balance_coins%        one currency, formatted
 * %economy_compact_coins%        the same, short
 * %economy_raw_coins%            the number alone
 * %economy_name_coins%           the currency's plural name
 * %economy_name_singular_coins%  one unit's name
 * %economy_name_plural_coins%    the plural, as name
 * %economy_rank_coins%           the viewer's place on the leaderboard, or — past it
 * %economy_total_coins%          every balance added up: the money supply
 * %economy_symbol_coins%
 * %economy_top_name_1_coins%     the richest player
 * %economy_top_amount_1_coins%
 * </pre>
 *
 * <p>Every one reads memory: a loaded balance, the balance cache or the
 * cached leaderboard. None touches the database on the thread that asked.
 */
public final class EconomyPlaceholder {

    private EconomyPlaceholder() {
        throw new AssertionError("No instances.");
    }

    public static void register(Plugin plugin) {
        Placeholders.group(plugin, "economy")
                .describe("Balances and leaderboards of every currency")
                .add("balance", request -> info(request, 0).format(balance(request)))
                .add("compact", request -> info(request, 0).formatCompact(balance(request)))
                .add("raw", request -> info(request, 0).scale(balance(request)).toPlainString())
                .add("name", request -> info(request, 0).namePlural())
                .add("name_singular", request -> info(request, 0).name())
                .add("name_plural", request -> info(request, 0).namePlural())
                .add("rank", EconomyPlaceholder::rank)
                .add("total", request -> info(request, 0).format(StoredEconomy.total(id(request, 0))))
                .add("symbol", request -> info(request, 0).symbol())
                .add("top_name", request -> top(request).map(StoredEconomy.TopEntry::name).orElse("—"))
                .add("top_amount", request -> top(request)
                        .map(entry -> info(request, 1).format(entry.amount()))
                        .orElse(info(request, 1).format(BigDecimal.ZERO)))
                .register();
    }

    /** The currency named by one argument, or the default when it names none. */
    private static CurrencyInfo info(Request request, int argument) {
        String id = request.arg(argument, "");
        return Economy.info(id.isEmpty() ? null : id);
    }

    private static BigDecimal balance(Request request) {
        UUID viewer = request.requireViewer().getUniqueId();
        return Economy.of(request.arg(0, "")).balance(viewer);
    }

    /** A currency argument as the leaderboard keys it: canonical, the default when absent. */
    private static String id(Request request, int argument) {
        String id = request.arg(argument, "");
        return id.isEmpty() ? Economy.defaultId() : Economy.canonical(id);
    }

    /** The viewer's place among the cached leaderboard, which holds the first hundred. */
    private static String rank(Request request) {
        UUID viewer = request.requireViewer().getUniqueId();
        for (StoredEconomy.TopEntry entry : StoredEconomy.top(id(request, 0), Integer.MAX_VALUE)) {
            if (entry.player().equals(viewer)) return String.valueOf(entry.position());
        }
        return "—";
    }

    /** {@code top_name_<position>_<currency>}. */
    private static Optional<StoredEconomy.TopEntry> top(Request request) {
        int position = Math.max(1, request.arg(0, 1));
        List<StoredEconomy.TopEntry> entries = StoredEconomy.top(id(request, 1), position);
        return entries.size() >= position ? Optional.of(entries.get(position - 1)) : Optional.empty();
    }
}
