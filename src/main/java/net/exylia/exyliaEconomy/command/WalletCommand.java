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
 * {@code /wallet}: every currency you hold, on one screen.
 *
 * <p>The multi-currency answer, so {@code /balance} can stay the one-number
 * question everybody expects it to be.
 */
@Command({"wallet", "balances"})
@CommandPermission(Permissions.USE)
public final class WalletCommand {

    private final EconomyActions actions = new EconomyActions();

    @CommandPlaceholder
    public void wallet(CommandSender sender, @Optional PlayerTarget player) {
        actions.wallet(sender, player == null ? null : player.typed());
    }
}
