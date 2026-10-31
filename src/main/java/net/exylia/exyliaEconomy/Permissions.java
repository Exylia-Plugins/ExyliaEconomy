package net.exylia.exyliaEconomy;

import net.exylia.lib.util.PermissionNumbers;
import org.bukkit.permissions.Permissible;

import java.math.BigDecimal;

/**
 * The permission nodes this plugin checks, as plugin.yml declares them.
 *
 * <p>Every player feature has a node everybody holds by default, so a server makes one a rank
 * perk by denying it to the default group. Redeeming a banknote never needs one.
 */
public final class Permissions {

    /** Read your balances: {@code /balance}, {@code /wallet}, {@code /baltop}, the currency commands. */
    public static final String USE = "exyliaeconomy.use";

    /** Send money to another player. */
    public static final String PAY = "exyliaeconomy.pay";

    /** Turn incoming payments off and on with {@code /paytoggle}. */
    public static final String PAYTOGGLE = "exyliaeconomy.paytoggle";

    /** Pay a player who turned incoming payments off. */
    public static final String PAYTOGGLE_BYPASS = "exyliaeconomy.paytoggle.bypass";

    /** Turn money into a banknote with {@code /withdraw}. */
    public static final String WITHDRAW = "exyliaeconomy.withdraw";

    /** Earn the interest {@code /economyadmin} sets on a currency. */
    public static final String INTEREST = "exyliaeconomy.interest";

    /** Left off the leaderboards; stored as the player joins, so it applies from their next join. */
    public static final String TOP_EXEMPT = "exyliaeconomy.top.exempt";

    /** Read somebody else's balance, wallet or history. */
    public static final String OTHERS = "exyliaeconomy.others";

    /** Swap one currency for another with {@code /economy exchange}. */
    public static final String EXCHANGE = "exyliaeconomy.exchange";

    /** Read your own ledger with {@code /economy history} and the wallet's history buttons. */
    public static final String HISTORY = "exyliaeconomy.history";

    /** Open the leaderboards with {@code /baltop}, {@code /economy top} and the wallet's buttons. */
    public static final String TOP = "exyliaeconomy.top";

    /** {@code tax.<n>}: a transfer tax of n percent; the lowest wins, and never above the currency's own. */
    public static final String TAX_PREFIX = "exyliaeconomy.tax.";

    /** Change balances and edit the currencies with {@code /economyadmin}. */
    public static final String ADMIN = "exyliaeconomy.admin";

    private Permissions() {
        throw new AssertionError("No instances.");
    }

    /**
     * The transfer tax this sender pays, in percent: the lowest {@code tax.<n>} they hold, as a
     * whole percent, when it is below the currency's own rate; otherwise the currency's rate. A
     * node only ever lowers the tax, so a currency with none stays untaxed.
     */
    public static BigDecimal transferTaxPercent(Permissible sender, BigDecimal configured) {
        int granted = PermissionNumbers.lowest(sender, TAX_PREFIX, Integer.MAX_VALUE);
        return granted == Integer.MAX_VALUE ? configured : configured.min(BigDecimal.valueOf(Math.max(0, granted)));
    }
}
