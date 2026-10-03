package net.exylia.exyliaEconomy;

/**
 * The permission nodes this plugin checks, as plugin.yml declares them.
 *
 * <p>plugin.yml also declares the nodes these had while the economy lived in ExyliaSurvivalCore,
 * each with its new node as a child, so a permission setup written for those keeps working.
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

    /** Left off the leaderboards; stored as the player joins, so it applies from their next join. */
    public static final String TOP_EXEMPT = "exyliaeconomy.top.exempt";

    /** Read somebody else's balance, wallet or history. */
    public static final String OTHERS = "exyliaeconomy.others";

    /** Change balances and edit the currencies with {@code /economyadmin}. */
    public static final String ADMIN = "exyliaeconomy.admin";

    private Permissions() {
        throw new AssertionError("No instances.");
    }
}
