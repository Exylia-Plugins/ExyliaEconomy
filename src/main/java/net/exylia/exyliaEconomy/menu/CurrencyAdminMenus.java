package net.exylia.exyliaEconomy.menu;

import net.exylia.exyliaEconomy.ExyliaEconomy;
import net.exylia.lib.text.Values;

import net.exylia.exyliaEconomy.config.EconomyMessages;
import net.exylia.exyliaEconomy.database.CurrencyRow;
import net.exylia.exyliaEconomy.database.EconomySettingsRow;
import net.exylia.exyliaEconomy.manager.CurrencyStore;
import net.exylia.exyliaEconomy.manager.StoredEconomy;
import net.exylia.lib.economy.CurrencyInfo;
import net.exylia.lib.ui.UiEntry;
import net.exylia.lib.util.TimeFormats;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The screens {@code /economyadmin} opens: the currencies, one currency, and the settings.
 *
 * <p>One currency is a hub that opens each section of it on its own page, as a mine's setup does.
 */
public final class CurrencyAdminMenus {

    public static final String LIST = "menus/admin/currency_admin_list";
    public static final String EDIT = "menus/admin/currency_admin_edit";
    public static final String SETTINGS = "menus/admin/currency_admin_settings";

    /** The hub's pages, each its own file next to it. Only stored currencies have more than {@code general}. */
    public static final List<String> PAGES = List.of("general", "commands", "payments", "exchange", "interest", "integrations");
    private static final String HUB = "hub";

    /** The section each admin is on, so an edit made from it comes back to it. Forgotten with the player. */
    private static final Map<Player, Page> pages = Collections.synchronizedMap(new WeakHashMap<>());

    private record Page(String currency, String name) {
    }

    /** The amount the preview lines are written with: big enough to show grouping and decimals. */
    private static final BigDecimal SAMPLE = new BigDecimal("12345.67");

    private CurrencyAdminMenus() {
        throw new AssertionError("No instances.");
    }

    // ------------------------------------------------------------------- list

    public static void openList(Player player, CurrencyStore store) {
        menus().open(player, LIST, Map.of(), store.all().stream()
                .map(row -> UiEntry.of(row)
                        .with("currency_id", row.id())
                        .with("currency_icon", icon(row))
                        .with("currency_kind", kindLabel(row.kind()))
                        .withFormatted("currency_name", row.info().namePlural())
                        .withFormatted("currency_preview", row.info().format(SAMPLE))
                        .with("currency_sort_order", row.sortOrder())
                        .build())
                .toList());
    }

    // ------------------------------------------------------------------- edit

    /** Opens a currency on its hub, from wherever the admin came. */
    public static void edit(Player player, CurrencyRow row) {
        openPage(player, row, HUB);
    }

    /** Opens one section of a currency; {@code hub} or an unknown name opens the hub. */
    public static void openPage(Player player, CurrencyRow row, String page) {
        pages.put(player, new Page(row.id(), page));
        draw(player, row, false);
    }

    /** Opens the currency on the section the admin was last on. */
    public static void openEdit(Player player, CurrencyRow row) {
        draw(player, row, false);
    }

    /** The page file this admin is on for this currency: the hub unless a section of it was opened. */
    private static String file(Player player, CurrencyRow row) {
        Page page = pages.get(player);
        if (page == null || !page.currency().equals(row.id()) || !PAGES.contains(page.name())) return EDIT;
        if (!page.name().equals("general") && row.kind() != CurrencyRow.Kind.STORED) return EDIT;
        return EDIT + "_" + page.name();
    }

    /** Redraws the currency in place when its screen is already open. */
    public static void refreshEdit(Player player, CurrencyRow row) {
        draw(player, row, true);
    }

    private static void draw(Player player, CurrencyRow row, boolean inPlace) {
        CurrencyInfo info = row.info();
        CurrencyStore store = StoredEconomy.store();
        EconomySettingsRow settings = store == null ? null : store.settings();
        boolean vault = settings != null && row.id().equals(settings.vaultProvide());
        Values context = Values.of()
                .put("currency_id", row.id())
                // The enum name, for the file's conditions; the label, for people.
                .put("currency_kind", row.kind().name())
                .put("currency_kind_label", kindLabel(row.kind()))
                .put("currency_icon", icon(row))
                .put("currency_name", info.name())
                .put("currency_plural", info.namePlural())
                .put("currency_symbol", info.symbol().isEmpty() ? admin().none() : info.symbol())
                .put("currency_decimals", row.decimals() < 0 ? admin().providerDecimals() : String.valueOf(row.decimals()))
                .put("currency_format", info.format())
                .put("currency_compact_format", info.compactFormat())
                .put("currency_preview", info.format(SAMPLE))
                .put("currency_preview_compact", info.formatCompact(SAMPLE))
                .put("currency_aliases", row.aliases() == null || row.aliases().isEmpty()
                        ? admin().none() : "/" + String.join(", /", row.aliases()))
                .put("currency_item_icon", row.item() == null || row.item().isBlank() ? "BARRIER" : row.item())
                .put("currency_item_state", row.item() == null || row.item().isBlank()
                        ? admin().itemNotSet() : admin().itemSet())
                .put("currency_start", plain(row.start()))
                .put("currency_max", row.max() == null || row.max().signum() <= 0 ? admin().none() : plain(row.max()))
                .put("currency_permission", row.permission() == null || row.permission().isBlank()
                        ? admin().none() : row.permission())
                .put("currency_minimum_transfer", plain(row.minimumTransfer()))
                .put("currency_tax", plain(BigDecimal.valueOf(row.transferTaxPercent())) + "%")
                .put("currency_rates", row.rates() == null || row.rates().isBlank()
                        ? admin().none() : row.rates().replace(";", ", "))
                .put("currency_sort_order", String.valueOf(row.sortOrder()))
                .put("currency_pay_confirm", row.confirmAbove() == null ? admin().none() : plain(row.confirmAbove()))
                .put("currency_interest_rate", row.interestRate() <= 0 ? admin().off()
                        : plain(BigDecimal.valueOf(row.interestRate())) + "%")
                .put("currency_interest_interval", TimeFormats.render(row.interestEvery(), TimeFormats.Style.COMPACT))
                .put("currency_interest_max", row.interestMax() == null || row.interestMax().signum() <= 0
                        ? admin().none() : plain(row.interestMax()));
        toggle(context, "transferable", row.transferable());
        toggle(context, "exchangeable", row.exchangeable());
        toggle(context, "leaderboard", row.leaderboard());
        toggle(context, "networked", row.networked());
        toggle(context, "commands", row.commands());
        toggle(context, "vault", vault);
        toggle(context, "banknotes", row.banknotes());
        toggle(context, "interest_offline", row.interestOffline());
        String file = file(player, row);
        if (inPlace && menus().update(player, file, context.map())) return;
        menus().open(player, file, context.map());
    }

    // --------------------------------------------------------------- settings

    public static void openSettings(Player player, CurrencyStore store) {
        draw(player, store, false);
    }

    public static void refreshSettings(Player player, CurrencyStore store) {
        draw(player, store, true);
    }

    private static void draw(Player player, CurrencyStore store, boolean inPlace) {
        EconomySettingsRow settings = store.settings();
        Values context = Values.of()
                .put("vault_provide", settings.vaultProvide().isBlank() ? admin().none() : settings.vaultProvide())
                .put("ledger_days", settings.keptLedgerDays() <= 0 ? admin().ledgerForever()
                        : Values.of("days", settings.keptLedgerDays()).apply(admin().ledgerDays()))
                .put("language", settings.languageOrDefault().equals(EconomySettingsRow.LANGUAGES.getFirst())
                        ? admin().languageDefault() : settings.languageOrDefault().toUpperCase(java.util.Locale.ROOT));
        toggle(context, "experience_levels", settings.experienceLevels());
        toggle(context, "experience_points", settings.experiencePoints());
        toggle(context, "vault_force", settings.vaultForce());
        toggle(context, "ledger", settings.ledger());
        toggle(context, "offline_pay_notice", settings.offlinePayNotice());
        toggle(context, "debug", settings.debug());
        if (inPlace && menus().update(player, SETTINGS, context.map())) return;
        menus().open(player, SETTINGS, context.map());
    }

    // ---------------------------------------------------------------- helpers

    /** What a kind is called on a button. */
    public static String kindLabel(CurrencyRow.Kind kind) {
        return switch (kind) {
            case STORED -> admin().kindStored();
            case ITEM -> admin().kindItem();
            case DISPLAY -> admin().kindDisplay();
        };
    }

    /** An item currency is drawn as its item; the rest as their icon. */
    private static String icon(CurrencyRow row) {
        if (row.kind() == CurrencyRow.Kind.ITEM && row.item() != null && !row.item().isBlank()) return row.item();
        return row.info().icon();
    }

    /** A setting that is on or off: its word and the dye that shows it. */
    private static void toggle(Values context, String name, boolean on) {
        context.put(name + "_status", on ? admin().on() : admin().off())
                .put(name + "_material", on ? "LIME_DYE" : "ORANGE_DYE");
    }

    private static String plain(BigDecimal amount) {
        return amount == null ? "0" : amount.stripTrailingZeros().toPlainString();
    }

    private static EconomyMessages.CurrencyAdmin admin() {
        return EconomyMessages.get().admin();
    }

    private static EconomyMenus menus() {
        return ExyliaEconomy.getInstance().getMenus();
    }
}
