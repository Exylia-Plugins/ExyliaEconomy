package net.exylia.exyliaEconomy.service;

import net.exylia.exyliaEconomy.manager.StoredEconomy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DemoDataTest {

    @Test
    void namesAreValidAndUnique() {
        assertEquals(30, new HashSet<>(DemoData.NAMES).size());
        DemoData.NAMES.forEach(name -> assertTrue(name.matches("[A-Za-z0-9_]{3,16}"), name));
        assertEquals(DemoData.uuid("Kaelith"), DemoData.uuid("Kaelith"));
    }

    @Test
    void amountsAreALongTailWithoutRoundNumbers() {
        for (int decimals : new int[]{0, 2}) {
            List<BigDecimal> amounts = DemoData.amounts("dollars", decimals, 30);
            assertEquals(amounts, DemoData.amounts("dollars", decimals, 30));
            for (int index = 1; index < amounts.size(); index++) {
                assertTrue(amounts.get(index - 1).compareTo(amounts.get(index)) >= 0);
            }
            assertTrue(amounts.get(0).compareTo(amounts.get(29).multiply(BigDecimal.valueOf(20))) > 0);
            amounts.forEach(amount -> {
                assertTrue(amount.signum() > 0);
                assertNotEquals(0, amount.remainder(BigDecimal.TEN).signum(), amount.toPlainString());
            });
        }
    }

    @Test
    void everyCurrencyRanksDifferently() {
        assertNotEquals(DemoData.ranking("dollars"), DemoData.ranking("gems"));
    }

    @Test
    void ledgerLinesEndAtTheBalance() {
        BigDecimal amount = new BigDecimal("48213.57");
        List<StoredEconomy.SeedLine> lines = DemoData.lines("dollars", "Kaelith", amount, 2);
        assertEquals(3, lines.size());
        assertEquals(amount, lines.get(2).after());
        for (int index = 1; index < lines.size(); index++) {
            assertEquals(lines.get(index - 1).after().add(lines.get(index).delta()), lines.get(index).after());
            assertTrue(lines.get(index - 1).at() < lines.get(index).at());
        }
        lines.forEach(line -> assertTrue(line.after().signum() >= 0));
    }
}
