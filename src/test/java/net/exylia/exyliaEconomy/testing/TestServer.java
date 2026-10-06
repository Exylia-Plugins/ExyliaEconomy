package net.exylia.exyliaEconomy.testing;

import net.exylia.exyliaEconomy.ExyliaEconomy;
import net.exylia.lib.debug.Debug;
import net.exylia.lib.task.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.event.Event;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

/**
 * Just enough of a Bukkit server for ExyliaLib's database and tasks to run for real.
 *
 * <p>Tasks run on a thread pool, and a delay is waited in wall time at fifty
 * milliseconds a tick, so code that waits for another server — a handover, a
 * lock retry — waits the way it does in production. There is no game thread
 * and nobody online.
 *
 * <p>Also carries the reflection the manager tests need to reach the state a
 * running plugin would have built, since the managers read their plugin from
 * a singleton a test cannot create.
 */
public final class TestServer {

    private static final ScheduledExecutorService POOL = Executors.newScheduledThreadPool(16, runnable -> {
        Thread thread = new Thread(runnable, "test-server");
        thread.setDaemon(true);
        return thread;
    });
    private static boolean installed;
    private static final List<Event> EVENTS = new CopyOnWriteArrayList<>();

    private TestServer() {
    }

    /** Installs the server, once per test JVM, over any stand-in another test left. */
    public static synchronized void install() {
        if (installed) return;
        ClassLoader loader = TestServer.class.getClassLoader();
        BukkitScheduler scheduler = (BukkitScheduler) Proxy.newProxyInstance(loader, new Class<?>[]{BukkitScheduler.class},
                (self, method, args) -> method.getName().startsWith("runTask") ? schedule(method.getName(), args)
                        : defaultValue(method.getReturnType()));
        Object plugins = Proxy.newProxyInstance(loader, new Class<?>[]{PluginManager.class}, (self, method, args) -> {
            if (method.getName().equals("callEvent")) {
                EVENTS.add((Event) args[0]);
                return null;
            }
            return defaultValue(method.getReturnType());
        });
        Object services = proxy(ServicesManager.class);
        Object console = Proxy.newProxyInstance(loader, new Class<?>[]{ConsoleCommandSender.class},
                (self, method, args) -> defaultValue(method.getReturnType()));
        Logger logger = Logger.getLogger("TestServer");
        Server server = (Server) Proxy.newProxyInstance(loader, new Class<?>[]{Server.class},
                (self, method, args) -> switch (method.getName()) {
                    case "getScheduler" -> scheduler;
                    case "getPluginManager" -> plugins;
                    case "getServicesManager" -> services;
                    case "getConsoleSender" -> console;
                    case "getOnlinePlayers" -> List.of();
                    case "getLogger" -> logger;
                    case "getName" -> "TestServer";
                    case "getVersion", "getBukkitVersion" -> "1.21.4";
                    case "hashCode" -> System.identityHashCode(self);
                    case "equals" -> self == args[0];
                    default -> defaultValue(method.getReturnType());
                });
        try {
            // Written straight into the field: Paper's setServer wants build info a test has none of.
            Field field = Bukkit.class.getDeclaredField("server");
            field.setAccessible(true);
            field.set(null, server);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
        installed = true;
    }

    private static BukkitTask schedule(String name, Object[] args) {
        Runnable body = (Runnable) args[1];
        long delay = args.length > 2 && args[2] instanceof Long ticks ? ticks : 0L;
        long period = args.length > 3 && args[3] instanceof Long ticks ? ticks : 0L;
        AtomicBoolean cancelled = new AtomicBoolean();
        Runnable guarded = () -> {
            if (!cancelled.get()) body.run();
        };
        Future<?> future = name.contains("Timer")
                ? POOL.scheduleAtFixedRate(guarded, delay * 50L, Math.max(1L, period) * 50L, TimeUnit.MILLISECONDS)
                : POOL.schedule(guarded, delay * 50L, TimeUnit.MILLISECONDS);
        return (BukkitTask) Proxy.newProxyInstance(TestServer.class.getClassLoader(), new Class<?>[]{BukkitTask.class},
                (self, method, taskArgs) -> switch (method.getName()) {
                    case "cancel" -> {
                        cancelled.set(true);
                        future.cancel(false);
                        yield null;
                    }
                    case "isCancelled" -> cancelled.get();
                    case "hashCode" -> System.identityHashCode(self);
                    case "equals" -> self == taskArgs[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    /** The events fired of one type, oldest first; they are kept for the whole test JVM. */
    public static <T extends Event> List<T> events(Class<T> type) {
        return EVENTS.stream().filter(type::isInstance).map(type::cast).toList();
    }

    /** A plugin by this name. Two plugins stand for two servers; the name also picks the database. */
    public static Plugin plugin(String name) {
        PluginDescriptionFile description = new PluginDescriptionFile(name, "1.0", "test.Main");
        Logger logger = Logger.getLogger(name);
        File folder = new File(System.getProperty("java.io.tmpdir"), "exyliaeconomy-test-" + name);
        return (Plugin) Proxy.newProxyInstance(TestServer.class.getClassLoader(), new Class<?>[]{Plugin.class},
                (self, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "getLogger" -> logger;
                    case "isEnabled" -> true;
                    case "getDataFolder" -> folder;
                    case "getDescription", "getPluginMeta" -> description;
                    case "getServer" -> Bukkit.getServer();
                    case "hashCode" -> name.hashCode();
                    case "equals" -> args[0] instanceof Plugin other && name.equals(other.getName());
                    case "toString" -> name;
                    default -> defaultValue(method.getReturnType());
                });
    }

    /** A debug channel whose lines reach the stand-in console, for code that logs outside a running plugin. */
    public static Debug debug() {
        install();
        return Debug.of(plugin("test"));
    }

    /** Waits until the condition holds, failing the test when it never does. */
    public static void await(String what, Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) throw new AssertionError("Timed out waiting for: " + what);
            Thread.sleep(20L);
        }
    }

    /**
     * Stands up the plugin singleton the modules log through.
     *
     * <p>Only the pieces a manager reaches for outside a running server: the
     * debug channel and the scheduler. Everything else on it stays null, which
     * is what a test wants — code that needs more of the plugin than this is
     * code that needs a server.
     *
     * @param plugin the test plugin its views hang off
     */
    public static void core(Plugin plugin) throws ReflectiveOperationException {
        Object core = allocate(ExyliaEconomy.class);
        set(core, "debug", Debug.of(plugin));
        set(core, "tasks", Tasks.of(plugin));
        set(ExyliaEconomy.class, "instance", core);
    }

    // ---------------------------------------------------------- reflection

    /** An instance whose constructor never ran, for a manager whose constructor needs the running plugin. */
    @SuppressWarnings("unchecked")
    public static <T> T allocate(Class<T> type) throws ReflectiveOperationException {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (T) ((sun.misc.Unsafe) field.get(null)).allocateInstance(type);
    }

    public static void set(Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = field(target instanceof Class<?> type ? type : target.getClass(), name);
        field.set(target instanceof Class<?> ? null : target, value);
    }

    @SuppressWarnings("unchecked")
    public static <T> T get(Object target, String name) throws ReflectiveOperationException {
        return (T) field(target.getClass(), name).get(target);
    }

    public static Object call(Object target, String name, Object... args) throws Exception {
        for (Method method : target.getClass().getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == args.length) {
                method.setAccessible(true);
                try {
                    return method.invoke(target, args);
                } catch (InvocationTargetException failure) {
                    throw failure.getCause() instanceof Exception cause ? cause : failure;
                }
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> at = type; at != null; at = at.getSuperclass()) {
            try {
                Field field = at.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // Declared further up.
            }
        }
        throw new NoSuchFieldException(name);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type) {
        return (T) Proxy.newProxyInstance(TestServer.class.getClassLoader(), new Class<?>[]{type},
                (self, method, args) -> defaultValue(method.getReturnType()));
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == long.class) return 0L;
        if (type == double.class) return 0d;
        if (type == float.class) return 0f;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        return 0;
    }
}
