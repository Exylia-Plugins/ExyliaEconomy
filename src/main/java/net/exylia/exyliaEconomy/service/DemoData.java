package net.exylia.exyliaEconomy.service;

import net.exylia.exyliaEconomy.ExyliaEconomy;
import net.exylia.exyliaEconomy.config.EconomyMessages;
import net.exylia.exyliaEconomy.database.CurrencyRow;
import net.exylia.exyliaEconomy.manager.CurrencyStore;
import net.exylia.exyliaEconomy.manager.StoredEconomy;
import net.exylia.lib.economy.Economy;
import net.exylia.lib.economy.EconomyResponse;
import net.exylia.lib.economy.Transaction;
import net.exylia.lib.text.Values;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /economyadmin demo seed|clear}: made-up players for screenshots of the
 * wallets and leaderboards, and their removal. For test servers only.
 *
 * <p>The players have fixed ids, so clear finds them again without a record.
 * What has to be recorded — the demo currencies and the sender's own grant — is
 * kept in {@code demo.yml}, so clear reverses exactly that and nothing else.
 */
public final class DemoData {

    static final List<String> NAMES = List.of(
            "Kaelith", "xNova_", "PixelDrift", "Mossy_Lantern", "Vexorin", "Frostbyte27", "LunaCraft",
            "Ironvale", "Sir_Pancake", "Zephyrine", "Obsidian_Kai", "TheRealMarlo", "Quillblade", "emberwisp",
            "Copper_Fox", "Nyxara", "BlockSmith88", "Tidecaller", "Rune_Wanderer", "Sablewing", "jellyroll_",
            "Mirelle", "DuskHollow", "Astraeon", "PebbleKnight", "Voltaren", "Cinderpaw", "Hollow_Ivy",
            "Orbitz", "Glimmerstone");

    private static final String FILE = "demo.yml";
    private static final String REASON = "admin:demo";
    private static final List<String> REASONS = List.of(
            "shop:sell", "pay", "market:sale", "auctions:sale", "sell:autosell", "market:buy", "interest");
    private static final long HOUR = 3_600_000L;

    /** The sender is granted about what the fourth richest holds: high on the board, not on top of it. */
    private static final int GRANT_RANK = 4;

    public static UUID uuid(String name) {
        return UUID.nameUUIDFromBytes(("exylia-economy-demo:" + name).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A long tail of balances for one currency, richest first: a few very
     * rich, many modest, no round numbers, the same every time for an id.
     */
    static List<BigDecimal> amounts(String currency, int decimals, int count) {
        Random random = new Random(("exylia-economy-demo:" + currency).hashCode());
        double top = Math.pow(10, 4 + random.nextInt(3)) * (1.5 + random.nextDouble() * 7);
        int scale = Math.max(0, decimals);
        List<BigDecimal> out = new ArrayList<>(count);
        for (int rank = 0; rank < count; rank++) {
            double value = top * Math.pow(rank + 1, -1.3) * (0.9 + random.nextDouble() * 0.2);
            BigDecimal amount = BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP);
            if (scale == 0 && amount.remainder(BigDecimal.TEN).signum() == 0) {
                amount = amount.add(BigDecimal.valueOf(1 + random.nextInt(9)));
            }
            if (scale > 0 && amount.stripTrailingZeros().scale() <= 0) {
                amount = amount.add(BigDecimal.ONE.movePointLeft(scale).multiply(BigDecimal.valueOf(13 + random.nextInt(80))));
            }
            out.add(amount);
        }
        out.sort(Collections.reverseOrder());
        return out;
    }

    /** The names in the order they rank in one currency, so every board reads differently. */
    static List<String> ranking(String currency) {
        List<String> names = new ArrayList<>(NAMES);
        Collections.shuffle(names, new Random(("exylia-economy-demo-order:" + currency).hashCode()));
        return names;
    }

    /** Three plausible ledger lines ending at the balance, oldest first. */
    static List<StoredEconomy.SeedLine> lines(String currency, String name, BigDecimal amount, int decimals) {
        Random random = new Random(("exylia-economy-demo-ledger:" + currency + ":" + name).hashCode());
        long now = System.currentTimeMillis();
        int scale = Math.max(0, decimals);
        List<StoredEconomy.SeedLine> newestFirst = new ArrayList<>();
        BigDecimal after = amount;
        long at = now - (1 + random.nextInt(5)) * HOUR;
        for (int index = 0; index < 3; index++) {
            String reason = REASONS.get(random.nextInt(REASONS.size()));
            BigDecimal size = amount.multiply(BigDecimal.valueOf(0.02 + random.nextDouble() * 0.12))
                    .setScale(scale, RoundingMode.HALF_UP).max(BigDecimal.ONE);
            BigDecimal delta = reason.equals("market:buy") || (reason.equals("pay") && random.nextBoolean())
                    ? size.negate() : size;
            if (after.subtract(delta).signum() < 0) delta = delta.abs().negate();
            newestFirst.add(new StoredEconomy.SeedLine(delta, after, reason, at));
            after = after.subtract(delta);
            at -= (2 + random.nextInt(22)) * HOUR;
        }
        Collections.reverse(newestFirst);
        return newestFirst;
    }

    public void seed(CommandSender sender) {
        CurrencyStore store = ready(sender);
        if (store == null) return;
        // The plugin's thread: currency edits are registered there.
        ExyliaEconomy.getInstance().getTasks().run(() -> {
            YamlConfiguration record = read();
            List<String> created = createCurrencies(store, record);
            List<CurrencyRow> stored = store.all().stream().filter(row -> row.kind() == CurrencyRow.Kind.STORED)
                    .filter(row -> StoredEconomy.storageKey(row.id()) != null).toList();
            List<CompletableFuture<Integer>> writes = new ArrayList<>();
            for (CurrencyRow row : stored) {
                List<String> order = ranking(row.id());
                List<BigDecimal> amounts = amounts(row.id(), row.decimals(), order.size());
                List<StoredEconomy.Seed> seeds = new ArrayList<>();
                for (int index = 0; index < order.size(); index++) {
                    String name = order.get(index);
                    seeds.add(new StoredEconomy.Seed(uuid(name), name, amounts.get(index),
                            lines(row.id(), name, amounts.get(index), row.decimals())));
                }
                writes.add(StoredEconomy.seedUnheld(row.id(), seeds));
            }
            int granted = sender instanceof Player player ? grant(player, stored, record) : 0;
            write(record);
            CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new)).whenComplete((done, failure) ->
                    ExyliaEconomy.getInstance().getTasks().run(() -> {
                        if (failure != null) {
                            ExyliaEconomy.getInstance().getDebug().error("Economy: demo seed failed.", failure);
                            send(sender, text().failed(), Values.of());
                            return;
                        }
                        send(sender, text().seedDone(), Values.of().put("players", NAMES.size())
                                .put("currencies", stored.size()).put("created", created.size())
                                .put("granted", granted));
                        send(sender, text().seedWarning(), Values.of());
                    }));
        });
    }

    public void clear(CommandSender sender) {
        CurrencyStore store = ready(sender);
        if (store == null) return;
        ExyliaEconomy.getInstance().getTasks().run(() -> {
            YamlConfiguration record = read();
            int[] grants = ungrant(sender, record);
            List<CompletableFuture<?>> deletes = new ArrayList<>();
            int currencies = 0;
            for (String id : record.getStringList("currencies")) {
                if (store.get(id).isEmpty()) continue;
                deletes.add(store.delete(id));
                currencies++;
            }
            record.set("currencies", null);
            write(record);
            if (!deletes.isEmpty()) StoredEconomy.changed(CompletableFuture.allOf(deletes.toArray(CompletableFuture[]::new)));
            int removed = currencies;
            StoredEconomy.clearUnheld(NAMES.stream().map(DemoData::uuid).toList()).whenComplete((balances, failure) ->
                    ExyliaEconomy.getInstance().getTasks().run(() -> {
                        if (failure != null) {
                            ExyliaEconomy.getInstance().getDebug().error("Economy: demo clear failed.", failure);
                            send(sender, text().failed(), Values.of());
                            return;
                        }
                        send(sender, text().clearDone(), Values.of().put("balances", balances)
                                .put("currencies", removed).put("reversed", grants[0]).put("kept", grants[1]));
                    }));
        });
    }

    // ------------------------------------------------------------- currencies

    /** On a server with one stored currency or none, three more to show what the plugin does. */
    private List<String> createCurrencies(CurrencyStore store, YamlConfiguration record) {
        List<CurrencyRow> all = store.all();
        if (all.stream().filter(row -> row.kind() == CurrencyRow.Kind.STORED).count() > 1) return List.of();
        String main = all.stream().filter(row -> row.kind() == CurrencyRow.Kind.STORED).map(CurrencyRow::id)
                .findFirst().orElse(null);
        int order = all.stream().mapToInt(CurrencyRow::sortOrder).max().orElse(0);
        List<CurrencyRow> rows = new ArrayList<>();
        rows.add(CurrencyRow.blank("gems", CurrencyRow.Kind.STORED, ++order).edit(row -> {
            row.name = "{info}Gem";
            row.plural = "{info}Gems";
            row.symbol = "◆";
            row.icon = "EMERALD";
            row.format = "%amount% %symbol%";
            row.compactFormat = "%amount%%symbol%";
            row.aliases = List.of("gems", "gem");
            row.banknotes = true;
            row.payConfirmAbove = BigDecimal.valueOf(500);
            if (main != null) {
                row.exchangeable = true;
                row.rates = main + "=250";
            }
        }));
        rows.add(CurrencyRow.blank("tokens", CurrencyRow.Kind.STORED, ++order).edit(row -> {
            row.name = "{highlight}Token";
            row.plural = "{highlight}Tokens";
            row.symbol = "✪";
            row.icon = "SUNFLOWER";
            row.format = "%amount% %symbol%";
            row.compactFormat = "%amount%%symbol%";
            row.aliases = List.of("tokens", "token");
            row.banknotes = true;
            row.interestRate = 1.5;
            row.interestMax = BigDecimal.valueOf(250);
        }));
        rows.add(CurrencyRow.blank("souls", CurrencyRow.Kind.STORED, ++order).edit(row -> {
            row.name = "{secondary_light}Soul";
            row.plural = "{secondary_light}Souls";
            row.symbol = "☠";
            row.icon = "SOUL_LANTERN";
            row.format = "%amount% %symbol%";
            row.compactFormat = "%amount%%symbol%";
            row.aliases = List.of("souls", "soul");
            row.transferable = false;
        }));
        List<String> created = new ArrayList<>();
        List<CompletableFuture<?>> writes = new ArrayList<>();
        for (CurrencyRow row : rows) {
            if (store.get(row.id()).isPresent()) continue;
            writes.add(store.save(row));
            created.add(row.id());
        }
        if (created.isEmpty()) return created;
        List<String> marked = new ArrayList<>(record.getStringList("currencies"));
        created.stream().filter(id -> !marked.contains(id)).forEach(marked::add);
        record.set("currencies", marked);
        // Registered here at once, so the balances below find them.
        StoredEconomy.changed(CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new)));
        return created;
    }

    // ----------------------------------------------------------------- grants

    /** Gives the sender the fourth richest amount of each currency, recorded so clear takes exactly that back. */
    private int grant(Player player, List<CurrencyRow> stored, YamlConfiguration record) {
        String path = "grants." + player.getUniqueId();
        Transaction transaction = Transaction.of(REASON).by(player.getUniqueId());
        int granted = 0;
        for (CurrencyRow row : stored) {
            BigDecimal amount = amounts(row.id(), row.decimals(), GRANT_RANK).get(GRANT_RANK - 1);
            EconomyResponse response = Economy.of(row.id()).deposit(player.getUniqueId(), amount, transaction);
            if (!response.isSuccess()) continue;
            BigDecimal before = new BigDecimal(record.getString(path + "." + row.id(), "0"));
            record.set(path + "." + row.id(), before.add(response.amount()).toPlainString());
            granted++;
        }
        return granted;
    }

    /** Takes every recorded grant back; one that cannot be taken (spent, refused) stays recorded. */
    private int[] ungrant(CommandSender sender, YamlConfiguration record) {
        int reversed = 0;
        int kept = 0;
        ConfigurationSection grants = record.getConfigurationSection("grants");
        if (grants == null) return new int[]{0, 0};
        Transaction transaction = Transaction.of(REASON).by(EconomyActions.initiator(sender));
        for (String player : grants.getKeys(false)) {
            ConfigurationSection currencies = grants.getConfigurationSection(player);
            if (currencies == null) continue;
            for (String currency : currencies.getKeys(false)) {
                BigDecimal amount = new BigDecimal(currencies.getString(currency, "0"));
                if (amount.signum() > 0 && (!Economy.currencies().contains(currency)
                        || !Economy.of(currency).withdraw(UUID.fromString(player), amount, transaction).isSuccess())) {
                    kept++;
                    continue;
                }
                currencies.set(currency, null);
                reversed++;
            }
            if (currencies.getKeys(false).isEmpty()) grants.set(player, null);
        }
        return new int[]{reversed, kept};
    }

    // --------------------------------------------------------------- plumbing

    private static CurrencyStore ready(CommandSender sender) {
        CurrencyStore store = StoredEconomy.store();
        if (store == null) {
            send(sender, EconomyMessages.get().economyOff(), Values.of());
            return null;
        }
        if (StoredEconomy.loading()) {
            send(sender, EconomyMessages.get().stillLoading(), Values.of());
            return null;
        }
        return store;
    }

    private static File file() {
        return new File(ExyliaEconomy.getInstance().getPlugin().getDataFolder(), FILE);
    }

    private static YamlConfiguration read() {
        return YamlConfiguration.loadConfiguration(file());
    }

    private static void write(YamlConfiguration record) {
        try {
            record.save(file());
        } catch (IOException failure) {
            ExyliaEconomy.getInstance().getDebug().error("Economy: could not write " + FILE
                    + "; demo clear may not find what seed recorded.", failure);
        }
    }

    private static EconomyMessages.Demo text() {
        return EconomyMessages.get().demo();
    }

    private static void send(CommandSender sender, String message, Values values) {
        ExyliaEconomy.getInstance().getMessages().send(sender, message, values);
    }
}
