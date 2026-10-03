package net.exylia.exyliaEconomy.command;

import net.exylia.exyliaEconomy.service.EconomyActions;
import net.exylia.exyliaEconomy.Permissions;
import net.exylia.lib.player.PlayerTarget;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.CommandPlaceholder;
import revxrsal.commands.annotation.Default;
import revxrsal.commands.annotation.Optional;
import revxrsal.commands.annotation.Subcommand;
import revxrsal.commands.annotation.SuggestWith;
import revxrsal.commands.bukkit.annotation.CommandPermission;

/**
 * {@code /economy}: every currency, one command.
 *
 * <p>Three spellings reach the same actions, each for a different reader:
 * {@code /balance}, {@code /pay}, {@code /baltop} and {@code /wallet} for the
 * player who wants the usual thing in the usual words; this command for
 * everything at once, currency by currency; and the per-currency commands —
 * {@code /shards pay ...} — which are {@link AliasCommands}.
 *
 * <p>What changes somebody else's money lives in {@link EconomyAdminCommand}.
 * The spellings below are kept because reward, crate and shop configurations
 * on live servers are written with {@code /eco give}, and a command that
 * silently stops paying is a bug nobody notices for a week.
 *
 * <p>Targets are {@link PlayerTarget}, not {@code Player}: money outlives a
 * session, so every one of these has to work on somebody who is not here.
 * The type suggests every name the library knows — this server's and the
 * network's — and leaves the lookup to the handler, which is allowed to take
 * a moment.
 */
@Command({"economy", "eco"})
@CommandPermission(Permissions.USE)
public final class EconomyCommand {

    private final EconomyActions actions = new EconomyActions();

    @CommandPlaceholder
    public void root(CommandSender sender) {
        actions.wallet(sender, null);
    }

    @Subcommand("balance")
    public void balance(CommandSender sender,
                        @Optional @SuggestWith(CurrencySuggestionProvider.WithPlayers.class) String currency,
                        @Optional PlayerTarget player) {
        if (player == null && namesAPlayer(currency)) {
            actions.balance(sender, null, currency);
            return;
        }
        actions.balance(sender, currency, typed(player));
    }

    @Subcommand("wallet")
    public void wallet(CommandSender sender, @Optional PlayerTarget player) {
        actions.wallet(sender, typed(player));
    }

    @Subcommand("currencies")
    public void currencies(CommandSender sender) {
        actions.currencies(sender);
    }

    @Subcommand("pay")
    @CommandPermission(Permissions.PAY)
    public void pay(Player sender, PlayerTarget player, String amount,
                    @Optional @SuggestWith(CurrencySuggestionProvider.class) String currency) {
        actions.pay(sender, currency, typed(player), amount);
    }

    @Subcommand("top")
    public void top(CommandSender sender,
                    @Optional @SuggestWith(CurrencySuggestionProvider.class) String currency,
                    @Default("1") int page) {
        // '/eco top 2' is the second page of the default board, not a currency called 2.
        if (currency != null && currency.matches("\\d{1,9}") && EconomyActions.currency(currency).isEmpty()) {
            actions.top(sender, null, Integer.parseInt(currency));
            return;
        }
        actions.top(sender, currency, page);
    }

    @Subcommand("history")
    public void history(CommandSender sender,
                        @Optional @SuggestWith(CurrencySuggestionProvider.WithPlayers.class) String currency,
                        @Optional PlayerTarget player) {
        if (player == null && namesAPlayer(currency)) {
            actions.history(sender, null, currency);
            return;
        }
        actions.history(sender, currency, typed(player));
    }

    @Subcommand("exchange")
    public void exchange(Player sender, String amount,
                         @SuggestWith(CurrencySuggestionProvider.class) String from,
                         @SuggestWith(CurrencySuggestionProvider.class) String to) {
        actions.exchange(sender, from, to, amount);
    }

    // ---------------------------------------------------------------------
    // Kept for the configurations that already call them. /economyadmin is
    // where these belong and where they are documented.
    // ---------------------------------------------------------------------

    @Subcommand("give")
    @CommandPermission(Permissions.ADMIN)
    public void give(CommandSender sender, PlayerTarget player, String amount,
                     @Optional @SuggestWith(CurrencySuggestionProvider.class) String currency) {
        actions.give(sender, currency, typed(player), amount);
    }

    @Subcommand("take")
    @CommandPermission(Permissions.ADMIN)
    public void take(CommandSender sender, PlayerTarget player, String amount,
                     @Optional @SuggestWith(CurrencySuggestionProvider.class) String currency) {
        actions.take(sender, currency, typed(player), amount);
    }

    @Subcommand("set")
    @CommandPermission(Permissions.ADMIN)
    public void set(CommandSender sender, PlayerTarget player, String amount,
                    @Optional @SuggestWith(CurrencySuggestionProvider.class) String currency) {
        actions.set(sender, currency, typed(player), amount);
    }

    @Subcommand("reset")
    @CommandPermission(Permissions.ADMIN)
    public void reset(CommandSender sender, PlayerTarget player,
                      @Optional @SuggestWith(CurrencySuggestionProvider.class) String currency) {
        actions.reset(sender, currency, typed(player));
    }

    @Subcommand("import")
    @CommandPermission(Permissions.ADMIN)
    public void importFrom(CommandSender sender,
                           @SuggestWith(CurrencySuggestionProvider.class) String from,
                           @SuggestWith(CurrencySuggestionProvider.class) String into,
                           @Optional String again) {
        actions.importFrom(sender, from, into, "again".equalsIgnoreCase(again));
    }

    /**
     * Whether the one word typed is a player rather than a currency.
     *
     * <p>The currency comes first so that {@code /economy balance} needs no
     * argument at all, which leaves {@code /economy balance Notch} reading as
     * a currency nobody has heard of. A word that is not a currency is the
     * player it plainly is, asked about in the default one.
     */
    private static boolean namesAPlayer(@Nullable String word) {
        return word != null && !word.isBlank() && EconomyActions.currency(word).isEmpty();
    }

    /** What the sender typed, for the actions that look a name up themselves. */
    private static @Nullable String typed(@Nullable PlayerTarget target) {
        return target == null ? null : target.typed();
    }
}
