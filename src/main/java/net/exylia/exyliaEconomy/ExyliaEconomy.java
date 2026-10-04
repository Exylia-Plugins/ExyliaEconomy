package net.exylia.exyliaEconomy;

import lombok.Getter;
import net.exylia.exyliaEconomy.action.EconomyActionRegister;
import net.exylia.exyliaEconomy.command.AliasCommands;
import net.exylia.exyliaEconomy.command.BalanceCommand;
import net.exylia.exyliaEconomy.command.BalanceTopCommand;
import net.exylia.exyliaEconomy.command.EconomyAdminCommand;
import net.exylia.exyliaEconomy.command.EconomyCommand;
import net.exylia.exyliaEconomy.command.PayCommand;
import net.exylia.exyliaEconomy.command.PayToggleCommand;
import net.exylia.exyliaEconomy.command.WalletCommand;
import net.exylia.exyliaEconomy.command.WithdrawCommand;
import net.exylia.exyliaEconomy.command.DepositCommand;
import net.exylia.exyliaEconomy.common.Messages;
import net.exylia.exyliaEconomy.common.Values;
import net.exylia.exyliaEconomy.config.EconomyConfig;
import net.exylia.exyliaEconomy.config.EconomyMessages;
import net.exylia.exyliaEconomy.manager.StoredEconomy;
import net.exylia.exyliaEconomy.menu.EconomyMenus;
import net.exylia.exyliaEconomy.migration.SurvivalCoreImport;
import net.exylia.exyliaEconomy.placeholder.EconomyPlaceholder;
import net.exylia.exyliaEconomy.service.EconomyActions;
import net.exylia.exyliaEconomy.service.BanknoteService;
import net.exylia.exyliaEconomy.service.Interest;
import net.exylia.exyliaEconomy.service.PayNotices;
import net.exylia.lib.action.Actions;
import net.exylia.lib.action.PluginActions;
import net.exylia.lib.config.Configs;
import net.exylia.lib.debug.Debug;
import net.exylia.lib.input.Inputs;
import net.exylia.lib.input.PluginInputs;
import net.exylia.lib.reload.Reloads;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.Prefixes;
import net.exylia.lib.util.reward.OverflowPolicy;
import net.exylia.lib.util.reward.PendingRewards;
import net.exylia.lib.util.reward.PluginRewards;
import net.exylia.lib.util.reward.Rewards;
import org.bukkit.Server;
import org.bukkit.plugin.java.JavaPlugin;
import net.exylia.exyliaEconomy.command.PlayerArguments;
import revxrsal.commands.bukkit.BukkitLamp;

/**
 * ExyliaEconomy: the server's own currencies, kept in the database and shared across a network,
 * and the player's side of every economy — {@code /economy}, {@code /wallet} and one command per
 * currency.
 *
 * <p>ExyliaLib only talks to economies and keeps none itself. This plugin is one of them: its
 * stored, item and experience currencies register with the library's {@code Economy} facade, so
 * any plugin pays and charges them through ExyliaLib alone, and one of them can be published to
 * Vault for the plugins that only speak Vault.
 *
 * <p>Not the Bukkit plugin itself: {@link ExyliaEconomyPlugin} is, so it can install ExyliaLib
 * when the server lacks it. Every library view is asked for with {@link #plugin}, because the
 * library keys its per-plugin views by Bukkit plugin.
 */
@Getter
public final class ExyliaEconomy {

    /** The namespace the menu buttons' actions are written against. */
    private static final String NAMESPACE = "exyliaeconomy";

    @Getter
    private static ExyliaEconomy instance;

    /** The Bukkit plugin that started this core. */
    private JavaPlugin plugin;

    private TaskScheduler tasks;
    private Debug debug;
    private PluginInputs inputs;
    private PluginRewards rewards;
    private PluginActions actions;
    private EconomyMenus menus;
    private Reloads reloads;
    private PayNotices payNotices;
    private BanknoteService banknotes;
    private Interest interest;

    public Server getServer() {
        return plugin.getServer();
    }

    /** Starts every module on the Bukkit plugin that loaded this core. */
    public void start(JavaPlugin plugin) {
        instance = this;
        this.plugin = plugin;

        // Before anything opens the database: the survival core's database.yml is what makes this
        // plugin read the balances it already holds rather than a new, empty database.
        SurvivalCoreImport.files(plugin);

        tasks = Tasks.of(plugin);
        debug = Debug.of(plugin);
        inputs = Inputs.of(plugin);
        // Item and experience currencies pay through these. A payment to somebody who is not here,
        // or whose inventory is full, waits in the table and is handed over on their next join.
        rewards = Rewards.of(plugin)
                .overflow(OverflowPolicy.QUEUE)
                .pending(PendingRewards.database(plugin))
                .claimOnJoin((viewer, delivery) -> {
                    if (delivery.given() <= 0) return;
                    Messages.send(viewer, EconomyMessages.get().rewardsDelivered(),
                            Values.of().put("amount", delivery.given()));
                });

        loadConfigs();
        debug.motd();

        actions = Actions.of(plugin, NAMESPACE);
        new EconomyActionRegister(this).registerAll();
        // After the actions: compiling a menu resolves every action its buttons name.
        menus = new EconomyMenus(this, NAMESPACE);
        menus.load();

        // Here, during enable, and not later: the Vault bridge registers as it is built, and a
        // plugin that looks for Vault in its own enable must find it.
        StoredEconomy.init(plugin, () -> AliasCommands.install(plugin, new EconomyActions()));
        EconomyPlaceholder.register(plugin);
        payNotices = new PayNotices(plugin);
        getServer().getPluginManager().registerEvents(payNotices, plugin);
        banknotes = new BanknoteService(plugin);
        getServer().getPluginManager().registerEvents(banknotes, plugin);
        interest = new Interest(plugin);
        interest.start();

        var lamp = PlayerArguments.install(BukkitLamp.builder(plugin)).build();
        lamp.register(new EconomyCommand());
        lamp.register(new EconomyAdminCommand());
        // The spellings every player already knows, all on the default currency: the
        // multi-currency answer is /wallet.
        lamp.register(new BalanceCommand());
        lamp.register(new PayCommand());
        lamp.register(new PayToggleCommand());
        lamp.register(new BalanceTopCommand());
        lamp.register(new WalletCommand());
        lamp.register(new WithdrawCommand());
        lamp.register(new DepositCommand());

        loadReloads();
        debug.success("ExyliaEconomy enabled");
    }

    /** Releases what {@link #start} set up, waiting for the queued balance writes. */
    public void shutdown() {
        AliasCommands.uninstall();
        if (interest != null) interest.stop();
        // Before the library lets the database go: shutdown waits, bounded, for every queued balance write.
        StoredEconomy.shutdown();
        // Everything else the library owns for this plugin — menus, actions, inputs, placeholders,
        // tasks — is released by the library on plugin disable.
        instance = null;
        if (debug != null) debug.success("ExyliaEconomy disabled");
    }

    /**
     * Reads the plugin's two files.
     *
     * <p>{@link Prefixes#set} is what makes every {@code %prefix%} in {@code messages.yml} arrive
     * as the prefix, and it is repeated after each reload so an edited prefix applies at once.
     */
    private void loadConfigs() {
        EconomyConfig.install(Configs.define(plugin, "config", EconomyConfig.class).load());
        EconomyMessages.install(Configs.define(plugin, "messages", EconomyMessages.class).translated().load());
        applyConfigs();
    }

    /** What has to follow every read of the files. */
    private void applyConfigs() {
        Prefixes.set(plugin, EconomyMessages.get().prefix());
        debug.enabled(EconomyConfig.get().debug());
    }

    /**
     * Declares what {@code /economyadmin reload} does.
     *
     * <p>Menus are also a library-reload step: their items hold the parsed palette, so a recolour
     * through {@code /exylialib reload} would otherwise leave them on the old one.
     */
    private void loadReloads() {
        reloads = Reloads.of(plugin)
                .step("configs", () -> {
                    Configs.reloadAll(plugin);
                    applyConfigs();
                })
                // Read off the game thread and applied back on it; the alias commands follow.
                .step("currencies", StoredEconomy::reload)
                .stepAlsoOnLibraryReload("menus", menus::reload);
    }
}
