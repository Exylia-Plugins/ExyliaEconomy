package net.exylia.exyliaEconomy.manager;

import net.exylia.exyliaEconomy.database.PlayerFlagRow;
import net.exylia.lib.database.Databases;
import net.exylia.lib.database.Repository;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * The per-player switches that must hold on every server and through restarts.
 *
 * <p>Read from the database every time it matters, never cached: a toggle made on one server
 * applies on the next payment anywhere.
 */
public final class PlayerFlags {

    /** The player turned incoming payments off with {@code /paytoggle}. */
    public static final String PAY_OFF = "pay_off";

    /**
     * The player had {@code exyliaeconomy.top.exempt} when they last joined. Stored because a
     * permission cannot be asked of somebody offline, and the leaderboard ranks everybody.
     */
    public static final String TOP_EXEMPT = "top_exempt";

    private final Repository<PlayerFlagRow> rows;

    public PlayerFlags(@NotNull Plugin plugin) {
        this.rows = Databases.of(plugin).repository(PlayerFlagRow.class);
    }

    public @NotNull CompletableFuture<Boolean> has(@NotNull UUID player, @NotNull String flag) {
        return rows.exists(PlayerFlagRow.id(player, flag));
    }

    public @NotNull CompletableFuture<Void> set(@NotNull UUID player, @NotNull String flag, boolean on) {
        return on ? rows.save(new PlayerFlagRow(player, flag))
                : rows.delete(PlayerFlagRow.id(player, flag)).thenApply(ignored -> null);
    }

    /** Everybody with a flag on. */
    public @NotNull CompletableFuture<Set<UUID>> holders(@NotNull String flag) {
        return rows.where("flag", flag).find()
                .thenApply(found -> found.stream().map(PlayerFlagRow::uuid).collect(Collectors.toSet()));
    }
}
