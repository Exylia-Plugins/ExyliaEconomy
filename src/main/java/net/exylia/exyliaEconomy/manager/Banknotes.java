package net.exylia.exyliaEconomy.manager;

import net.exylia.exyliaEconomy.database.BanknoteRow;
import net.exylia.lib.database.Databases;
import net.exylia.lib.database.Repository;
import net.exylia.lib.economy.EconomyResponse;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

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
    public enum Outcome { PAID, ALREADY_REDEEMED, UNKNOWN_NOTE, REFUSED }

    /**
     * @param note   the note's row, {@code null} when there is none
     * @param credit what the currency answered, {@code null} unless it was asked
     */
    public record Redeem(@NotNull Outcome outcome, @Nullable BanknoteRow note, @Nullable EconomyResponse credit) { }

    private final Repository<BanknoteRow> rows;

    public Banknotes(@NotNull Plugin plugin) {
        this.rows = Databases.of(plugin).repository(BanknoteRow.class);
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
                // Back to unredeemed, only if it is still this claim.
                return rows.updateIf(note, "redeemedAt", claimed.redeemedAt())
                        .thenApply(ignored -> new Redeem(Outcome.REFUSED, note, refused));
            });
        });
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
