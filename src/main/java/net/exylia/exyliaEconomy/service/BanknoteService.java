package net.exylia.exyliaEconomy.service;

import net.exylia.exyliaEconomy.ExyliaEconomy;
import net.exylia.exyliaEconomy.common.Messages;
import net.exylia.exyliaEconomy.common.Values;
import net.exylia.exyliaEconomy.config.EconomyConfig;
import net.exylia.exyliaEconomy.config.EconomyMessages;
import net.exylia.exyliaEconomy.database.BanknoteRow;
import net.exylia.exyliaEconomy.manager.Banknotes;
import net.exylia.exyliaEconomy.manager.StoredEconomy;
import net.exylia.lib.economy.CurrencyInfo;
import net.exylia.lib.economy.CurrencyKind;
import net.exylia.lib.economy.Economy;
import net.exylia.lib.economy.EconomyResponse;
import net.exylia.lib.economy.Transaction;
import net.exylia.lib.format.Dates;
import net.exylia.lib.item.ItemValues;
import net.exylia.lib.item.Items;
import net.exylia.lib.item.PluginItems;
import net.exylia.lib.util.Cooldowns;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Banknotes: {@code /withdraw} turns money into a paper item, right-click or {@code /deposit}
 * turns it back.
 *
 * <p>The item only carries the note's id; what it is worth is its row in the database, written
 * before the money is taken. Redeeming takes the item out of the hand first and claims the row
 * before paying, so a copied note — a duplicated stack, a creative clone, the same note on two
 * servers — pays once and every other copy simply disappears. A payment the currency refuses
 * gives the claim back and the item with it.
 */
public final class BanknoteService implements Listener {

    /** The item value holding the note's id; the others are informational. */
    static final String NOTE = "banknote";
    private static final Duration COOLDOWN = Duration.ofMillis(500);

    private final Banknotes notes;
    private final PluginItems items;

    public BanknoteService(@NotNull Plugin plugin) {
        this.notes = new Banknotes(plugin);
        this.items = Items.of(plugin);
    }

    private static EconomyMessages text() {
        return EconomyMessages.get();
    }

    // ------------------------------------------------------------ withdraw

    public void withdraw(Player player, String typedAmount, @Nullable String currencyId) {
        String currency = EconomyActions.resolved(player, currencyId);
        if (currency == null) return;
        CurrencyInfo info = Economy.info(currency);
        if (Economy.kind(currency) != CurrencyKind.STORED || !EconomyConfig.get().banknotes(currency)) {
            Messages.send(player, text().noteDisabled(), Values.of().put("currency", info.namePlural()));
            return;
        }
        BigDecimal typed = Economy.parseAmount(typedAmount);
        BigDecimal amount = typed == null ? null : info.scale(typed);
        if (amount == null || amount.signum() <= 0) {
            Messages.send(player, text().invalidAmount());
            return;
        }
        if (StoredEconomy.loading(player.getUniqueId(), currency)) {
            Messages.send(player, text().stillLoading());
            return;
        }
        Economy.CurrencyView view = Economy.of(currency);
        if (!view.has(player.getUniqueId(), amount)) {
            EconomyActions.notEnough(player, info, amount.subtract(view.balance(player.getUniqueId())));
            return;
        }
        if (player.getInventory().firstEmpty() < 0) {
            Messages.send(player, text().noteInventoryFull());
            return;
        }
        if (!Cooldowns.tryStart(player, "exyliaeconomy:withdraw", COOLDOWN)) return;
        long now = System.currentTimeMillis();
        BanknoteRow row = new BanknoteRow(UUID.randomUUID().toString(), currency, amount,
                player.getUniqueId().toString(), player.getName(), now, 0L, "");
        // The row first: a note handed out is always one the database knows. A row whose money was
        // never taken is discarded, and is worth nothing anyway with no item carrying its id.
        notes.issue(row).whenComplete((ignored, failure) -> ExyliaEconomy.getInstance().getTasks().runAtEntity(player, () -> {
            if (failure != null) {
                ExyliaEconomy.getInstance().getDebug().error("Economy: could not record a banknote.", failure);
                Messages.send(player, text().noteFailed());
                return;
            }
            print(player, row, info);
        }, () -> discard(row)));
    }

    /** Takes the money and hands the note over, in one tick: nothing can fill the free slot between. */
    private void print(Player player, BanknoteRow row, CurrencyInfo info) {
        if (player.getInventory().firstEmpty() < 0) {
            discard(row);
            Messages.send(player, text().noteInventoryFull());
            return;
        }
        UUID id = player.getUniqueId();
        EconomyResponse taken = Economy.of(row.currency()).withdraw(id, row.amount(), Transaction.of("note:withdraw").by(id));
        if (!taken.isSuccess()) {
            discard(row);
            if (taken.type() == EconomyResponse.Type.INSUFFICIENT_FUNDS) {
                EconomyActions.notEnough(player, info, taken.shortfall());
            } else {
                EconomyActions.refused(player, taken, info);
            }
            return;
        }
        give(player, item(player, row, info));
        Messages.send(player, text().noteWithdrawn(), Values.of().put("amount", info.format(row.amount())));
    }

    private void discard(BanknoteRow row) {
        notes.discard(row.id()).exceptionally(failure -> null);
    }

    /** The printed note: its look from messages.yml, its id stored on it, never stackable. */
    ItemStack item(@Nullable Player viewer, BanknoteRow row, CurrencyInfo info) {
        EconomyMessages.Banknote look = text().banknote();
        YamlConfiguration section = new YamlConfiguration();
        section.set("material", look.material());
        section.set("name", look.name());
        section.set("lore", look.lore());
        section.set("max-stack-size", 1);
        section.set("glow", true);
        ItemStack stack = items.render(items.parse(section), viewer, Map.of(
                "amount", info.format(row.amount()),
                "currency", info.namePlural(),
                "issuer", row.issuerName(),
                "date", Dates.formatMillis(row.issuedAt(), Dates.Style.DATE)), Set.of("amount", "currency"));
        ItemValues values = items.values();
        values.set(stack, NOTE, row.id());
        values.set(stack, "banknote_currency", row.currency());
        values.set(stack, "banknote_amount", row.amount().toPlainString());
        values.set(stack, "banknote_issuer", row.issuer());
        return stack;
    }

    private static void give(Player player, ItemStack item) {
        for (ItemStack left : player.getInventory().addItem(item).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), left);
        }
    }

    // -------------------------------------------------------------- redeem

    /** {@code /deposit}: the note in the main hand, or the off hand. */
    public void deposit(Player player) {
        if (redeem(player, EquipmentSlot.HAND) || redeem(player, EquipmentSlot.OFF_HAND)) return;
        Messages.send(player, text().noteNotHeld());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() == null || !items.values().has(event.getItem(), NOTE)) return;
        event.setCancelled(true);
        redeem(event.getPlayer(), event.getHand());
    }

    /** @return whether that hand held a note */
    private boolean redeem(Player player, EquipmentSlot hand) {
        ItemStack held = player.getInventory().getItem(hand);
        String id = items.values().text(held, NOTE).orElse(null);
        if (id == null) return false;
        if (!Cooldowns.tryStart(player, "exyliaeconomy:redeem", COOLDOWN)) return true;
        // Out of the hand before anything else: whatever the database answers, this copy is spent.
        ItemStack one = held.clone();
        one.setAmount(1);
        if (held.getAmount() > 1) held.setAmount(held.getAmount() - 1);
        else player.getInventory().setItem(hand, null);
        UUID uuid = player.getUniqueId();
        notes.redeem(id, uuid, note -> {
            if (!Economy.currencies().contains(note.currency()) || !Economy.canUse(player, note.currency())) {
                return EconomyResponse.notAvailable();
            }
            return Economy.of(note.currency()).deposit(uuid, note.amount(), Transaction.of("note:redeem").by(uuid));
        }).whenComplete((done, failure) -> ExyliaEconomy.getInstance().getTasks().runAtEntity(player, () -> {
            if (failure != null) {
                ExyliaEconomy.getInstance().getDebug().error("Economy: could not redeem banknote " + id + ".", failure);
                give(player, one);
                Messages.send(player, text().noteFailed());
                return;
            }
            switch (done.outcome()) {
                case PAID -> Messages.send(player, text().noteRedeemed(), Values.of()
                        .put("amount", Economy.info(done.note().currency()).format(done.note().amount())));
                case REFUSED -> {
                    give(player, one);
                    EconomyActions.refused(player, done.credit(), Economy.info(done.note().currency()));
                }
                default -> Messages.send(player, text().noteInvalid());
            }
        }, () -> {
            // ponytail: they left before the answer; a refused or failed note is logged rather than queued back.
            if (failure != null || done.outcome() == Banknotes.Outcome.REFUSED) {
                ExyliaEconomy.getInstance().getPlugin().getLogger().severe("Economy: banknote " + id + " of " + uuid
                        + " was not redeemed and its holder left before the item could be handed back. It is still"
                        + " unredeemed in exylia_banknotes: pay its amount by hand and mark the row redeemed.");
            }
        }));
        return true;
    }
}
