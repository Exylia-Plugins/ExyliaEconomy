package net.exylia.exyliaEconomy.command;

import net.exylia.exyliaEconomy.ExyliaEconomy;
import net.exylia.exyliaEconomy.Permissions;
import org.bukkit.entity.Player;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.CommandPlaceholder;
import revxrsal.commands.annotation.Optional;
import revxrsal.commands.annotation.SuggestWith;
import revxrsal.commands.bukkit.annotation.CommandPermission;

/** {@code /withdraw <amount> [currency]}: turn money into a banknote anybody can redeem. */
@Command("withdraw")
@CommandPermission(Permissions.WITHDRAW)
public final class WithdrawCommand {

    @CommandPlaceholder
    public void withdraw(Player sender, String amount,
                         @Optional @SuggestWith(CurrencySuggestionProvider.class) String currency) {
        ExyliaEconomy.getInstance().getBanknotes().withdraw(sender, amount, currency);
    }
}
