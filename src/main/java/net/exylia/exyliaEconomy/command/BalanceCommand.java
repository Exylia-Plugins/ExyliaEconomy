package net.exylia.exyliaEconomy.command;

import net.exylia.exyliaEconomy.service.EconomyActions;
import net.exylia.exyliaEconomy.Permissions;
import net.exylia.lib.player.PlayerTarget;
import org.bukkit.command.CommandSender;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.CommandPlaceholder;
import revxrsal.commands.annotation.Optional;
import revxrsal.commands.bukkit.annotation.CommandPermission;

/**
 * {@code /balance}: what you have, in the currency the server runs on.
 *
 * <p>The spelling every player already knows from every other server. It never
 * takes a currency: the default one is the point of it, and anything else is
 * {@code /wallet} or that currency's own command.
 */
@Command({"balance", "bal", "money"})
@CommandPermission(Permissions.USE)
public final class BalanceCommand {

    private final EconomyActions actions = new EconomyActions();

    @CommandPlaceholder
    public void balance(CommandSender sender, @Optional PlayerTarget player) {
        actions.balance(sender, null, player == null ? null : player.typed());
    }
}
