package net.exylia.exyliaEconomy.command;

import net.exylia.exyliaEconomy.ExyliaEconomy;
import net.exylia.exyliaEconomy.config.EconomyMessages;
import net.exylia.lib.text.Values;
import net.exylia.exyliaEconomy.model.CurrencyRules;
import net.exylia.exyliaEconomy.service.EconomyActions;
import net.exylia.exyliaEconomy.Permissions;
import net.exylia.lib.command.RuntimeCommand;
import net.exylia.lib.command.RuntimeCommands;
import net.exylia.lib.command.lamp.Suggestions;
import net.exylia.lib.economy.Economy;
import net.exylia.lib.player.ExyliaPlayers;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /coins}, {@code /gems}: a command per currency, named by
 * {@code /economyadmin}.
 *
 * <p>Installed through ExyliaLib's {@link RuntimeCommands}, because the names
 * are not known until the file is read and a Lamp command is declared by
 * annotation. Each one is {@code /economy} with the currency filled in, so
 * there is exactly one implementation of what "pay" means.
 */
public final class AliasCommands {

    private AliasCommands() {
    }

    /** Replaces every alias command with the ones the currencies name now. */
    public static void install(Plugin plugin, EconomyActions actions) {
        List<RuntimeCommand> commands = new ArrayList<>();
        for (CurrencyRules rules : EconomyActions.withCommands()) {
            List<String> aliases = rules.aliases();
            Alias alias = new Alias(aliases.get(0), rules.id(), actions);
            commands.add(RuntimeCommand.of(aliases.get(0), alias::execute)
                    .withAliases(aliases.subList(1, aliases.size()))
                    .withPermission(Permissions.USE)
                    .withDescription("Your " + rules.id() + " balance")
                    .withCompleter(alias::tabComplete));
        }
        RuntimeCommands.of(plugin).install(commands);
    }

    /** Takes every alias command down, for the plugin going away. */
    public static void uninstall() {
        RuntimeCommands.of(ExyliaEconomy.getInstance().getPlugin()).uninstall();
    }

    /** One currency's command. */
    private static final class Alias {

        private final String label;
        private final String currency;
        private final EconomyActions actions;

        Alias(String label, String currency, EconomyActions actions) {
            this.label = label;
            this.currency = currency;
            this.actions = actions;
        }

        void execute(CommandSender sender, String[] args) {
            if (args.length == 0) {
                actions.balance(sender, currency, null);
                return;
            }
            String sub = args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "pay" -> {
                    if (!sender.hasPermission(Permissions.PAY)) {
                        denied(sender);
                    } else if (args.length < 3 || !(sender instanceof Player player)) {
                        usage(sender, label);
                    } else {
                        actions.pay(player, currency, args[1], args[2]);
                    }
                }
                case "top" -> actions.top(sender, currency, args.length > 1 ? page(args[1]) : 1);
                case "history" -> actions.history(sender, currency, args.length > 1 ? args[1] : null);
                case "exchange" -> {
                    if (args.length < 3 || !(sender instanceof Player player)) {
                        usage(sender, label);
                    } else {
                        actions.exchange(player, currency, args[2], args[1]);
                    }
                }
                case "give", "take", "set", "reset" -> {
                    if (!sender.hasPermission(Permissions.ADMIN)) {
                        denied(sender);
                        return;
                    }
                    if (args.length < 2 || (!sub.equals("reset") && args.length < 3)) {
                        usage(sender, label);
                        return;
                    }
                    switch (sub) {
                        case "give" -> actions.give(sender, currency, args[1], args[2]);
                        case "take" -> actions.take(sender, currency, args[1], args[2]);
                        case "set" -> actions.set(sender, currency, args[1], args[2]);
                        default -> actions.reset(sender, currency, args[1],
                                args.length > 2 && "confirm".equalsIgnoreCase(args[2]));
                    }
                }
                default -> actions.balance(sender, currency, args[0]);
            }
        }

        /**
         * The same names {@code /economy} offers.
         *
         * <p>Not {@code null} — the server's own fallback is whoever is
         * online, and every one of these commands works on a player who is
         * not. {@link ExyliaPlayers#names()} is this server's players and the
         * network's, from memory.
         */
        List<String> tabComplete(CommandSender sender, String[] args) {
            if (args.length == 1) {
                List<String> options = new ArrayList<>(List.of("top", "history", "exchange"));
                if (sender.hasPermission(Permissions.PAY)) options.add(0, "pay");
                if (sender.hasPermission(Permissions.ADMIN)) {
                    options.addAll(List.of("give", "take", "set", "reset"));
                }
                // '/coins <player>' reads somebody else's balance, so the
                // names belong next to the subcommands, not instead of them.
                if (sender.hasPermission(Permissions.OTHERS)) options.addAll(ExyliaPlayers.names());
                return Suggestions.matching(args[0], options);
            }
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (args.length == 2) {
                boolean allowed = switch (sub) {
                    case "pay" -> sender.hasPermission(Permissions.PAY);
                    case "give", "take", "set", "reset" -> sender.hasPermission(Permissions.ADMIN);
                    case "history" -> sender.hasPermission(Permissions.OTHERS);
                    default -> false;
                };
                return allowed ? Suggestions.matching(args[1], ExyliaPlayers.names()) : List.of();
            }
            if (args.length == 3 && sub.equals("exchange")) {
                List<String> ids = new ArrayList<>();
                for (String id : Economy.ordered()) {
                    if (!id.equals(currency) && Economy.canUse(sender, id)) ids.add(id);
                }
                return Suggestions.matching(args[2], ids);
            }
            return List.of();
        }

        private static void denied(CommandSender sender) {
            ExyliaEconomy.getInstance().getMessages().send(sender, EconomyMessages.get().permissionDenied());
        }

        private static void usage(CommandSender sender, String label) {
            ExyliaEconomy.getInstance().getMessages().send(sender, EconomyMessages.get().usage(), Values.of().put("command", label));
        }

        private static int page(String typed) {
            try {
                return Math.max(1, Integer.parseInt(typed));
            } catch (NumberFormatException notANumber) {
                return 1;
            }
        }
    }
}
