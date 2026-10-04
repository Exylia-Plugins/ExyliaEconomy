package net.exylia.exyliaEconomy.action;

import net.exylia.exyliaEconomy.config.EconomyMessages;
import net.exylia.exyliaEconomy.ExyliaEconomy;
import net.exylia.exyliaEconomy.common.Messages;
import net.exylia.exyliaEconomy.common.Values;
import net.exylia.exyliaEconomy.database.CurrencyRow;
import net.exylia.exyliaEconomy.database.EconomySettingsRow;
import net.exylia.exyliaEconomy.manager.CurrencyStore;
import net.exylia.exyliaEconomy.manager.StoredEconomy;
import net.exylia.exyliaEconomy.menu.CurrencyAdminMenus;
import net.exylia.exyliaEconomy.Permissions;
import net.exylia.lib.action.ActionArguments;
import net.exylia.lib.action.ActionResult;
import net.exylia.lib.action.PluginActions;
import net.exylia.lib.input.FormField;
import net.exylia.lib.input.FormKey;
import net.exylia.lib.item.Source;
import net.exylia.lib.util.editor.EditorForm;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * Every {@code exyliaeconomy:currency_admin_*} button: creating, editing and
 * deleting currencies in game.
 *
 * <p>Each button carries the currency's id rather than reading an "editing"
 * session, so a click always lands on the currency its screen was drawn for.
 * Every change is written, registered again on this server at once and
 * announced to the others through {@link StoredEconomy#changed}.
 */
public final class CurrencyAdminActions {

    private static final Pattern ID = Pattern.compile("[a-z0-9_]{1,32}");
    private static final Pattern ALIAS = Pattern.compile("[a-z0-9_]{1,32}");

    private static final FormKey<String> NAME = FormKey.text("name");
    private static final FormKey<String> PLURAL = FormKey.text("plural");
    private static final FormKey<String> SYMBOL = FormKey.text("symbol");
    private static final FormKey<Long> DECIMALS = FormKey.integer("decimals");
    private static final FormKey<String> FORMAT = FormKey.text("format");
    private static final FormKey<String> COMPACT = FormKey.text("compact_format");
    private static final FormKey<BigDecimal> START = FormKey.decimal("start");
    private static final FormKey<BigDecimal> MAX = FormKey.decimal("max");
    private static final FormKey<String> PERMISSION = FormKey.text("permission");
    private static final FormKey<BigDecimal> MINIMUM = FormKey.decimal("minimum_transfer");
    private static final FormKey<BigDecimal> TAX = FormKey.decimal("tax");
    private static final FormKey<String> RATES = FormKey.text("rates");
    private static final FormKey<String> ALIASES = FormKey.text("aliases");
    private static final FormKey<Long> SORT = FormKey.integer("sort_order");
    private static final FormKey<BigDecimal> CONFIRM_ABOVE = FormKey.decimal("pay_confirm_above");
    private static final FormKey<BigDecimal> INTEREST_RATE = FormKey.decimal("interest_rate");
    private static final FormKey<Duration> INTEREST_INTERVAL = FormKey.duration("interest_interval");
    private static final FormKey<BigDecimal> INTEREST_MAX = FormKey.decimal("interest_max");

    private final ExyliaEconomy plugin;
    private final PluginActions actions;

    public CurrencyAdminActions(ExyliaEconomy plugin) {
        this.plugin = plugin;
        this.actions = plugin.getActions();
    }

    public void registerAll() {
        button("currency_admin_open_list", (player, args, store) -> CurrencyAdminMenus.openList(player, store));
        button("currency_admin_settings", (player, args, store) -> CurrencyAdminMenus.openSettings(player, store));
        button("currency_admin_create", this::create);
        withRow("currency_admin_edit", (player, store, row) -> CurrencyAdminMenus.openEdit(player, row));
        withRow("currency_admin_delete", this::delete);
        withRow("currency_admin_appearance", this::appearance);
        withRow("currency_admin_rules", this::rules);
        withRow("currency_admin_rates", this::rates);
        withRow("currency_admin_aliases", this::aliases);
        withRow("currency_admin_sort", this::sortOrder);
        withRow("currency_admin_pay_confirm", this::payConfirm);
        withRow("currency_admin_interest", this::interest);
        withRow("currency_admin_icon", (player, store, row) -> {
            player.closeInventory();
            plugin.getInputs().answered(player, plugin.getInputs().icon(player, admin().iconPrompt()).open(),
                    icon -> save(player, store, row.edit(draft -> draft.icon = icon)),
                    () -> CurrencyAdminMenus.openEdit(player, row));
        });
        withRow("currency_admin_item", (player, store, row) -> askItem(player, store, row, false));
        button("currency_admin_toggle", this::toggle);
        button("currency_admin_settings_toggle", this::toggleSetting);
    }

    // --------------------------------------------------------------- lifecycle

    private void create(Player player, ActionArguments args, CurrencyStore store) {
        player.closeInventory();
        plugin.getInputs().answered(player, plugin.getInputs().id(player,
                        admin().idPrompt()).maxLength(32).open(),
                typed -> {
                    String id = typed.trim().toLowerCase(Locale.ROOT);
                    if (!ID.matcher(id).matches()) {
                        Messages.send(player, EconomyMessages.get().currencyInvalidId(), Values.of("id", id));
                        CurrencyAdminMenus.openList(player, store);
                        return;
                    }
                    if (store.get(id).isPresent()) {
                        Messages.send(player, EconomyMessages.get().currencyExists(), Values.of("id", id));
                        CurrencyAdminMenus.openList(player, store);
                        return;
                    }
                    plugin.getInputs().answered(player, plugin.getInputs().choice(player,
                                            Values.of("id", id).apply(admin().kindPrompt()),
                                            Arrays.asList(CurrencyRow.Kind.values()))
                                    .label(CurrencyAdminMenus::kindLabel)
                                    .key(kind -> kind.name().toLowerCase(Locale.ROOT))
                                    .icon(CurrencyAdminActions::kindIcon)
                                    .open(),
                            kind -> {
                                int last = store.all().stream().mapToInt(CurrencyRow::sortOrder).max().orElse(-1);
                                CurrencyRow row = CurrencyRow.blank(id, kind, last + 1);
                                // An item currency is its item: without one it is not a currency yet.
                                if (kind == CurrencyRow.Kind.ITEM) {
                                    askItem(player, store, row, true);
                                    return;
                                }
                                save(player, store, row, EconomyMessages.get().currencyCreated());
                            },
                            () -> CurrencyAdminMenus.openList(player, store));
                },
                () -> CurrencyAdminMenus.openList(player, store));
    }

    // Asked first: a stored currency's balances outlive the row.
    private void delete(Player player, CurrencyStore store, CurrencyRow row) {
        player.closeInventory();
        String warning = row.kind() == CurrencyRow.Kind.STORED ? admin().deleteStoredWarning() : "";
        plugin.getInputs().answered(player, plugin.getInputs().confirm(player,
                                Values.of("id", row.id()).apply(admin().deletePrompt()) + warning).dangerous().open(),
                confirmed -> {
                    if (!confirmed) {
                        CurrencyAdminMenus.openEdit(player, row);
                        return;
                    }
                    CompletableFuture<?> written = store.delete(row.id());
                    if (row.id().equals(store.settings().vaultProvide())) {
                        written = CompletableFuture.allOf(written,
                                store.save(store.settings().withVaultProvide("")));
                    }
                    StoredEconomy.changed(written);
                    reported(player, written, row.id(), EconomyMessages.get().currencyDeleted());
                    CurrencyAdminMenus.openList(player, store);
                },
                () -> CurrencyAdminMenus.openEdit(player, row));
    }

    // ------------------------------------------------------------------ fields

    private void appearance(Player player, CurrencyStore store, CurrencyRow row) {
        boolean overlay = row.kind() == CurrencyRow.Kind.DISPLAY;
        form(player, store, row, EditorForm.of(plugin.getPlugin(), player,
                                Values.of("id", row.id()).apply(admin().appearanceTitle()))
                        .text(NAME, admin().name(), row.info().name(), 2)
                        .hint(admin().nameHint())
                        .text(PLURAL, admin().plural(), row.info().namePlural(), 2)
                        .text(SYMBOL, admin().symbol(), row.symbol())
                        .hint(admin().symbolHint())
                        .integer(DECIMALS, admin().decimals(), row.decimals())
                        .hint(overlay ? admin().decimalsProviderHint() : admin().decimalsHint())
                        .text(FORMAT, admin().format(), row.info().format(), 2)
                        .hint(admin().formatHint())
                        .text(COMPACT, admin().compactFormat(), row.info().compactFormat(), 2)
                        .hint(admin().compactFormatHint()),
                values -> row.edit(draft -> {
                    draft.name = values.getText(NAME).trim();
                    draft.plural = values.getText(PLURAL).trim();
                    draft.symbol = values.getOr(SYMBOL, "").trim();
                    draft.decimals = (int) Math.max(overlay ? -1 : 0, Math.min(8, values.getLong(DECIMALS)));
                    draft.format = values.getText(FORMAT);
                    draft.compactFormat = values.getText(COMPACT);
                }));
    }

    private void rules(Player player, CurrencyStore store, CurrencyRow row) {
        if (row.kind() != CurrencyRow.Kind.STORED) return;
        form(player, store, row, EditorForm.of(plugin.getPlugin(), player,
                                Values.of("id", row.id()).apply(admin().rulesTitle()))
                        .decimal(START, admin().start(), row.start())
                        .decimal(MAX, admin().max(), row.max())
                        .hint(admin().maxHint())
                        .text(PERMISSION, admin().permission(), row.permission())
                        .hint(admin().permissionHint())
                        .decimal(MINIMUM, admin().minimumTransfer(), row.minimumTransfer())
                        .decimal(TAX, admin().tax(), BigDecimal.valueOf(row.transferTaxPercent()))
                        .hint(admin().taxHint()),
                values -> row.edit(draft -> {
                    draft.start = values.getDecimal(START).max(BigDecimal.ZERO);
                    BigDecimal max = values.getDecimal(MAX);
                    draft.max = max.signum() <= 0 ? BigDecimal.valueOf(-1) : max;
                    draft.permission = values.getOr(PERMISSION, "").trim();
                    draft.minimumTransfer = values.getDecimal(MINIMUM).max(BigDecimal.ZERO);
                    draft.transferTaxPercent = Math.max(0, Math.min(100, values.getDecimal(TAX).doubleValue()));
                }));
    }

    private void rates(Player player, CurrencyStore store, CurrencyRow row) {
        if (row.kind() != CurrencyRow.Kind.STORED) return;
        player.closeInventory();
        String current = row.rates() == null ? "" : row.rates().replace(";", ", ");
        formed(player, EditorForm.of(plugin.getPlugin(), player,
                                Values.of("id", row.id()).apply(admin().ratesTitle()))
                        .text(RATES, admin().rates(), current, 3)
                        .hint(admin().ratesHint())
                        .ask(values -> values.getOr(RATES, "")),
                typed -> {
                    Map<String, BigDecimal> parsed;
                    try {
                        parsed = CurrencyRow.decodeRates(typed);
                    } catch (IllegalArgumentException unreadable) {
                        Messages.send(player, EconomyMessages.get().currencyInvalidRates(),
                                Values.of("reason", unreadable.getMessage()));
                        CurrencyAdminMenus.openEdit(player, row);
                        return;
                    }
                    save(player, store, row.edit(draft -> draft.rates = CurrencyRow.encodeRates(parsed)));
                },
                () -> CurrencyAdminMenus.openEdit(player, row));
    }

    private void aliases(Player player, CurrencyStore store, CurrencyRow row) {
        if (row.kind() == CurrencyRow.Kind.DISPLAY) return;
        String current = row.aliases() == null ? "" : String.join(", ", row.aliases());
        form(player, store, row, EditorForm.of(plugin.getPlugin(), player,
                                Values.of("id", row.id()).apply(admin().commandsTitle()))
                        .text(ALIASES, admin().aliases(), current, 2)
                        .hint(admin().aliasesHint()),
                values -> row.edit(draft -> draft.aliases = Arrays.stream(values.getOr(ALIASES, "").split("[,\\s]+"))
                        .map(alias -> alias.trim().toLowerCase(Locale.ROOT).replace("/", ""))
                        .filter(alias -> ALIAS.matcher(alias).matches())
                        .distinct()
                        .toList()));
    }

    private void sortOrder(Player player, CurrencyStore store, CurrencyRow row) {
        form(player, store, row, EditorForm.of(plugin.getPlugin(), player,
                                Values.of("id", row.id()).apply(admin().sortTitle()))
                        .integer(SORT, admin().sortOrder(), row.sortOrder())
                        .hint(admin().sortOrderHint()),
                values -> row.edit(draft -> draft.sortOrder = (int) values.getLong(SORT)));
    }

    private void payConfirm(Player player, CurrencyStore store, CurrencyRow row) {
        form(player, store, row, EditorForm.of(plugin.getPlugin(), player,
                                Values.of("id", row.id()).apply(admin().payConfirmTitle()))
                        .decimal(CONFIRM_ABOVE, admin().payConfirmAbove(),
                                row.payConfirmAbove() == null ? BigDecimal.ZERO : row.payConfirmAbove())
                        .hint(admin().payConfirmAboveHint()),
                values -> row.edit(draft -> draft.payConfirmAbove = values.getDecimal(CONFIRM_ABOVE).max(BigDecimal.ZERO)));
    }

    private void interest(Player player, CurrencyStore store, CurrencyRow row) {
        if (row.kind() != CurrencyRow.Kind.STORED) return;
        form(player, store, row, EditorForm.of(plugin.getPlugin(), player,
                                Values.of("id", row.id()).apply(admin().interestTitle()))
                        .decimal(INTEREST_RATE, admin().interestRate(), BigDecimal.valueOf(row.interestRate()))
                        .hint(admin().interestRateHint())
                        .field(INTEREST_INTERVAL, FormField.duration(INTEREST_INTERVAL, admin().interestInterval())
                                .defaultValue(row.interestEvery()))
                        .hint(admin().interestIntervalHint())
                        .decimal(INTEREST_MAX, admin().interestMax(),
                                row.interestMax() == null ? BigDecimal.ZERO : row.interestMax())
                        .hint(admin().interestMaxHint()),
                values -> row.edit(draft -> {
                    draft.interestRate = Math.max(0, Math.min(100, values.getDecimal(INTEREST_RATE).doubleValue()));
                    // Interest checks every 30 seconds, so a minute is the shortest interval it keeps.
                    draft.interestInterval = Math.max(60, values.getDuration(INTEREST_INTERVAL).toSeconds());
                    draft.interestMax = values.getDecimal(INTEREST_MAX).max(BigDecimal.ZERO);
                }));
    }

    /**
     * Asks for the exact item an item currency is.
     *
     * <p>Stored whole — name, lore, model, enchantments — so only a stack
     * {@link ItemStack#isSimilar similar} to it counts as money. The count is
     * dropped: one unit is one item.
     */
    private void askItem(Player player, CurrencyStore store, CurrencyRow row, boolean creating) {
        if (row.kind() != CurrencyRow.Kind.ITEM) return;
        player.closeInventory();
        plugin.getInputs().answered(player, plugin.getInputs().item(player, admin().itemPrompt()).open(),
                stack -> {
                    ItemStack one = stack.clone();
                    one.setAmount(1);
                    String raw = Source.whole(one).raw();
                    if (raw.equals("AIR")) {
                        if (creating) CurrencyAdminMenus.openList(player, store);
                        else CurrencyAdminMenus.openEdit(player, row);
                        return;
                    }
                    save(player, store, row.edit(draft -> {
                        draft.item = raw;
                        // A new item currency looks like its item until somebody picks otherwise.
                        if (creating) draft.icon = raw;
                    }), creating ? EconomyMessages.get().currencyCreated() : EconomyMessages.get().currencySaved());
                },
                () -> {
                    if (creating) CurrencyAdminMenus.openList(player, store);
                    else CurrencyAdminMenus.openEdit(player, row);
                });
    }

    /** {@code currency_admin_toggle <id> <setting>}: flips one switch on a stored currency. */
    private void toggle(Player player, ActionArguments args, CurrencyStore store) {
        CurrencyRow row = store.get(args.string(0, "")).orElse(null);
        if (row == null) {
            Messages.send(player, EconomyMessages.get().currencyNotFound(), Values.of("id", args.string(0, "")));
            return;
        }
        if (row.kind() != CurrencyRow.Kind.STORED) return;
        String setting = args.string(1, "").toLowerCase(Locale.ROOT);
        if (setting.equals("vault")) {
            EconomySettingsRow settings = store.settings();
            String provide = row.id().equals(settings.vaultProvide()) ? "" : row.id();
            CompletableFuture<Void> written = store.save(settings.withVaultProvide(provide));
            StoredEconomy.changed(written);
            reported(player, written, row.id(), null);
            CurrencyAdminMenus.refreshEdit(player, row);
            return;
        }
        if (setting.equals("networked")) {
            toggleNetworked(player, store, row);
            return;
        }
        UnaryOperator<CurrencyRow> change = switch (setting) {
            case "transferable" -> current -> current.edit(draft -> draft.transferable = !draft.transferable);
            case "exchangeable" -> current -> current.edit(draft -> draft.exchangeable = !draft.exchangeable);
            case "leaderboard" -> current -> current.edit(draft -> draft.leaderboard = !draft.leaderboard);
            case "commands" -> current -> current.edit(draft -> draft.commands = !draft.commands);
            case "banknotes" -> current -> current.edit(draft -> draft.banknotes = !draft.banknotes);
            case "interest_offline" -> current -> current.edit(draft -> draft.interestOffline = !draft.interestOffline);
            default -> null;
        };
        if (change == null) return;
        CurrencyRow changed = change.apply(row);
        CompletableFuture<Void> written = store.save(changed);
        StoredEconomy.changed(written);
        reported(player, written, row.id(), null);
        CurrencyAdminMenus.refreshEdit(player, changed);
    }

    /**
     * Networked or not is which rows hold the balances, so the switch is only
     * thrown while nothing but starting balances would stay behind: refused
     * otherwise, never a quiet reset of everybody's money.
     */
    private void toggleNetworked(Player player, CurrencyStore store, CurrencyRow row) {
        StoredEconomy.holdsBalances(row.id()).whenComplete((held, failure) -> plugin.getTasks().runAtEntity(player, () -> {
            if (failure != null) plugin.getDebug().error("Economy: could not count the balances of " + row.id() + ".", failure);
            CurrencyRow current = store.get(row.id()).orElse(null);
            if (current == null) {
                Messages.send(player, EconomyMessages.get().currencyNotFound(), Values.of("id", row.id()));
                return;
            }
            if (failure != null || Boolean.TRUE.equals(held)) {
                Messages.send(player, EconomyMessages.get().currencyNetworkedLocked(), Values.of("id", row.id()));
                return;
            }
            CurrencyRow changed = current.edit(draft -> draft.networked = !draft.networked);
            CompletableFuture<Void> written = store.save(changed);
            StoredEconomy.changed(written);
            reported(player, written, row.id(), null);
            CurrencyAdminMenus.refreshEdit(player, changed);
        }));
    }

    /** {@code currency_admin_settings_toggle <setting>}: flips one of the economy-wide switches. */
    private void toggleSetting(Player player, ActionArguments args, CurrencyStore store) {
        EconomySettingsRow settings = store.settings();
        String setting = args.string(0, "").toLowerCase(Locale.ROOT);
        EconomySettingsRow changed = switch (setting) {
            case "experience_levels" -> settings.withExperienceLevels(!settings.experienceLevels());
            case "experience_points" -> settings.withExperiencePoints(!settings.experiencePoints());
            case "vault_force" -> settings.withVaultForce(!settings.vaultForce());
            case "ledger" -> settings.withLedger(!settings.ledger());
            case "ledger_days" -> settings.withNextLedgerDays();
            case "offline_pay_notice" -> settings.withOfflinePayNotice(!settings.offlinePayNotice());
            case "debug" -> settings.withDebug(!settings.debug());
            case "language" -> settings.withNextLanguage();
            default -> null;
        };
        if (changed == null) return;
        CompletableFuture<Void> written = store.save(changed);
        StoredEconomy.changed(written);
        reported(player, written, "settings", null);
        // A new language rebuilt every menu: the open one is redrawn from the new files.
        if (setting.equals("language")) CurrencyAdminMenus.openSettings(player, store);
        else CurrencyAdminMenus.refreshSettings(player, store);
    }

    // ---------------------------------------------------------------- plumbing

    /** Writes a row, registers it everywhere, and puts the admin back on it. */
    private void save(Player player, CurrencyStore store, CurrencyRow row) {
        save(player, store, row, EconomyMessages.get().currencySaved());
    }

    /** The same, confirming with {@code done} once the write has landed rather than before. */
    private void save(Player player, CurrencyStore store, CurrencyRow row, String done) {
        CompletableFuture<Void> written = store.save(row);
        StoredEconomy.changed(written);
        reported(player, written, row.id(), done);
        CurrencyAdminMenus.openEdit(player, row);
    }

    /**
     * Tells the admin how a write ended, back on their thread: {@code done}
     * once it landed (nothing when {@code null}), the failure whenever it did not.
     */
    private void reported(Player player, CompletableFuture<?> written, String id, @Nullable String done) {
        written.whenComplete((ignored, failure) -> {
            if (failure == null && done == null) return;
            plugin.getTasks().runAtEntity(player, () -> Messages.send(player,
                    failure == null ? done : EconomyMessages.get().currencyWriteFailed(), Values.of("id", id)));
        });
    }

    /** One dialog over a row, saved when submitted and back to the row otherwise. */
    private void form(Player player, CurrencyStore store, CurrencyRow row, EditorForm form,
                      java.util.function.Function<net.exylia.lib.input.FormValues, CurrencyRow> build) {
        player.closeInventory();
        formed(player, form.ask(build), edited -> save(player, store, edited),
                () -> CurrencyAdminMenus.openEdit(player, row));
    }

    private interface Button {
        void click(Player player, ActionArguments args, CurrencyStore store);
    }

    private interface RowButton {
        void click(Player player, CurrencyStore store, CurrencyRow row);
    }

    /** A button only an economy admin may press, while the economy runs. */
    private void button(String id, Button button) {
        actions.registerSync(id, (ctx, args) -> {
            Player player = ctx.player();
            if (player == null) return ActionResult.success();
            if (!player.hasPermission(Permissions.ADMIN)) {
                Messages.send(player, EconomyMessages.get().permissionDenied());
                return ActionResult.success();
            }
            CurrencyStore store = StoredEconomy.store();
            if (store == null) {
                Messages.send(player, EconomyMessages.get().economyOff());
                return ActionResult.success();
            }
            button.click(player, args, store);
            return ActionResult.success();
        });
    }

    /** A button about the currency its first argument names. */
    private void withRow(String id, RowButton button) {
        button(id, (player, args, store) -> {
            String currency = args.string(0, "");
            store.get(currency).ifPresentOrElse(row -> button.click(player, store, row),
                    () -> Messages.send(player, EconomyMessages.get().currencyNotFound(), Values.of("id", currency)));
        });
    }

    private static Material kindIcon(CurrencyRow.Kind kind) {
        return switch (kind) {
            case STORED -> Material.GOLD_INGOT;
            case ITEM -> Material.NETHERITE_INGOT;
            case DISPLAY -> Material.PAINTING;
        };
    }

    /** The same for a form, which answers with nothing when it was not submitted. */
    private <T> void formed(Player player, CompletionStage<Optional<T>> asked,
                            Consumer<T> accepted, Runnable abandoned) {
        asked.thenAccept(result -> plugin.getTasks().runAtEntity(player,
                () -> result.ifPresentOrElse(accepted, abandoned)));
    }

    private static EconomyMessages.CurrencyAdmin admin() {
        return EconomyMessages.get().admin();
    }
}
