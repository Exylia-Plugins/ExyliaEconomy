package net.exylia.exyliaEconomy.command;

import net.exylia.exyliaEconomy.Permissions;
import net.exylia.exyliaEconomy.service.EconomyActions;
import org.bukkit.entity.Player;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.CommandPlaceholder;
import revxrsal.commands.bukkit.annotation.CommandPermission;

/**
 * {@code /paytoggle}: stop receiving payments, or start again.
 *
 * <p>Kept in the database, so it holds on every server and through restarts. Whoever has
 * {@code exyliaeconomy.paytoggle.bypass} pays regardless.
 */
@Command("paytoggle")
@CommandPermission(Permissions.PAYTOGGLE)
public final class PayToggleCommand {

    private final EconomyActions actions = new EconomyActions();

    @CommandPlaceholder
    public void toggle(Player sender) {
        actions.payToggle(sender);
    }
}
