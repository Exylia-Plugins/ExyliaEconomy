package net.exylia.exyliaEconomy;

import lombok.Getter;
import net.exylia.exyliaEconomy.action.EconomyActionRegister;
import net.exylia.exyliaEconomy.command.AliasCommands;
import net.exylia.exyliaEconomy.command.BalanceCommand;
import net.exylia.exyliaEconomy.command.BalanceTopCommand;
import net.exylia.exyliaEconomy.command.EconomyAdminCommand;
import net.exylia.exyliaEconomy.command.EconomyCommand;
import net.exylia.exyliaEconomy.command.PayCommand;
import net.exylia.exyliaEconomy.command.WalletCommand;
import net.exylia.exyliaEconomy.common.Messages;
import net.exylia.exyliaEconomy.common.Values;
import net.exylia.exyliaEconomy.config.EconomyConfig;
import net.exylia.exyliaEconomy.config.EconomyMessages;
import net.exylia.exyliaEconomy.manager.StoredEconomy;
import net.exylia.exyliaEconomy.menu.EconomyMenus;
import net.exylia.exyliaEconomy.migration.SurvivalCoreImport;
import net.exylia.exyliaEconomy.placeholder.EconomyPlaceholder;
import net.exylia.exyliaEconomy.service.EconomyActions;
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
import org.bukkit.plugin.java.JavaPlugin;
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
 */
@Getter
public final class ExyliaEconomy extends JavaPlugin {

    /** The namespace the menu buttons' actions are written against. */
    private static final String NAMESPACE = "exyliaeconomy";

    @Getter
    private static ExyliaEconomy instance;

    private TaskScheduler tasks;
    private Debug debug;
    private PluginInputs inputs;
    private PluginRewards rewards;
    private PluginActions actions;
    private EconomyMenus menus;
    private Reloads reloads;

    /**
     * This plugin, as the library and the rest of the code ask for it.
     *
     * <p>Kept as a method so code written against the plugin reads the same wherever it runs.
     */
    public JavaPlugin getPlugin() {
        return this;
    }

    @Override
    public void onEnable() {
        instance = this;

        // Before anything opens the database: the survival core's database.yml is what makes this
        // plugin read the balances it already holds rather than a new, empty database.
        SurvivalCoreImport.files(this);

        tasks = Tasks.of(this);
        debug = Debug.of(this);
        inputs = Inputs.of(this);
        // Item and experience currencies pay through these. A payment to somebody who is not here,
        // or whose inventory is full, waits in the table and is handed over on their next join.
        rewards = Rewards.of(this)
                .overflow(OverflowPolicy.QUEUE)
                .pending(PendingRewards.database(this))
                .claimOnJoin((viewer, delivery) -> {
                    if (delivery.given() <= 0) return;
                    Messages.send(viewer, EconomyMessages.get().rewardsDelivered(),
                            Values.of().put("amount", delivery.given()));
                });

        loadConfigs();
        debug.motd();

        actions = Actions.of(this, NAMESPACE);
        new EconomyActionRegister(this).registerAll();
        // After the actions: compiling a menu resolves every action its buttons name.
        menus = new EconomyMenus(this, NAMESPACE);
        menus.load();

        // Here, during enable, and not later: the Vault bridge registers as it is built, and a
        // plugin that looks for Vault in its own enable must find it.
        StoredEconomy.init(this, () -> AliasCommands.install(this, new EconomyActions()));
        EconomyPlaceholder.register(this);

        var lamp = BukkitLamp.builder(this).build();
        lamp.register(new EconomyCommand());
        lamp.register(new EconomyAdminCommand());
        // The spellings every player already knows, all on the default currency: the
        // multi-currency answer is /wallet.
        lamp.register(new BalanceCommand());
        lamp.register(new PayCommand());
        lamp.register(new BalanceTopCommand());
        lamp.register(new WalletCommand());

        loadReloads();
        debug.success("ExyliaEconomy enabled");
    }

    @Override
    public void onDisable() {
        AliasCommands.uninstall();
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
        EconomyConfig.install(Configs.define(this, "config", EconomyConfig.class).load());
        EconomyMessages.install(Configs.define(this, "messages", EconomyMessages.class).translated().load());
        applyConfigs();
    }

    /** What has to follow every read of the files. */
    private void applyConfigs() {
        Prefixes.set(this, EconomyMessages.get().prefix());
        debug.enabled(EconomyConfig.get().debug());
    }

    /**
     * Declares what {@code /economyadmin reload} does.
     *
     * <p>Menus are also a library-reload step: their items hold the parsed palette, so a recolour
     * through {@code /exylialib reload} would otherwise leave them on the old one.
     */
    private void loadReloads() {
        reloads = Reloads.of(this)
                .step("configs", () -> {
                    Configs.reloadAll(this);
                    applyConfigs();
                })
                // Read off the game thread and applied back on it; the alias commands follow.
                .step("currencies", StoredEconomy::reload)
                .stepAlsoOnLibraryReload("menus", menus::reload);
    }
}
