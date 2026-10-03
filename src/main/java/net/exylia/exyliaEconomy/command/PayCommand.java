package net.exylia.exyliaEconomy.command;

import net.exylia.exyliaEconomy.service.EconomyActions;
import net.exylia.exyliaEconomy.Permissions;
import net.exylia.lib.player.PlayerTarget;
import org.bukkit.entity.Player;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.CommandPlaceholder;
import revxrsal.commands.annotation.Optional;
import revxrsal.commands.annotation.SuggestWith;
import revxrsal.commands.bukkit.annotation.CommandPermission;

/**
 * {@code /pay <player> <amount>}: hand somebody money.
 *
 * <p>In the default currency unless a third word names another one, so the
 * command reads the way it does everywhere else and still reaches a server
 * that runs six.
 */
@Command("pay")
@CommandPermission(Permissions.PAY)
public final class PayCommand {

    private final EconomyActions actions = new EconomyActions();

    @CommandPlaceholder
    public void pay(Player sender, PlayerTarget player, String amount,
                    @Optional @SuggestWith(CurrencySuggestionProvider.class) String currency) {
        actions.pay(sender, currency, player.typed(), amount);
    }
}
