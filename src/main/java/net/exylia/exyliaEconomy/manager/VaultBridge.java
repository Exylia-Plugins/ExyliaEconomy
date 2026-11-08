package net.exylia.exyliaEconomy.manager;

import io.papermc.paper.plugin.provider.classloader.ConfiguredPluginClassLoader;
import net.exylia.lib.debug.Debug;
import net.exylia.lib.economy.CurrencyInfo;
import net.exylia.lib.economy.Economy;
import net.exylia.lib.economy.EconomyResponse;
import net.exylia.lib.economy.Transaction;
import net.exylia.lib.player.ExyliaPlayer;
import net.exylia.lib.player.ExyliaPlayers;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Publishes one stored currency as the server's Vault economy.
 *
 * <p>Every plugin that only speaks Vault — a chest shop, a jobs plugin, a
 * rank ladder — then runs on a currency this plugin keeps, with no
 * EssentialsX or CMI underneath. The Vault interface is implemented as a
 * {@link Proxy} over a class looked up by name, the same way ExyliaLib
 * <em>reads</em> Vault: nothing here links against it, and a server without
 * Vault pays a caught {@code ClassNotFoundException} and nothing more.
 *
 * <p>{@code vault.provide} names the currency. By default it is registered at
 * the highest priority, above any economy plugin the owner also installed —
 * EssentialsX, CMI — because a server that set a currency up here means it.
 * With "Vault above" turned off it is registered at the lowest instead, and
 * serves only while nothing else does. Two plugins both certain they
 * are the server's economy is how balances split in two.
 *
 * <h2>Registered before the currencies are read</h2>
 * The currencies arrive on the first tick, after every plugin has enabled, and
 * a plugin that looks for Vault in its own enable and finds nothing turns its
 * economy off for the whole session. So there is one proxy, registered as this
 * bridge is built, and it asks which currency it serves on every call: until
 * the currencies land it answers with an empty balance and refused payments,
 * and a plugin that kept the proxy keeps working through every reload.
 *
 * <p>Nothing here tells the library. Its {@code vault} currency is found when
 * Vault's service is registered, it asks Vault for the economy on every call,
 * and {@link StoredCurrency#servesVault()} is how it lists the balance once.
 */
public final class VaultBridge {

    private static final String ECONOMY = "net.milkbowl.vault.economy.Economy";
    private static final String RESPONSE = "net.milkbowl.vault.economy.EconomyResponse";
    private static final String RESPONSE_TYPE = "net.milkbowl.vault.economy.EconomyResponse$ResponseType";

    /**
     * The plugin a class belongs to, lower-case, or empty for the server's own.
     *
     * <p>Up the class loaders, because a plugin started by a loader
     * runs from a loader of its own whose parent is the plugin's.
     */
    private static final ClassValue<String> OWNERS = new ClassValue<>() {
        @Override
        protected String computeValue(Class<?> type) {
            for (ClassLoader loader = type.getClassLoader(); loader != null; loader = loader.getParent()) {
                if (loader instanceof ConfiguredPluginClassLoader owner) {
                    return owner.getConfiguration().getName().toLowerCase(Locale.ROOT);
                }
            }
            return "";
        }
    };

    /** Plugins that only hand a call on (the API, its v2 bridge, the library, the analytics agent's watcher): never "who paid". */
    private static final Set<String> PASS_THROUGH = Set.of("vault", "vaultunlocked", "exylialib", "exyliaanalytics");

    /** The first plugin on this thread's stack other than {@code self} and the pass-throughs, lower-case; empty when none. */
    public static String callingPlugin(String self) {
        return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk(frames -> frames
                .map(frame -> OWNERS.get(frame.getDeclaringClass()))
                .filter(owner -> !owner.isEmpty() && !owner.equals(self) && !PASS_THROUGH.contains(owner))
                .findFirst()
                .orElse(""));
    }

    /** The transaction a Vault change is filed under; the plugin is only looked up when needed, that walks the stack. */
    static Transaction reason(@Nullable Transaction relayed, Supplier<String> plugin) {
        if (relayed != null && relayed != Transaction.NONE) return relayed;
        String name = plugin.get();
        return Transaction.of(name.isEmpty() ? "vault" : "vault:" + name);
    }

    private final Plugin plugin;
    private final String self;
    private final @Nullable Class<?> economyClass;
    private final @Nullable Object proxy;
    /** Vault's response and its type, looked up once rather than on every payment. */
    private final @Nullable Constructor<?> responseConstructor;
    private final @Nullable Class<? extends Enum> responseTypeClass;
    private volatile StoredCurrency current;
    /**
     * What {@link #serving()} answers: asking the services manager takes its
     * lock, and placeholders ask from scoreboard threads many times a second.
     * Worked out again whenever a service comes or goes.
     */
    private volatile StoredCurrency serving;

    VaultBridge(Plugin plugin) {
        this.plugin = plugin;
        this.self = plugin.getName().toLowerCase(Locale.ROOT);
        Class<?> service;
        try {
            service = Class.forName(ECONOMY);
        } catch (ClassNotFoundException absent) {
            service = null;
        }
        this.economyClass = service;
        this.proxy = service == null ? null
                : Proxy.newProxyInstance(service.getClassLoader(), new Class<?>[] {service}, new Handler());
        Constructor<?> constructor = null;
        Class<? extends Enum> typeClass = null;
        if (service != null) {
            try {
                typeClass = Class.forName(RESPONSE_TYPE).asSubclass(Enum.class);
                constructor = Class.forName(RESPONSE).getConstructor(double.class, double.class, typeClass, String.class);
            } catch (ReflectiveOperationException unexpected) {
                Debug.of(plugin).warn("Economy: Vault's EconomyResponse is not the expected shape: " + unexpected);
            }
        }
        this.responseConstructor = constructor;
        this.responseTypeClass = typeClass;
        if (proxy != null) registerService(ServicePriority.Lowest);
    }

    /** Serves the currency through Vault, or stops serving when it is {@code null}. */
    void publish(@Nullable StoredCurrency currency, boolean force) {
        if (proxy == null) {
            if (currency != null) {
                Debug.of(plugin).warn("Economy: vault.provide names '" + currency.id()
                        + "' but Vault is not installed, so nothing is published.");
            }
            return;
        }
        unregisterService();
        RegisteredServiceProvider<?> other = Bukkit.getServicesManager().getRegistration(economyClass);
        current = currency;
        refresh();
        if (currency == null) {
            if (other == null) {
                Debug.of(plugin).warn("Economy: no stored currency is published to Vault and no other"
                        + " economy plugin is installed, so plugins that use Vault, and the 'vault' currency,"
                        + " have no economy. Turn Vault on for a currency in /economyadmin.");
            }
            return;
        }
        registerService(force ? ServicePriority.Highest : ServicePriority.Lowest);
        refresh();
        if (other != null && !force) {
            Debug.of(plugin).log("Economy: " + other.getPlugin().getName() + " provides the Vault economy; '"
                    + currency.id() + "' is registered beneath it and serves only without it."
                    + " Turn on 'Vault above' in /economyadmin to put it on top.");
        } else {
            Debug.of(plugin).log("Economy: '" + currency.id() + "' is the server's Vault economy.");
        }
    }

    /** The currency Vault hands out right now, or {@code null} when that is not this bridge. */
    @Nullable StoredCurrency serving() {
        return serving;
    }

    /** What Vault was handed for this currency, whether or not another plugin wraps it; {@code null} if not published. */
    @Nullable Object service(StoredCurrency currency) {
        return current == currency ? proxy : null;
    }

    /** Works out again who Vault hands out: on a publish, and whenever any plugin's service comes or goes. */
    void refresh() {
        StoredCurrency currency = current;
        if (proxy == null || currency == null) {
            serving = null;
            return;
        }
        RegisteredServiceProvider<?> registration = Bukkit.getServicesManager().getRegistration(economyClass);
        serving = registration != null && wraps(registration.getProvider(), 3) ? currency : null;
    }

    /**
     * Whether a Vault service is ours, or a pass-through in front of it: a
     * plugin that watches Vault (ExyliaAnalytics) puts a proxy on top that
     * forwards every call here. Taking that for another economy would split
     * 'vault' from the currency it really is.
     *
     * <p>ponytail: reads the handler's fields, so only reflective proxies
     * holding the delegate in a field are seen through; a wrapper written as
     * a class of its own still reads as another economy.
     */
    private boolean wraps(@Nullable Object service, int depth) {
        if (service == proxy) return true;
        if (service == null || depth == 0 || !Proxy.isProxyClass(service.getClass())) return false;
        Object handler = Proxy.getInvocationHandler(service);
        for (Class<?> type = handler.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                try {
                    field.setAccessible(true);
                    if (wraps(field.get(handler), depth - 1)) return true;
                } catch (ReflectiveOperationException | RuntimeException sealed) {
                    // A field this module may not open: not ours to read.
                }
            }
        }
        return false;
    }

    /** Whether an economy plugin other than this bridge is registered with Vault. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    boolean othersServe() {
        if (proxy == null) return false;
        for (Object registration : Bukkit.getServicesManager().getRegistrations((Class) economyClass)) {
            if (((RegisteredServiceProvider<?>) registration).getProvider() != proxy) return true;
        }
        return false;
    }

    void unpublish() {
        current = null;
        serving = null;
        if (proxy != null) unregisterService();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void registerService(ServicePriority priority) {
        Bukkit.getServicesManager().register((Class) economyClass, proxy, plugin, priority);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void unregisterService() {
        Bukkit.getServicesManager().unregister((Class) economyClass, proxy);
    }

    /**
     * Vault's double as the currency's amount, rounded half up to its
     * decimals: {@code 2.3 * 3} is {@code 6.8999999999999995}, and cutting it
     * down would charge 6.89. {@code null} for no amount at all.
     */
    static @Nullable BigDecimal money(CurrencyInfo info, double amount) {
        if (!Double.isFinite(amount) || amount < 0D) return null;
        return BigDecimal.valueOf(amount).setScale(info.scaleDigits(), RoundingMode.HALF_UP);
    }

    /** Answers Vault's interface with the library's operations, on whichever currency is served. */
    private final class Handler implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            StoredCurrency currency = current;
            switch (name) {
                case "equals" -> { return proxy == args[0]; }
                case "hashCode" -> { return System.identityHashCode(proxy); }
                case "toString" -> { return "VaultBridge[" + (currency == null ? "loading" : currency.id()) + "]"; }
                case "isEnabled" -> { return true; }
                case "getName" -> { return currency == null ? plugin.getName() : plugin.getName() + ":" + currency.id(); }
                case "hasBankSupport" -> { return false; }
                case "hasAccount", "createPlayerAccount" -> { return true; }
                case "getBanks" -> { return List.of(); }
                default -> { }
            }
            if (currency == null) return loading(method, args);
            switch (name) {
                case "fractionalDigits" -> { return currency.info().decimals(); }
                case "format" -> { return currency.info().format(BigDecimal.valueOf((Double) args[0])); }
                case "currencyNamePlural" -> { return currency.info().namePlural(); }
                case "currencyNameSingular" -> { return currency.info().name(); }
                case "getBalance" -> { return balance(currency, player(args[0])); }
                case "has" -> { return balance(currency, player(args[0])) >= amount(args); }
                case "depositPlayer" -> { return response(deposit(currency, player(args[0]), amount(args))); }
                case "withdrawPlayer" -> { return response(withdraw(currency, player(args[0]), amount(args))); }
                default -> { return unsupported(method); }
            }
        }

        /** Before the currencies are read: nothing held, nothing paid, said so. */
        private Object loading(Method method, Object[] args) throws ReflectiveOperationException {
            return switch (method.getName()) {
                case "format" -> String.valueOf(args[0]);
                case "currencyNamePlural", "currencyNameSingular" -> "";
                case "depositPlayer", "withdrawPlayer" -> vaultResponse(0D, 0D, "FAILURE",
                        "The economy is still loading.");
                default -> unsupported(method);
            };
        }

        /** Banks, and anything Vault grows later: not supported, said so. */
        private Object unsupported(Method method) throws ReflectiveOperationException {
            if (method.getReturnType().getName().equals(RESPONSE)) {
                return vaultResponse(0D, 0D, "NOT_IMPLEMENTED", plugin.getName() + " does not support bank accounts.");
            }
            if (method.getReturnType() == boolean.class) return false;
            if (method.getReturnType() == double.class) return 0D;
            if (method.getReturnType() == int.class) return 0;
            return null;
        }

        /** Vault hands a player as an {@link OfflinePlayer} or as a name. */
        private @Nullable UUID player(Object argument) {
            if (argument instanceof OfflinePlayer offline) return offline.getUniqueId();
            if (argument instanceof String typed) {
                ExyliaPlayer cached = ExyliaPlayers.cached(typed);
                return cached == null ? null : cached.id();
            }
            return null;
        }

        /** The amount is the last double argument, whatever else is there. */
        private double amount(Object[] args) {
            for (int index = args.length - 1; index >= 0; index--) {
                if (args[index] instanceof Double value) return value;
            }
            return 0D;
        }

        /** In memory for a player held here; read from the row, in line, for anybody else. */
        private double balance(StoredCurrency currency, @Nullable UUID player) {
            return player == null ? 0D : currency.balanceNow(player).doubleValue();
        }

        private EconomyResponse deposit(StoredCurrency currency, @Nullable UUID player, double amount) {
            if (player == null) return EconomyResponse.failure("Unknown player.");
            BigDecimal money = money(currency.info(), amount);
            if (money == null) return EconomyResponse.invalidAmount();
            return Economy.of(currency.id()).deposit(player, money, caller());
        }

        /**
         * Only from a player held here. A Vault plugin charging somebody offline
         * — upkeep, a tax — is refused: queued, the charge would floor at zero
         * later and the plugin would have been told it was paid.
         */
        private EconomyResponse withdraw(StoredCurrency currency, @Nullable UUID player, double amount) {
            if (player == null) return EconomyResponse.failure("Unknown player.");
            if (!currency.isLoaded(player)) {
                return EconomyResponse.failure("Only the balance of a player on this server can be charged.");
            }
            BigDecimal money = money(currency.info(), amount);
            if (money == null) return EconomyResponse.invalidAmount();
            return Economy.of(currency.id()).withdraw(player, money, caller());
        }

        /**
         * The reason an ExyliaLib caller gave, carried through Vault; otherwise
         * {@code vault:<plugin>} for the plugin that called Vault, so the ledger
         * and the analytics say which jobs or shop plugin moved the money;
         * plain {@code vault} when no plugin is found on the stack.
         */
        private Transaction caller() {
            return reason(Transaction.relayed(), () -> callingPlugin(self));
        }

        /** What actually moved, never what was asked: a deposit refused at a ceiling is a failure. */
        private Object response(EconomyResponse ours) throws ReflectiveOperationException {
            String type = ours.isSuccess() ? "SUCCESS" : "FAILURE";
            return vaultResponse(ours.amount().doubleValue(), ours.balance().doubleValue(), type, ours.message());
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private Object vaultResponse(double amount, double balance, String type, String message)
                throws ReflectiveOperationException {
            if (responseConstructor == null) throw new ClassNotFoundException(RESPONSE);
            return responseConstructor.newInstance(amount, balance, Enum.valueOf((Class) responseTypeClass, type), message);
        }
    }
}
