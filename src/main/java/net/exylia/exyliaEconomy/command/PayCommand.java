package net.exylia.exyliaEconomy.command;

import net.exylia.exyliaEconomy.service.EconomyActions;
import net.exylia.exyliaEconomy.Permissions;
import net.exylia.lib.player.PlayerTarget;
import org.bukkit.entity.Player;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.CommandPlaceholder;
import revxrsal.commands.annotation.Optional;
import revxrsal.commands.annotation.Suggest;
import revxrsal.commands.annotation.SuggestWith;
import revxrsal.commands.bukkit.annotation.CommandPermission;

/**
 * {@code /pay <player> <amount> [currency] [confirm]}: hand somebody money.
 *
 * <p>In the default currency unless a third word names another one, so the
 * command reads the way it does everywhere else and still reaches a server
 * that runs six.
 */
@Command("pay")
@CommandPermission(Permissions.PAY)
public final class PayCommand {

    private static final String CONFIRM = "confirm";

    private final EconomyActions actions = new EconomyActions();

    /**
     * {@code confirm} last sends a payment that asked first; written straight after the amount
     * it means the default currency.
     */
    @CommandPlaceholder
    public void pay(Player sender, PlayerTarget player, String amount,
                    @Optional @SuggestWith(CurrencySuggestionProvider.class) String currency,
                    @Optional @Suggest("confirm") String confirm) {
        boolean confirmed = CONFIRM.equalsIgnoreCase(confirm);
        if (confirm == null && CONFIRM.equalsIgnoreCase(currency)) {
            currency = null;
            confirmed = true;
        }
        actions.pay(sender, currency, player.typed(), amount, confirmed);
    }
}
