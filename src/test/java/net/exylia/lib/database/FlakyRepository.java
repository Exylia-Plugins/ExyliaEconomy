package net.exylia.lib.database;

import net.exylia.lib.database.internal.EntityModel;
import net.exylia.lib.database.internal.Storage;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A repository over the same rows as another, whose writes lie about what they did.
 *
 * <p>The failure every duplication bug is born in is not a write that failed —
 * it is a write that <em>committed</em> and then reported failure, because the
 * connection went away before the answer came back. Nothing about the caller's
 * side of that can be tested with a database that behaves, so this puts one in
 * front of the real rows: the row is written, and the caller is told it was not.
 *
 * <p>Lives in the library's package because a repository is built from a
 * {@link Storage} and a model that only this package may hand over.
 */
public final class FlakyRepository {

    private FlakyRepository() {
    }

    /**
     * The same rows, but every {@code save} commits and then reports failure.
     *
     * @param real the repository to stand in front of
     */
    public static <T> Repository<T> committingLiar(Repository<T> real) throws ReflectiveOperationException {
        return over(real, Set.of("save"), true);
    }

    /**
     * The same rows, but every {@code save} and {@code delete} reports failure;
     * the save commits first and the delete never happens.
     *
     * <p>What an unreachable database looks like for as long as it takes to
     * decide what to do about a write nobody can confirm.
     */
    public static <T> Repository<T> unreachable(Repository<T> real) throws ReflectiveOperationException {
        return over(real, Set.of("save", "delete"), false);
    }

    /**
     * The same rows, but every {@code save} commits and then reports failure,
     * and from then on nothing can be read or removed.
     *
     * <p>A write nobody can confirm either way: the database answered the
     * first read and then went away with the write in flight.
     */
    @SuppressWarnings("unchecked")
    public static <T> Repository<T> committingThenBlind(Repository<T> real) throws ReflectiveOperationException {
        Storage storage = (Storage) read(real, "storage");
        EntityModel<T> model = (EntityModel<T>) read(real, "model");
        AtomicBoolean blind = new AtomicBoolean();
        Storage liar = (Storage) Proxy.newProxyInstance(FlakyRepository.class.getClassLoader(),
                new Class<?>[]{Storage.class}, (self, method, args) -> {
                    String name = method.getName();
                    if (name.equals("save")) {
                        ((CompletableFuture<?>) method.invoke(storage, args)).join();
                        blind.set(true);
                    } else if (!blind.get() || !Set.of("find", "exists", "delete").contains(name)) {
                        return method.invoke(storage, args);
                    }
                    return CompletableFuture.failedFuture(
                            new IllegalStateException("The database stopped answering."));
                });
        return new Repository<>(liar, model);
    }

    /**
     * The same rows, but every {@code save} waits for the gate, commits and
     * then reports failure.
     *
     * <p>For what the rest of the server can do while a write is on its way.
     *
     * @param gate completed when the writes may go ahead
     */
    @SuppressWarnings("unchecked")
    public static <T> Repository<T> gatedLiar(Repository<T> real, CompletableFuture<Void> gate)
            throws ReflectiveOperationException {
        Storage storage = (Storage) read(real, "storage");
        EntityModel<T> model = (EntityModel<T>) read(real, "model");
        Storage liar = (Storage) Proxy.newProxyInstance(FlakyRepository.class.getClassLoader(),
                new Class<?>[]{Storage.class}, (self, method, args) -> {
                    if (!method.getName().equals("save")) return method.invoke(storage, args);
                    return gate.thenCompose(open -> {
                        try {
                            return (CompletableFuture<?>) method.invoke(storage, args);
                        } catch (ReflectiveOperationException failure) {
                            return CompletableFuture.failedFuture(failure);
                        }
                    }).thenCompose(written -> CompletableFuture.failedFuture(
                            new IllegalStateException("The database stopped answering.")));
                });
        return new Repository<>(liar, model);
    }

    /**
     * The same rows, but the next {@code failures} calls of one storage method
     * report failure; with {@code commit}, each one is carried out first.
     *
     * <p>A database that drops a handful of writes and then recovers, which is
     * what a retry has to survive without losing or doubling anything.
     *
     * @param method   the storage method that fails, such as {@code updateIf} or {@code insert}
     * @param failures how many more calls fail; counted down by each one
     */
    @SuppressWarnings("unchecked")
    public static <T> Repository<T> failing(Repository<T> real, String method, boolean commit,
                                            java.util.concurrent.atomic.AtomicInteger failures)
            throws ReflectiveOperationException {
        Storage storage = (Storage) read(real, "storage");
        EntityModel<T> model = (EntityModel<T>) read(real, "model");
        Storage liar = (Storage) Proxy.newProxyInstance(FlakyRepository.class.getClassLoader(),
                new Class<?>[]{Storage.class}, (self, method2, args) -> {
                    if (!method2.getName().equals(method) || failures.getAndDecrement() <= 0) {
                        return method2.invoke(storage, args);
                    }
                    if (commit) ((CompletableFuture<?>) method2.invoke(storage, args)).join();
                    return CompletableFuture.failedFuture(
                            new IllegalStateException("The database stopped answering."));
                });
        return new Repository<>(liar, model);
    }

    @SuppressWarnings("unchecked")
    private static <T> Repository<T> over(Repository<T> real, Set<String> failing, boolean commit)
            throws ReflectiveOperationException {
        Storage storage = (Storage) read(real, "storage");
        EntityModel<T> model = (EntityModel<T>) read(real, "model");
        Storage liar = (Storage) Proxy.newProxyInstance(FlakyRepository.class.getClassLoader(),
                new Class<?>[]{Storage.class}, (self, method, args) -> {
                    if (!failing.contains(method.getName())) {
                        return method.invoke(storage, args);
                    }
                    if (commit) {
                        ((CompletableFuture<?>) method.invoke(storage, args)).join();
                    }
                    return CompletableFuture.failedFuture(
                            new IllegalStateException("The database stopped answering."));
                });
        return new Repository<>(liar, model);
    }

    private static Object read(Repository<?> real, String name) throws ReflectiveOperationException {
        Field field = Repository.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(real);
    }
}
