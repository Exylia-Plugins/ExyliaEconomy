package net.exylia.exyliaEconomy.command;

import net.exylia.lib.command.lamp.Suggestions;
import net.exylia.lib.player.ExyliaPlayer;
import net.exylia.lib.player.ExyliaPlayers;
import net.exylia.lib.player.PlayerTarget;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import revxrsal.commands.Lamp;
import revxrsal.commands.annotation.list.AnnotationList;
import revxrsal.commands.autocomplete.AsyncSuggestionProvider;
import revxrsal.commands.autocomplete.SuggestionProvider;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.command.CommandActor;
import revxrsal.commands.exception.SendableException;
import revxrsal.commands.node.ExecutionContext;
import revxrsal.commands.parameter.ParameterType;
import revxrsal.commands.stream.MutableStringStream;

import java.lang.reflect.Type;

/**
 * The library's player arguments, built in this plugin's own class loader.
 *
 * <h2>Why they are not simply imported</h2>
 * The server's library loader gives <em>every plugin its own copy</em> of
 * Lamp, because {@code libraries:} in a {@code plugin.yml} is per plugin.
 * ExyliaLib's {@code ParameterType} and this plugin's are therefore different
 * classes with the same name, and a factory built over there and handed to a
 * builder over here does not link:
 *
 * <pre>
 * LinkageError: loader constraint violation ...
 *   addParameterTypeFactory(ParameterType$Factory)
 *   ... have different Class objects for the type ParameterType$Factory
 * </pre>
 *
 * <p>Which is why this file exists in every plugin rather than once in the
 * library. What does cross the boundary is everything that is not a Lamp
 * type: {@link ExyliaPlayer} and {@link PlayerTarget} are ExyliaLib classes,
 * of which there is one copy, and {@link ExyliaPlayers#cached},
 * {@code names()}, {@code notFound(...)} and {@link Suggestions#matching}
 * take and return nothing but those, Bukkit and the JDK.
 *
 * <h2>What it registers</h2>
 * <ul>
 *   <li>{@link ExyliaPlayer} — anybody this server knows, resolved from
 *       memory while the command is parsed. Lamp calls {@code parse} on every
 *       tab keystroke, so it must not do I/O.</li>
 *   <li>{@link PlayerTarget} — anybody at all; the handler calls
 *       {@code then(sender, ...)} and the library goes to the proxy.</li>
 *   <li>The suggestion filter, which cuts every provider down to the word
 *       being typed. Lamp's Brigadier bridge sends a provider's whole list
 *       whatever the argument already reads.</li>
 * </ul>
 */
public final class PlayerArguments {

    private PlayerArguments() {
    }

    /** Registers both player arguments and the suggestion filter. */
    public static @NotNull Lamp.Builder<BukkitCommandActor> install(
            @NotNull Lamp.Builder<BukkitCommandActor> builder) {
        return builder
                .parameterTypes(types -> types
                        .addParameterType(ExyliaPlayer.class, new Known())
                        .addParameterType(PlayerTarget.class, new Target()))
                .suggestionProviders(providers -> providers.addProviderFactory(new Filtering()));
    }

    /** Sent when no tier could put an id to a name; the library owns the wording. */
    public static final class PlayerNotFoundException extends SendableException {

        private final String name;

        PlayerNotFoundException(String name) {
            super(name);
            this.name = name;
        }

        @Override
        public void sendTo(@NotNull CommandActor actor) {
            if (actor instanceof BukkitCommandActor bukkit) {
                ExyliaPlayers.notFound(bukkit.sender(), name);
            } else {
                actor.reply("No player named " + name + " was found.");
            }
        }
    }

    private static final class Known implements ParameterType<BukkitCommandActor, ExyliaPlayer> {

        @Override
        public ExyliaPlayer parse(@NotNull MutableStringStream input,
                                  @NotNull ExecutionContext<BukkitCommandActor> context) {
            String typed = input.readString();
            ExyliaPlayer found = ExyliaPlayers.cached(typed);
            if (found == null) {
                throw new PlayerNotFoundException(typed);
            }
            return found;
        }

        @Override
        public @NotNull SuggestionProvider<BukkitCommandActor> defaultSuggestions() {
            return context -> Suggestions.matching(context.input().source(), ExyliaPlayers.names());
        }
    }

    private static final class Target implements ParameterType<BukkitCommandActor, PlayerTarget> {

        @Override
        public PlayerTarget parse(@NotNull MutableStringStream input,
                                  @NotNull ExecutionContext<BukkitCommandActor> context) {
            String typed = input.readString();
            if (typed.isBlank()) {
                // A quoted empty argument, reported as a player nobody answers
                // to rather than as the record's own exception reaching Lamp.
                throw new PlayerNotFoundException(typed);
            }
            return new PlayerTarget(typed);
        }

        @Override
        public @NotNull SuggestionProvider<BukkitCommandActor> defaultSuggestions() {
            return context -> Suggestions.matching(context.input().source(), ExyliaPlayers.names());
        }
    }

    private static final class Filtering implements SuggestionProvider.Factory<BukkitCommandActor> {

        @Override
        public @Nullable SuggestionProvider<BukkitCommandActor> create(@NotNull Type type,
                                                                       @NotNull AnnotationList annotations,
                                                                       @NotNull Lamp<BukkitCommandActor> lamp) {
            SuggestionProvider<BukkitCommandActor> source =
                    lamp.findNextSuggestionProvider(type, annotations, this, lamp);
            // Nothing behind this factory suggests anything for this argument:
            // left alone, so Brigadier's own argument type keeps suggesting —
            // and filtering — the way the client expects.
            if (source.equals(SuggestionProvider.empty())) {
                return null;
            }
            if (source instanceof AsyncSuggestionProvider<?>) {
                @SuppressWarnings("unchecked")
                AsyncSuggestionProvider<BukkitCommandActor> async =
                        (AsyncSuggestionProvider<BukkitCommandActor>) source;
                return SuggestionProvider.fromAsync(context -> async.getSuggestionsAsync(context)
                        .thenApply(values -> Suggestions.matching(context.input().source(), values)));
            }
            return context -> Suggestions.matching(context.input().source(), source.getSuggestions(context));
        }
    }
}
