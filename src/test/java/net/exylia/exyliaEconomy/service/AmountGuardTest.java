package net.exylia.exyliaEconomy.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AmountGuardTest {

    @Test
    void refusesAmountsNoBalanceCanHold() {
        assertNull(EconomyActions.amount("1e99999999"));
        assertNull(EconomyActions.amount("1E5"));
        assertNull(EconomyActions.amount("1000000000000000000000"));
        assertNull(EconomyActions.amount("0.00000000001"));
    }

    @Test
    void readsOrdinaryAmounts() {
        assertEquals(0, new BigDecimal("150.5").compareTo(EconomyActions.amount("150.5")));
        assertEquals(0, new BigDecimal("1000").compareTo(EconomyActions.amount("1,000")));
    }
}
