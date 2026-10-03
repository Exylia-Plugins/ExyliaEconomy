package net.exylia.exyliaEconomy.database;

import net.exylia.lib.database.Column;
import net.exylia.lib.database.Id;
import net.exylia.lib.database.Table;

import java.util.Arrays;
import java.util.Locale;

/**
 * The economy's settings that belong to no one currency.
 *
 * <p>One row, {@link #GLOBAL}. Its existence is also what says the currencies
 * have been set up: an empty table is a server that has not imported yet.
 *
 * @param vaultProvide the stored currency published as Vault's economy; blank for none
 * @param vaultForce   whether it is published above any other economy plugin
 * @param ledger       whether every stored operation is written down for history
 * @param ledgerDays   how many days a ledger line is kept: {@code -1} forever, and
 *                     {@code 0} — a row written before the column existed
 *                     included — {@link #DEFAULT_LEDGER_DAYS}
 * @param imports      the balance imports already run, {@code from>into}
 *                     separated by semicolons, so a second run is refused
 */
@Table("exylia_economy_settings")
public record EconomySettingsRow(
        @Id(length = 16) String id,
        @Column boolean experienceLevels,
        @Column boolean experiencePoints,
        @Column(length = 32) String vaultProvide,
        @Column boolean vaultForce,
        @Column boolean ledger,
        @Column("updated_at") long updatedAt,
        @Column int ledgerDays,
        @Column(length = Column.UNBOUNDED) String imports) {

    public static final String GLOBAL = "global";
    public static final int DEFAULT_LEDGER_DAYS = 90;
    /** What the settings screen cycles the retention through; {@code -1} is forever. */
    private static final int[] LEDGER_STEPS = {30, 90, 180, 365, -1};

    public EconomySettingsRow(boolean experienceLevels, boolean experiencePoints, String vaultProvide,
                              boolean vaultForce, boolean ledger) {
        this(experienceLevels, experiencePoints, vaultProvide, vaultForce, ledger, 0, "");
    }

    public EconomySettingsRow(boolean experienceLevels, boolean experiencePoints, String vaultProvide,
                              boolean vaultForce, boolean ledger, int ledgerDays, String imports) {
        this(GLOBAL, experienceLevels, experiencePoints, vaultProvide == null ? "" : vaultProvide,
                vaultForce, ledger, System.currentTimeMillis(), ledgerDays, imports == null ? "" : imports);
    }

    /** Days a ledger line is kept, or a non-positive number for forever. */
    public int keptLedgerDays() {
        return ledgerDays == 0 ? DEFAULT_LEDGER_DAYS : ledgerDays;
    }

    /** Whether balances were already imported from one currency into another. */
    public boolean imported(String from, String into) {
        String mark = (from + ">" + into).toLowerCase(Locale.ROOT);
        return imports != null && Arrays.asList(imports.split(";")).contains(mark);
    }

    public EconomySettingsRow withImport(String from, String into) {
        if (imported(from, into)) return this;
        String mark = (from + ">" + into).toLowerCase(Locale.ROOT);
        String all = imports == null || imports.isBlank() ? mark : imports + ";" + mark;
        return new EconomySettingsRow(experienceLevels, experiencePoints, vaultProvide, vaultForce, ledger,
                ledgerDays, all);
    }

    /** The next retention on the settings screen's cycle. */
    public EconomySettingsRow withNextLedgerDays() {
        int current = keptLedgerDays();
        int next = LEDGER_STEPS[0];
        for (int index = 0; index < LEDGER_STEPS.length; index++) {
            if (LEDGER_STEPS[index] == current) {
                next = LEDGER_STEPS[(index + 1) % LEDGER_STEPS.length];
                break;
            }
        }
        return new EconomySettingsRow(experienceLevels, experiencePoints, vaultProvide, vaultForce, ledger,
                next, imports);
    }

    public EconomySettingsRow withExperienceLevels(boolean value) {
        return new EconomySettingsRow(value, experiencePoints, vaultProvide, vaultForce, ledger, ledgerDays, imports);
    }

    public EconomySettingsRow withExperiencePoints(boolean value) {
        return new EconomySettingsRow(experienceLevels, value, vaultProvide, vaultForce, ledger, ledgerDays, imports);
    }

    public EconomySettingsRow withVaultProvide(String value) {
        return new EconomySettingsRow(experienceLevels, experiencePoints, value, vaultForce, ledger, ledgerDays, imports);
    }

    public EconomySettingsRow withVaultForce(boolean value) {
        return new EconomySettingsRow(experienceLevels, experiencePoints, vaultProvide, value, ledger, ledgerDays, imports);
    }

    public EconomySettingsRow withLedger(boolean value) {
        return new EconomySettingsRow(experienceLevels, experiencePoints, vaultProvide, vaultForce, value, ledgerDays, imports);
    }
}
