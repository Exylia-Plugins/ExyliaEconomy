package net.exylia.exyliaEconomy.command;

import net.exylia.exyliaEconomy.ExyliaEconomy;
import org.bukkit.entity.Player;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.CommandPlaceholder;

/**
 * {@code /deposit}: redeem the banknote in hand, the same as right-clicking it. Needs no
 * permission, like the right-click: money a player already holds is always theirs to take back.
 */
@Command("deposit")
public final class DepositCommand {

    @CommandPlaceholder
    public void deposit(Player sender) {
        ExyliaEconomy.getInstance().getBanknotes().deposit(sender);
    }
}
