package net.exylia.exyliaEconomy.command;

import net.exylia.exyliaEconomy.ExyliaEconomy;
import net.exylia.exyliaEconomy.Permissions;
import org.bukkit.entity.Player;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.CommandPlaceholder;
import revxrsal.commands.bukkit.annotation.CommandPermission;

/** {@code /deposit}: redeem the banknote in hand, the same as right-clicking it. */
@Command("deposit")
@CommandPermission(Permissions.USE)
public final class DepositCommand {

    @CommandPlaceholder
    public void deposit(Player sender) {
        ExyliaEconomy.getInstance().getBanknotes().deposit(sender);
    }
}
