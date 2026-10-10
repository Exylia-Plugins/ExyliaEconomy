package net.exylia.exyliaEconomy.service;

import net.exylia.exyliaEconomy.ExyliaEconomy;
import net.exylia.lib.text.Values;
import net.exylia.exyliaEconomy.database.CurrencyRow;
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
import io.papermc.paper.event.player.PlayerPurchaseEvent;
import org.bukkit.block.Crafter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareInventoryResultEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.Inventory;
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
        if (Economy.kind(currency) != CurrencyKind.STORED || !StoredEconomy.row(currency).map(CurrencyRow::banknotes).orElse(false)) {
            ExyliaEconomy.getInstance().getMessages().send(player, text().noteDisabled(), Values.of().put("currency", info.namePlural()));
            return;
        }
        BigDecimal typed = Economy.parseAmount(typedAmount);
        BigDecimal amount = typed == null ? null : info.scale(typed);
        if (amount == null || amount.signum() <= 0) {
            ExyliaEconomy.getInstance().getMessages().send(player, text().invalidAmount());
            return;
        }
        if (StoredEconomy.loading(player.getUniqueId(), currency)) {
            ExyliaEconomy.getInstance().getMessages().send(player, text().stillLoading());
            return;
        }
        Economy.CurrencyView view = Economy.of(currency);
        if (!view.has(player.getUniqueId(), amount)) {
            EconomyActions.notEnough(player, info, amount.subtract(view.balance(player.getUniqueId())));
            return;
        }
        if (player.getInventory().firstEmpty() < 0) {
            ExyliaEconomy.getInstance().getMessages().send(player, text().noteInventoryFull());
            return;
        }
        if (!Cooldowns.tryStart(player, "exyliaeconomy:withdraw", COOLDOWN)) return;
        long now = System.currentTimeMillis();
        String scope = StoredEconomy.storageKey(currency);
        if (scope == null) {
            ExyliaEconomy.getInstance().getMessages().send(player, text().noteDisabled(), Values.of().put("currency", info.namePlural()));
            return;
        }
        BanknoteRow row = new BanknoteRow(UUID.randomUUID().toString(), currency, scope, amount,
                player.getUniqueId().toString(), player.getName(), now, 0L, "");
        // The row first: a note handed out is always one the database knows. A row whose money was
        // never taken is discarded, and is worth nothing anyway with no item carrying its id.
        notes.issue(row).whenComplete((ignored, failure) -> ExyliaEconomy.getInstance().getTasks().runAtEntity(player, () -> {
            if (failure != null) {
                ExyliaEconomy.getInstance().getDebug().error("Economy: could not record a banknote.", failure);
                ExyliaEconomy.getInstance().getMessages().send(player, text().noteFailed());
                return;
            }
            print(player, row, info);
        }, () -> discard(row)));
    }

    /** Takes the money and hands the note over, in one tick: nothing can fill the free slot between. */
    private void print(Player player, BanknoteRow row, CurrencyInfo info) {
        if (player.getInventory().firstEmpty() < 0) {
            discard(row);
            ExyliaEconomy.getInstance().getMessages().send(player, text().noteInventoryFull());
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
        ExyliaEconomy.getInstance().getMessages().send(player, text().noteWithdrawn(), Values.of().put("amount", info.format(row.amount())));
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

    // --------------------------------------------------------------- guards
    // A note is paper: vanilla would craft it into books, maps and rockets, trade it to librarians
    // and cartographers, or put it through a loom or a cartography table, and its money with it.

    private boolean holdsNote(@Nullable Inventory inventory) {
        if (inventory == null) return false;
        for (ItemStack item : inventory.getContents()) {
            if (items.values().has(item, NOTE)) return true;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (holdsNote(event.getInventory())) event.getInventory().setResult(null);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (holdsNote(event.getInventory())) event.setCancelled(true);
    }

    /** Anvil, grindstone, smithing table, loom, cartography table and stonecutter. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareResult(PrepareInventoryResultEvent event) {
        if (holdsNote(event.getInventory())) event.setResult(null);
    }

    /** Taking any result while a note sits in the inputs: crafting, merchants, every station above. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onResult(InventoryClickEvent event) {
        if (event.getSlotType() == InventoryType.SlotType.RESULT && holdsNote(event.getView().getTopInventory())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTrade(PlayerPurchaseEvent event) {
        if (holdsNote(event.getPlayer().getOpenInventory().getTopInventory())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCrafter(CrafterCraftEvent event) {
        if (event.getBlock().getState(false) instanceof Crafter crafter && holdsNote(crafter.getInventory())) {
            event.setCancelled(true);
        }
    }

    // -------------------------------------------------------------- redeem

    /** {@code /deposit}: the note in the main hand, or the off hand. */
    public void deposit(Player player) {
        if (redeem(player, EquipmentSlot.HAND) || redeem(player, EquipmentSlot.OFF_HAND)) return;
        ExyliaEconomy.getInstance().getMessages().send(player, text().noteNotHeld());
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
        // Asked here, on the player's thread: the row read later must name this same currency.
        String currency = items.values().text(held, "banknote_currency", "");
        if (!Economy.currencies().contains(currency)) {
            ExyliaEconomy.getInstance().getMessages().send(player, text().noteInvalid());
            return true;
        }
        if (!Economy.canUse(player, currency)) {
            ExyliaEconomy.getInstance().getMessages().send(player, text().noPermission(), Values.of().put("currency", Economy.info(currency).namePlural()));
            return true;
        }
        if (!Cooldowns.tryStart(player, "exyliaeconomy:redeem", COOLDOWN)) return true;
        // Out of the hand before anything else: whatever the database answers, this copy is spent.
        ItemStack one = held.clone();
        one.setAmount(1);
        if (held.getAmount() > 1) held.setAmount(held.getAmount() - 1);
        else player.getInventory().setItem(hand, null);
        UUID uuid = player.getUniqueId();
        notes.redeem(id, uuid, note -> {
            if (!note.currency().equals(currency)) return EconomyResponse.notAvailable();
            // A per-server currency is a balance on the server that printed it, not on this one.
            if (!note.scope().equals(StoredEconomy.storageKey(note.currency()))) {
                return EconomyResponse.failure(text().noteWrongServer());
            }
            return Economy.of(note.currency()).deposit(uuid, note.amount(), Transaction.of("note:redeem").by(uuid));
        }).whenComplete((done, failure) -> ExyliaEconomy.getInstance().getTasks().runAtEntity(player, () -> {
            if (failure != null) {
                ExyliaEconomy.getInstance().getDebug().error("Economy: could not redeem banknote " + id + ".", failure);
                give(player, one);
                ExyliaEconomy.getInstance().getMessages().send(player, text().noteFailed());
                return;
            }
            switch (done.outcome()) {
                case PAID -> ExyliaEconomy.getInstance().getMessages().send(player, text().noteRedeemed(), Values.of()
                        .put("amount", Economy.info(done.note().currency()).format(done.note().amount())));
                case REFUSED, STUCK -> {
                    // A stuck note comes back too: once an admin clears its claim, it redeems again.
                    give(player, one);
                    EconomyActions.refused(player, done.credit(), Economy.info(done.note().currency()));
                }
                default -> ExyliaEconomy.getInstance().getMessages().send(player, text().noteInvalid());
            }
        }, () -> {
            // ponytail: they left before the answer; a refused or failed note is logged rather than queued back.
            if (failure != null || done.outcome() == Banknotes.Outcome.REFUSED || done.outcome() == Banknotes.Outcome.STUCK) {
                ExyliaEconomy.getInstance().getDebug().error("Economy: banknote " + id + " of " + uuid
                        + " was not redeemed and its holder left before the item could be handed back. It is still"
                        + " unredeemed in exylia_banknotes: pay its amount by hand and mark the row redeemed.");
            }
        }));
        return true;
    }
}
