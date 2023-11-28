package stroom.util.exception;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;


/**
 * Credit for this class goes to
 * <a href="https://stackoverflow.com/a/27644392">MC Emperor on Stack Overflow</a>
 */
public class LambdaExceptionUtil {

    @FunctionalInterface
    public interface ConsumerWithExceptions<T, E extends Exception> {

        void accept(T t) throws E;
    }

    @FunctionalInterface
    public interface BiConsumerWithExceptions<T, U, E extends Exception> {

        void accept(T t, U u) throws E;
    }

    @FunctionalInterface
    public interface FunctionWithExceptions<T, R, E extends Exception> {

        R apply(T t) throws E;
    }

    @FunctionalInterface
    public interface SupplierWithExceptions<T, E extends Exception> {

        T get() throws E;
    }

    @FunctionalInterface
    public interface RunnableWithExceptions<E extends Exception> {

        void run() throws E;
    }

    /**
     * e.g:
     * .forEach(rethrowConsumer(name -> System.out.println(Class.forName(name))));
     * or
     * .forEach(rethrowConsumer(ClassNameUtil::println));
     */
    public static <E extends Exception> Runnable rethrowRunnable(RunnableWithExceptions<E> runnable)
            throws E {
        return () -> {
            try {
                runnable.run();
            } catch (Exception exception) {
                throwAsUnchecked(exception);
            }
        };
    }

    /**
     * e.g:
     * .forEach(rethrowConsumer(name -> System.out.println(Class.forName(name))));
     * or
     * .forEach(rethrowConsumer(ClassNameUtil::println));
     */
    public static <T, E extends Exception> Consumer<T> rethrowConsumer(ConsumerWithExceptions<T, E> consumer)
            throws E {
        return t -> {
            try {
                consumer.accept(t);
            } catch (Exception exception) {
                throwAsUnchecked(exception);
            }
        };
    }

    public static <T, U, E extends Exception> BiConsumer<T, U> rethrowBiConsumer(
            BiConsumerWithExceptions<T, U, E> biConsumer)
            throws E {
        return (t, u) -> {
            try {
                biConsumer.accept(t, u);
            } catch (Exception exception) {
                throwAsUnchecked(exception);
            }
        };
    }

    /**
     * e.g:
     * .map(rethrowFunction(name -> Class.forName(name)))
     * or
     * .map(rethrowFunction(Class::forName))
     */
    public static <T, R, E extends Exception> Function<T, R> rethrowFunction(
            FunctionWithExceptions<T, R, E> function)
            throws E {
        return t -> {
            try {
                return function.apply(t);
            } catch (Exception exception) {
                throwAsUnchecked(exception);
                return null;
            }
        };
    }

    /**
     * rethrowSupplier(() -> new StringJoiner(new String(new byte[]{77, 97, 114, 107}, "UTF-8"))),
     */
    public static <T, E extends Exception> Supplier<T> rethrowSupplier(
            SupplierWithExceptions<T, E> function)
            throws E {
        return () -> {
            try {
                return function.get();
            } catch (Exception exception) {
                throwAsUnchecked(exception);
                return null;
            }
        };
    }

    /**
     * uncheck(() -> Class.forName("xxx"));
     */
    public static void uncheck(RunnableWithExceptions t) {
        try {
            t.run();
        } catch (Exception exception) {
            throwAsUnchecked(exception);
        }
    }

    /**
     * uncheck(() -> Class.forName("xxx"));
     */
    public static <R, E extends Exception> R uncheck(SupplierWithExceptions<R, E> supplier) {
        try {
            return supplier.get();
        } catch (Exception exception) {
            throwAsUnchecked(exception);
            return null;
        }
    }

    /**
     * uncheck(Class::forName, "xxx");
     */
    public static <T, R, E extends Exception> R uncheck(FunctionWithExceptions<T, R, E> function, T t) {
        try {
            return function.apply(t);
        } catch (Exception exception) {
            throwAsUnchecked(exception);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void throwAsUnchecked(Exception exception) throws E {
        throw (E) exception;
    }

}
