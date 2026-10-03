package net.exylia.exyliaEconomy.command;

import net.exylia.exyliaEconomy.ExyliaEconomy;
import net.exylia.exyliaEconomy.common.Messages;
import net.exylia.exyliaEconomy.config.EconomyMessages;
import net.exylia.exyliaEconomy.manager.CurrencyStore;
import net.exylia.exyliaEconomy.manager.StoredEconomy;
import net.exylia.exyliaEconomy.menu.CurrencyAdminMenus;
import net.exylia.exyliaEconomy.service.EconomyActions;
import net.exylia.exyliaEconomy.Permissions;
import net.exylia.lib.player.PlayerTarget;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.CommandPlaceholder;
import revxrsal.commands.annotation.Optional;
import revxrsal.commands.annotation.Subcommand;
import revxrsal.commands.annotation.SuggestWith;
import revxrsal.commands.bukkit.annotation.CommandPermission;

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

    /** The currencies, to create, edit and delete in game. */
    @CommandPlaceholder
    public void open(Player player) {
        CurrencyStore store = StoredEconomy.store();
        if (store == null) {
            Messages.send(player, EconomyMessages.get().economyOff());
            return;
        }
        CurrencyAdminMenus.openList(player, store);
    }

    @Subcommand("give")
    public void give(CommandSender sender, PlayerTarget player, String amount,
                     @Optional @SuggestWith(CurrencySuggestionProvider.class) String currency) {
        actions.give(sender, currency, player.typed(), amount);
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
     * Reads {@code config.yml}, the messages and the menus again, and the currencies from the
     * database: the same as another server announcing an admin's edit.
     */
    @Subcommand("reload")
    public void reload(CommandSender sender) {
        ExyliaEconomy.getInstance().getReloads().run(sender);
    }

    /** Every currency the server runs, with its id and what keeps it. */
    @Subcommand("currencies")
    public void currencies(CommandSender sender) {
        actions.currencies(sender);
    }
}
