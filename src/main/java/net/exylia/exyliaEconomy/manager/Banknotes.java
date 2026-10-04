package net.exylia.exyliaEconomy.manager;

import net.exylia.exyliaEconomy.database.BanknoteRow;
import net.exylia.lib.database.Databases;
import net.exylia.lib.database.Repository;
import net.exylia.lib.database.internal.Outages;
import net.exylia.lib.economy.EconomyResponse;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * The banknotes /withdraw printed, as the database keeps them.
 *
 * <p>A note is worth something exactly once: redeeming claims its row by a compare-and-set on
 * {@code redeemedAt}, before any money moves, so of two copies of one note — a duplicated stack, a
 * creative clone, two servers at once — only one is ever paid. A credit refused after the claim
 * gives the claim back, so the note keeps its worth.
 */
public final class Banknotes {

    /** How a redeem ended. */
    public enum Outcome {
        PAID, ALREADY_REDEEMED, UNKNOWN_NOTE, REFUSED,
        /** Refused, and the claim could not be given back: logged at SEVERE with the note's id. */
        STUCK
    }

    private static final int RELEASE_ATTEMPTS = 3;
    private static final long RELEASE_RETRY_MILLIS = 1_000L;

    /**
     * @param note   the note's row, {@code null} when there is none
     * @param credit what the currency answered, {@code null} unless it was asked
     */
    public record Redeem(@NotNull Outcome outcome, @Nullable BanknoteRow note, @Nullable EconomyResponse credit) { }

    private final Repository<BanknoteRow> rows;
    private final Logger logger;

    public Banknotes(@NotNull Plugin plugin) {
        this.rows = Databases.of(plugin).repository(BanknoteRow.class);
        this.logger = plugin.getLogger();
    }

    /** Records a note before it is printed: a note without a row is worth nothing. */
    public @NotNull CompletableFuture<Void> issue(@NotNull BanknoteRow row) {
        return rows.save(row);
    }

    /** Forgets a note that was never handed out. */
    public @NotNull CompletableFuture<Void> discard(@NotNull String id) {
        return rows.delete(id).thenApply(ignored -> null);
    }

    public @NotNull CompletableFuture<Optional<BanknoteRow>> find(@NotNull String id) {
        return rows.find(id);
    }

    /**
     * Redeems a note for a player: claims it, then credits it.
     *
     * @param credit pays the note's amount, read from its row; runs on a database thread
     */
    public @NotNull CompletableFuture<Redeem> redeem(@NotNull String id, @NotNull UUID player,
                                                     @NotNull Function<BanknoteRow, EconomyResponse> credit) {
        return rows.find(id).thenCompose(found -> {
            if (found.isEmpty()) return CompletableFuture.completedFuture(new Redeem(Outcome.UNKNOWN_NOTE, null, null));
            BanknoteRow note = found.get();
            if (note.redeemedAt() != 0L) {
                return CompletableFuture.completedFuture(new Redeem(Outcome.ALREADY_REDEEMED, note, null));
            }
            BanknoteRow claimed = note.redeemed(player.toString(), System.currentTimeMillis());
            return claim(claimed).thenCompose(won -> {
                if (!won) return CompletableFuture.completedFuture(new Redeem(Outcome.ALREADY_REDEEMED, note, null));
                EconomyResponse paid;
                try {
                    paid = credit.apply(note);
                } catch (RuntimeException failure) {
                    paid = EconomyResponse.failure(String.valueOf(failure.getMessage()));
                }
                if (paid.isSuccess()) return CompletableFuture.completedFuture(new Redeem(Outcome.PAID, note, paid));
                EconomyResponse refused = paid;
                return release(note, claimed.redeemedAt(), 0).thenApply(released -> {
                    if (released) return new Redeem(Outcome.REFUSED, note, refused);
                    logger.severe("Economy: banknote " + note.id() + " (" + note.amount().toPlainString() + " "
                            + note.currency() + ") was refused for " + player + " but stays marked redeemed: the"
                            + " database did not answer. Set redeemed_at to 0 on that row of exylia_banknotes"
                            + " to make it redeemable again.");
                    return new Redeem(Outcome.STUCK, note, refused);
                });
            });
        });
    }

    /**
     * Back to unredeemed, only while it is still this claim. A failed write may have committed, so
     * the row is read back; an outage is tried again a few times.
     *
     * @return whether the note is unredeemed again, or at least no longer this claim
     */
    private CompletableFuture<Boolean> release(BanknoteRow note, long stamp, int attempt) {
        return rows.updateIf(note, "redeemedAt", stamp).handle((done, failure) -> {
            if (failure == null) return CompletableFuture.completedFuture(true);
            return rows.find(note.id()).handle((found, unread) -> {
                if (unread == null && found.map(row -> row.redeemedAt() != stamp).orElse(true)) {
                    return CompletableFuture.completedFuture(true);
                }
                Throwable cause = unread != null ? unread : failure;
                if (attempt + 1 >= RELEASE_ATTEMPTS || !Outages.is(cause)) return CompletableFuture.completedFuture(false);
                return CompletableFuture.runAsync(() -> { },
                                CompletableFuture.delayedExecutor(RELEASE_RETRY_MILLIS, TimeUnit.MILLISECONDS))
                        .thenCompose(ignored -> release(note, stamp, attempt + 1));
            }).thenCompose(next -> next);
        }).thenCompose(next -> next);
    }

    /**
     * The compare-and-set, made sure of: a claim that reported failure may have committed, so the
     * row is read back and this very claim found there is a win.
     */
    private CompletableFuture<Boolean> claim(BanknoteRow claimed) {
        return rows.updateIf(claimed, "redeemedAt", 0L).handle((won, failure) -> failure == null
                        ? CompletableFuture.completedFuture(Boolean.TRUE.equals(won))
                        : rows.find(claimed.id()).thenApply(now -> now.filter(row ->
                                row.redeemedAt() == claimed.redeemedAt()
                                        && claimed.redeemedBy().equals(row.redeemedBy())).isPresent()))
                .thenCompose(answer -> answer);
    }
}
