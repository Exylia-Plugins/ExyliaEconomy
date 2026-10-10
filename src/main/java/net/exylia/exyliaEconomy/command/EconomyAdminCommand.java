package net.exylia.exyliaEconomy.command;

import net.exylia.exyliaEconomy.ExyliaEconomy;
import net.exylia.exyliaEconomy.config.EconomyMessages;
import net.exylia.exyliaEconomy.manager.CurrencyStore;
import net.exylia.exyliaEconomy.manager.StoredEconomy;
import net.exylia.exyliaEconomy.menu.CurrencyAdminMenus;
import net.exylia.exyliaEconomy.service.EconomyActions;
import net.exylia.exyliaEconomy.service.LedgerTools;
import net.exylia.exyliaEconomy.Permissions;
import net.exylia.lib.player.PlayerTarget;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.CommandPlaceholder;
import revxrsal.commands.annotation.Default;
import revxrsal.commands.annotation.Optional;
import revxrsal.commands.annotation.Subcommand;
import revxrsal.commands.annotation.Suggest;
import revxrsal.commands.annotation.SuggestWith;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;
import revxrsal.commands.node.ExecutionContext;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * {@code /economyadmin}: everything that changes somebody else's money.
 *
 * <p>Its own command rather than more subcommands of {@code /economy}, so what
 * a player may run and what an administrator may run are two different words.
 * The old spellings — {@code /eco give ...} — still work, because they are
 * written into reward and crate configurations on every server that has one,
 * and a command that silently stops paying is a bug nobody sees for a week.
 *
 * <p>Targets are {@link PlayerTarget}: money outlives a session, so every one
 * of these works on somebody who is not here.
 */
@Command({"economyadmin", "ecoadmin", "eadmin"})
@CommandPermission(Permissions.ADMIN)
public final class EconomyAdminCommand {

    private final EconomyActions actions = new EconomyActions();
    private final LedgerTools ledger = new LedgerTools();

    /** The currencies, to create, edit and delete in game. */
    @CommandPlaceholder
    public void open(Player player) {
        CurrencyStore store = StoredEconomy.store();
        if (store == null) {
            ExyliaEconomy.getInstance().getMessages().send(player, EconomyMessages.get().economyOff());
            return;
        }
        CurrencyAdminMenus.openList(player, store);
    }

    @Subcommand("give")
    public void give(CommandSender sender, PlayerTarget player, String amount,
                     @Optional @SuggestWith(CurrencySuggestionProvider.class) String currency) {
        actions.give(sender, currency, player.typed(), amount);
    }

    /** Gives every player online an amount; asks first, then {@code confirm} sends it. */
    @Subcommand("giveall")
    public void giveAll(CommandSender sender,
                        @SuggestWith(CurrencySuggestionProvider.class) String currency, String amount,
                        @Optional @Suggest("confirm") String confirm) {
        actions.giveAll(sender, currency, amount, "confirm".equalsIgnoreCase(confirm));
    }

    @Subcommand("take")
    public void take(CommandSender sender, PlayerTarget player, String amount,
                     @Optional @SuggestWith(CurrencySuggestionProvider.class) String currency) {
        actions.take(sender, currency, player.typed(), amount);
    }

    @Subcommand("set")
    public void set(CommandSender sender, PlayerTarget player, String amount,
                    @Optional @SuggestWith(CurrencySuggestionProvider.class) String currency) {
        actions.set(sender, currency, player.typed(), amount);
    }

    @Subcommand("reset")
    public void reset(CommandSender sender, PlayerTarget player,
                      @Optional @SuggestWith(CurrencySuggestionProvider.class) String currency) {
        actions.reset(sender, currency, player.typed());
    }

    /** Copies every known balance from one currency into another. Refused a second time unless {@code again}. */
    @Subcommand("import")
    public void importFrom(CommandSender sender,
                           @SuggestWith(CurrencySuggestionProvider.class) String from,
                           @SuggestWith(CurrencySuggestionProvider.class) String into,
                           @Optional String again) {
        actions.importFrom(sender, from, into, "again".equalsIgnoreCase(again));
    }

    /**
     * Reads the messages and the menus again, and the currencies and settings from the
     * database: the same as another server announcing an admin's edit.
     */
    @Subcommand("reload")
    public void reload(CommandSender sender) {
        ExyliaEconomy.getInstance().getReloads().run(sender);
    }

    /** A player's ledger lines with their ids, ten a page. */
    @Subcommand("log")
    public void log(CommandSender sender, PlayerTarget player,
                    @Optional @SuggestWith(CurrencySuggestionProvider.class) String currency,
                    @Default("1") int page) {
        // '/eadmin log Steve 2' is page 2 of the default currency, not a currency called 2.
        if (currency != null && currency.matches("\\d{1,9}") && EconomyActions.currency(currency).isEmpty()) {
            ledger.log(sender, player.typed(), null, Integer.parseInt(currency));
            return;
        }
        ledger.log(sender, player.typed(), currency, page);
    }

    /** Writes ledger lines to a CSV under exports/, every currency or one, optionally only the last days. */
    @Subcommand("export")
    public void export(CommandSender sender, @SuggestWith(ExportScopes.class) String currency, @Default("0") int days) {
        ledger.export(sender, currency, days);
    }

    /** Reverts a player's movements in a window ({@code 1h}, {@code 2d}) or one entry ({@code #42}), asking first. */
    @Subcommand("rollback")
    public void rollback(CommandSender sender, PlayerTarget player, @Suggest({"1h", "1d", "#"}) String window,
                         @Optional @SuggestWith(CurrencySuggestionProvider.class) String currency,
                         @Optional @Suggest("confirm") String confirm) {
        boolean confirmed = "confirm".equalsIgnoreCase(confirm);
        if (confirm == null && "confirm".equalsIgnoreCase(currency)) {
            currency = null;
            confirmed = true;
        }
        ledger.rollback(sender, player.typed(), window, currency, confirmed);
    }

    /** The currencies, and {@code all}. */
    public static final class ExportScopes extends CurrencySuggestionProvider {

        @Override
        public @NotNull Collection<String> getSuggestions(@NotNull ExecutionContext<BukkitCommandActor> context) {
            List<String> options = new ArrayList<>(super.getSuggestions(context));
            options.add(0, "all");
            return options;
        }
    }

    /** Every currency the server runs, with its id and what keeps it. */
    @Subcommand("currencies")
    public void currencies(CommandSender sender) {
        actions.currencies(sender);
    }
}
