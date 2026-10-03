package net.exylia.exyliaEconomy.command;

import net.exylia.exyliaEconomy.service.EconomyActions;
import net.exylia.exyliaEconomy.Permissions;
import org.bukkit.command.CommandSender;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.CommandPlaceholder;
import revxrsal.commands.annotation.Default;
import revxrsal.commands.bukkit.annotation.CommandPermission;

/**
 * {@code /baltop}: who has the most, in the default currency.
 *
 * <p>A player gets the board as a screen; the page argument is for the console,
 * which has nowhere to click.
 */
@Command({"baltop", "balancetop", "moneytop"})
@CommandPermission(Permissions.USE)
public final class BalanceTopCommand {

    private final EconomyActions actions = new EconomyActions();

    @CommandPlaceholder
    public void top(CommandSender sender, @Default("1") int page) {
        actions.top(sender, null, page);
    }
}
