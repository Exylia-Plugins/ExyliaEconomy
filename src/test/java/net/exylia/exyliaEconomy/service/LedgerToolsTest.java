package net.exylia.exyliaEconomy.service;

import net.exylia.exyliaEconomy.database.LedgerRow;
import net.exylia.exyliaEconomy.manager.Claims;
import net.exylia.exyliaEconomy.testing.TestServer;
import net.exylia.lib.database.MemoryDatabase;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Rolling the ledger back once, and writing it out as CSV. */
class LedgerToolsTest {

    private static Plugin first;
    private static Plugin second;
    private static long nextId = 9_000_000L;

    @BeforeAll
    static void servers() {
        TestServer.install();
        first = TestServer.plugin("EconomyLedgerA");
        second = TestServer.plugin("EconomyLedgerB");
        MemoryDatabase.install(first, "economy-ledger-tools");
        MemoryDatabase.install(second, "economy-ledger-tools");
    }

    private static LedgerRow line(String delta, String reason, String initiator) {
        return new LedgerRow(nextId++, "11111111-1111-1111-1111-111111111111", "coins", new BigDecimal(delta),
                new BigDecimal("500"), reason, initiator, "lobby-1", 1_700_000_000_000L, 19_675L);
    }

    @Test
    @DisplayName("a rollback reverts each line once, asked twice or on two servers at once")
    void rollbackIdempotent() {
        List<LedgerRow> rows = List.of(line("100", "pay", null), line("-40", "shop:buy", null));
        AtomicInteger applied = new AtomicInteger();

        LedgerTools.Result once = LedgerTools.revert(rows, new Claims(first), row -> applied.incrementAndGet() > 0).join();
        assertEquals(2, once.reverted().size());
        assertEquals(0, new BigDecimal("-60").compareTo(LedgerTools.reversal(once.reverted())));

        LedgerTools.Result again = LedgerTools.revert(rows, new Claims(second), row -> applied.incrementAndGet() > 0).join();
        assertEquals(0, again.reverted().size());
        assertEquals(2, again.skipped());
        assertEquals(2, applied.get());

        List<LedgerRow> fresh = List.of(line("75", "pay", null));
        AtomicInteger raced = new AtomicInteger();
        CompletableFuture<LedgerTools.Result> a = CompletableFuture.supplyAsync(() ->
                LedgerTools.revert(fresh, new Claims(first), row -> raced.incrementAndGet() > 0)).thenCompose(r -> r);
        CompletableFuture<LedgerTools.Result> b = CompletableFuture.supplyAsync(() ->
                LedgerTools.revert(fresh, new Claims(second), row -> raced.incrementAndGet() > 0)).thenCompose(r -> r);
        assertEquals(1, a.join().reverted().size() + b.join().reverted().size());
        assertEquals(1, raced.get());
    }

    @Test
    @DisplayName("a refused reversal gives its claim back, so a later rollback can still revert it")
    void refusedReversalRetries() {
        List<LedgerRow> rows = List.of(line("300", "pay", null));
        LedgerTools.Result refused = LedgerTools.revert(rows, new Claims(first), row -> false).join();
        assertEquals(1, refused.failed());
        assertEquals(1, LedgerTools.revert(rows, new Claims(first), row -> true).join().reverted().size());
    }

    @Test
    @DisplayName("CSV lines quote what needs quoting and neutralise formulas")
    void csvFormatting() throws Exception {
        LedgerRow plain = new LedgerRow(7L, "11111111-1111-1111-1111-111111111111", "coins@lobby-1",
                new BigDecimal("-12.50"), new BigDecimal("87.5"), "shop:buy", null, "lobby-1", 0L, 0L);
        assertEquals("7,1970-01-01T00:00Z,11111111-1111-1111-1111-111111111111,coins@lobby-1,-12.50,87.5,shop:buy,,lobby-1",
                LedgerTools.csv(plain, ZoneOffset.UTC));

        LedgerRow tricky = new LedgerRow(8L, "p", "coins", BigDecimal.ONE, BigDecimal.TEN, "say \"hi\", ok",
                "=cmd()", "s", 0L, 0L);
        assertEquals("8,1970-01-01T00:00Z,p,coins,1,10,\"say \"\"hi\"\", ok\",'=cmd(),s",
                LedgerTools.csv(tricky, ZoneOffset.UTC));

        Path file = Files.createTempDirectory("ledger").resolve("exports").resolve("out.csv");
        LedgerTools.write(file, List.of(plain));
        List<String> lines = Files.readAllLines(file);
        assertEquals(LedgerTools.CSV_HEADER, lines.get(0));
        assertEquals(2, lines.size());
    }

    @Test
    @DisplayName("transfers, banknotes and rollbacks are never reverted on their own")
    void onlyOneSidedLinesRevert() {
        for (String kept : new String[]{"pay", "pay:tax", "pay:refund", "exchange:coins>gems", "note:withdraw",
                "note:redeem", "rollback"}) {
            org.junit.jupiter.api.Assertions.assertFalse(LedgerTools.revertible(kept), kept);
        }
        for (String reverted : new String[]{"admin:give", "shop:sell", "interest", "api", "payroll"}) {
            org.junit.jupiter.api.Assertions.assertTrue(LedgerTools.revertible(reverted), reverted);
        }
    }

    @Test
    @DisplayName("CSV cells starting with a tab or carriage return are neutralised too")
    void csvControlCharacters() {
        LedgerRow row = new LedgerRow(9L, "p", "coins", BigDecimal.ONE, BigDecimal.ONE, "\tcmd", "\rx", "s", 0L, 0L);
        assertEquals("9,1970-01-01T00:00Z,p,coins,1,1,'\tcmd,\"'\rx\",s", LedgerTools.csv(row, ZoneOffset.UTC));
    }
}
