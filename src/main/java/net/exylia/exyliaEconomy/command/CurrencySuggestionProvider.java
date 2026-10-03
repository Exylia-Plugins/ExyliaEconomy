package net.exylia.exyliaEconomy.command;

import net.exylia.exyliaEconomy.Permissions;
import net.exylia.lib.economy.Economy;
import net.exylia.lib.player.ExyliaPlayers;
import org.jetbrains.annotations.NotNull;
import revxrsal.commands.autocomplete.SuggestionProvider;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.node.ExecutionContext;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Suggests the currencies the sender may actually use.
 *
 * <p>A currency behind a permission is not offered to somebody who does not
 * have it: a suggestion the command then refuses is a worse answer than no
 * suggestion at all.
 */
public class CurrencySuggestionProvider implements SuggestionProvider<BukkitCommandActor> {

    @Override
    public @NotNull Collection<String> getSuggestions(@NotNull ExecutionContext<BukkitCommandActor> context) {
        List<String> ids = new ArrayList<>();
        for (String id : Economy.ordered()) {
            if (Economy.canUse(context.actor().sender(), id)) ids.add(id);
        }
        return ids;
    }

    /**
     * The currencies, then the players for whoever may read somebody else's:
     * where the first word may be either, as in {@code /economy balance}.
     */
    public static final class WithPlayers extends CurrencySuggestionProvider {

        @Override
        public @NotNull Collection<String> getSuggestions(@NotNull ExecutionContext<BukkitCommandActor> context) {
            List<String> options = new ArrayList<>(super.getSuggestions(context));
            if (context.actor().sender().hasPermission(Permissions.OTHERS)) options.addAll(ExyliaPlayers.names());
            return options;
        }
    }
}
