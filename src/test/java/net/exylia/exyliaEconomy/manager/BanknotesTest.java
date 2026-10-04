package net.exylia.exyliaEconomy.manager;

import net.exylia.exyliaEconomy.database.BanknoteRow;
import net.exylia.exyliaEconomy.testing.TestServer;
import net.exylia.lib.database.MemoryDatabase;
import net.exylia.lib.economy.EconomyResponse;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A banknote pays once, whoever redeems it and on however many servers. */
class BanknotesTest {

    private static Plugin first;
    private static Plugin second;

    @BeforeAll
    static void servers() {
        TestServer.install();
        first = TestServer.plugin("EconomyNotesA");
        second = TestServer.plugin("EconomyNotesB");
        MemoryDatabase.install(first, "economy-notes");
        MemoryDatabase.install(second, "economy-notes");
    }

    private static BanknoteRow note() {
        return new BanknoteRow(UUID.randomUUID().toString(), "coins", new BigDecimal("250"),
                UUID.randomUUID().toString(), "Issuer", System.currentTimeMillis(), 0L, "");
    }

    private static EconomyResponse paid(BanknoteRow note) {
        return EconomyResponse.success(note.amount(), note.amount());
    }

    @Test
    @DisplayName("a note redeemed twice pays once")
    void doubleRedeemRefused() {
        Banknotes notes = new Banknotes(first);
        BanknoteRow note = note();
        notes.issue(note).join();
        AtomicInteger credits = new AtomicInteger();
        UUID player = UUID.randomUUID();

        assertEquals(Banknotes.Outcome.PAID, notes.redeem(note.id(), player, row -> {
            credits.incrementAndGet();
            return paid(row);
        }).join().outcome());
        assertEquals(Banknotes.Outcome.ALREADY_REDEEMED, notes.redeem(note.id(), player, row -> {
            credits.incrementAndGet();
            return paid(row);
        }).join().outcome());
        assertEquals(1, credits.get());
        assertEquals(Banknotes.Outcome.UNKNOWN_NOTE,
                notes.redeem(UUID.randomUUID().toString(), player, BanknotesTest::paid).join().outcome());
    }

    @Test
    @DisplayName("copies of one note redeemed at once on two servers pay exactly once")
    void concurrentCopiesPayOnce() {
        Banknotes a = new Banknotes(first);
        Banknotes b = new Banknotes(second);
        BanknoteRow note = note();
        a.issue(note).join();
        AtomicInteger credits = new AtomicInteger();
        List<CompletableFuture<Banknotes.Redeem>> tries = new ArrayList<>();
        for (int copy = 0; copy < 16; copy++) {
            Banknotes server = copy % 2 == 0 ? a : b;
            tries.add(CompletableFuture.supplyAsync(() -> server.redeem(note.id(), UUID.randomUUID(), row -> {
                credits.incrementAndGet();
                return paid(row);
            })).thenCompose(redeem -> redeem));
        }
        long paid = tries.stream().map(CompletableFuture::join)
                .filter(redeem -> redeem.outcome() == Banknotes.Outcome.PAID).count();
        assertEquals(1, paid);
        assertEquals(1, credits.get());
    }

    @Test
    @DisplayName("a credit the currency refuses gives the claim back, and the note pays later")
    void refusedCreditRollsBack() {
        Banknotes notes = new Banknotes(first);
        BanknoteRow note = note();
        notes.issue(note).join();
        UUID player = UUID.randomUUID();

        Banknotes.Redeem refused = notes.redeem(note.id(), player, row -> EconomyResponse.failure("over the ceiling")).join();
        assertEquals(Banknotes.Outcome.REFUSED, refused.outcome());
        assertEquals(0L, notes.find(note.id()).join().orElseThrow().redeemedAt());

        assertEquals(Banknotes.Outcome.PAID, new Banknotes(second).redeem(note.id(), player, BanknotesTest::paid)
                .join().outcome());
        assertEquals(player.toString(), notes.find(note.id()).join().orElseThrow().redeemedBy());
    }
}
