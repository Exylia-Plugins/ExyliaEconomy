package net.exylia.exyliaEconomy.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A player is about to pay another with {@code /pay} or a currency's {@code pay}.
 *
 * <p>Fired once every rule has passed — the currency allows transfers, the payer can afford the
 * amount and the tax, the receiver accepts payments — and before any money moves. Cancel it and
 * nothing is taken from anybody.
 *
 * <p>The payer is told the payment was cancelled, in the plugin's own words, unless you set
 * {@link #cancelMessage(String)}: your line instead, or nothing at all for an empty one, when you
 * already told them yourself.
 *
 * <p>What moved afterwards is announced by ExyliaLib's {@code BalanceChangeEvent}, once per side,
 * on the server that wrote each balance.
 *
 * <p>Fired on the payer's thread: the main thread on Paper, their region's on Folia.
 */
public final class EconomyPayEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID receiver;
    private final String receiverName;
    private final String currency;
    private final BigDecimal amount;
    private final BigDecimal tax;
    private boolean cancelled;
    private String cancelMessage;

    /**
     * @param payer        who sends the money
     * @param receiver     who gets it; they may be offline or on another server
     * @param receiverName the receiver's name
     * @param currency     the currency's id
     * @param amount       what the receiver gets
     * @param tax          what the payer pays on top, kept by nobody; zero for none
     */
    public EconomyPayEvent(@NotNull Player payer, @NotNull UUID receiver, @NotNull String receiverName,
                           @NotNull String currency, @NotNull BigDecimal amount, @NotNull BigDecimal tax) {
        super(payer);
        this.receiver = receiver;
        this.receiverName = receiverName;
        this.currency = currency;
        this.amount = amount;
        this.tax = tax;
    }

    public @NotNull Player payer() {
        return getPlayer();
    }

    public @NotNull UUID receiver() {
        return receiver;
    }

    public @NotNull String receiverName() {
        return receiverName;
    }

    public @NotNull String currency() {
        return currency;
    }

    public @NotNull BigDecimal amount() {
        return amount;
    }

    public @NotNull BigDecimal tax() {
        return tax;
    }

    /** The line the payer gets when this is cancelled; {@code null} for the plugin's own. */
    public @Nullable String cancelMessage() {
        return cancelMessage;
    }

    /**
     * Replaces what the payer is told when this is cancelled.
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
