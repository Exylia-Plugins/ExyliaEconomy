package net.exylia.exyliaEconomy.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;

/**
 * A player is about to exchange one currency for another.
 *
 * <p>Fired before the rate is applied and before any money moves; the exchange may still be
 * refused afterwards by its own rules (no rate, not enough money, the target's ceiling). Cancel
 * it and nothing changes. The player is told it was cancelled unless you set
 * {@link #cancelMessage(String)}: your line instead, or nothing at all for an empty one.
 *
 * <p>Fired on the player's thread: the main thread on Paper, their region's on Folia.
 */
public final class EconomyExchangeEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String from;
    private final String to;
    private final BigDecimal amount;
    private boolean cancelled;
    private String cancelMessage;

    /**
     * @param player who exchanges
     * @param from   the currency given, by id
     * @param to     the currency asked for, as typed
     * @param amount how much of {@code from} was typed
     */
    public EconomyExchangeEvent(@NotNull Player player, @NotNull String from, @NotNull String to,
                                @NotNull BigDecimal amount) {
        super(player);
        this.from = from;
        this.to = to;
        this.amount = amount;
    }

    public @NotNull String from() {
        return from;
    }

    public @NotNull String to() {
        return to;
    }

    public @NotNull BigDecimal amount() {
        return amount;
    }

    /** The line the player gets when this is cancelled; {@code null} for the plugin's own. */
    public @Nullable String cancelMessage() {
        return cancelMessage;
    }

    /**
     * Replaces what the player is told when this is cancelled.
     *
     * @param message a line in ExyliaLib's text format; empty to say nothing, {@code null} for the
     *                plugin's own
     */
    public void cancelMessage(@Nullable String message) {
        this.cancelMessage = message;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
