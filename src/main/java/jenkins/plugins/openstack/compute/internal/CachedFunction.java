package jenkins.plugins.openstack.compute.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

/**
 * Implementation of {@link CacheableFunction} that requires
 * {@link #calculate(Object)} to be implemented.
 *
 * @param <K>
 *            See {@link CacheableFunction}
 * @param <V>
 *            See {@link CacheableFunction}
 */
public abstract class CachedFunction<K, V> implements CacheableFunction<K, V> {

    @Restricted(NoExternalUse.class) // non-private just for testing
    /**
     * The index (in the cache) we use if the function's actual argument is
     * null.
     */
    static final Object NULL = new Object();

    @Restricted(NoExternalUse.class) // non-private just for testing
    /** Holds the result of calling the function. */
    class Result {
        /** If non-null then the function threw this exception. */
        @CheckForNull
        final RuntimeException ex;
        /**
         * If <code>ex</code> is not null then this is the original stacktrace
         * from it.
         */
        final StackTraceElement[] exOriginalStacktrace;
        /**
         * If <code>ex</code> is null then this is the result returned by the
         * function
         */
        final V value;

        Result(V value) {
            this.value = value;
            this.ex = null;
            this.exOriginalStacktrace = null;
        }

        Result(RuntimeException ex) {
            this.ex = ex;
            this.exOriginalStacktrace = ex.getStackTrace();
            this.value = null;
        }
    }

    @Restricted(NoExternalUse.class) // non-private just for testing
    /** Indexed by method argument. */
    final @Nonnull Cache<Object, Result> cache;

    private static final String OURCLASSNAME = CachedFunction.class.getName();

    /**
     * Creates an empty cache, setting the data expiry lifetime.
     * 
     * @param secondsToCacheData
     *            The number of seconds that the data remains valid after being
     *            calculated.
     */
    protected CachedFunction(final int secondsToCacheData) {
        cache = CacheBuilder.newBuilder().expireAfterWrite(secondsToCacheData, TimeUnit.SECONDS).build();
    }

    /**
     * The function whose results should be cached.
     * 
     * @param key
     *            The argument to be given to the function.
     * @return The result to be cached.
     * @throws RuntimeException
     *             If thrown, this will be cached too.
     */
    protected abstract V calculate(K key);

    /** {@inheritDoc} */
    @Override
    public V get(final K key) {
        final Callable<Result> callOnCacheMiss = new Callable<Result>() {
            @Override
            public Result call() {
                try {
                    return new Result(calculate(key));
                } catch (RuntimeException e) {
                    return new Result(e);
                }
            }
        };
        final Object cacheKey = key == null ? NULL : key;
        final Result result;
        try {
            result = cache.get(cacheKey, callOnCacheMiss);
        } catch (ExecutionException e) {
            // Should not happen as our callOnCacheMiss method does not throw
            // a declared exceptions and any RuntimeExceptions thrown by the
            // calculate method will have been caught and stored in the result.
            throw new RuntimeException("Internal error", e);
        }
        // If we got an exception, pass that back...
        if (result.ex != null) {
            // NOTE: If you are debugging an exception stacktrace and you are
            // pointed to the following line, please be aware that this is where
            // get() was called from this time, but all the deeper stacktrace
            // elements came from the original exception from when the value was
            // first calculated (and cached), which might not be from this time
            // if "this time" was a cache-hit.
            final List<StackTraceElement> howWeGotHereThisTime = createStacktracePointingHere();
            final StackTraceElement[] st = combineHowWeGotHereWithTheOriginalStacktrace(howWeGotHereThisTime,
                    result.exOriginalStacktrace);
            result.ex.setStackTrace(st);
            throw result.ex;
        }
        // ...otherwise return the result.
        return result.value;
    }

    /**
     * When we are returning the "same exception" that we cached previously, we
     * need to re-throw the exact same exception (same instance) that was thrown
     * when we calculated the results (in order to ensure that cache-hit
     * behavior is the same as cache-miss behavior) BUT the stacktrace from the
     * cached result will be very misleading if left as-is (it'll show us being
     * called from whatever code experienced the cache-miss), so we take the
     * exception's original stacktrace (up to where we got involved) and then
     * glue our current stacktrace (from where we got involved onwards) on to
     * the end of that.
     * 
     * @param howWeGotHereThisTime
     *            Our current stacktrace up to where we got involved.
     * @param originalExceptionStacktrace
     *            The original exception stacktrace.
     * @return A stacktrace showing the cache-miss code path plus how we got to
     *         this point.
     */
    private static StackTraceElement[] combineHowWeGotHereWithTheOriginalStacktrace(
            final List<StackTraceElement> howWeGotHereThisTime, final StackTraceElement[] originalExceptionStacktrace) {
        final List<StackTraceElement> combined = new ArrayList<>(
                howWeGotHereThisTime.size() + originalExceptionStacktrace.length);
        for (final StackTraceElement orig : originalExceptionStacktrace) {
            combined.add(orig);
            if (orig.getClassName().equals(OURCLASSNAME)) {
                break;
            }
        }
        combined.addAll(howWeGotHereThisTime);
        final StackTraceElement[] newTrace = combined.toArray(new StackTraceElement[combined.size()]);
        return newTrace;
    }

    /**
     * @return a stacktrace from the top of the {@link Thread#currentThread()}
     *         down to the code which call this method, excluding the call to
     *         this method.
     */
    private static List<StackTraceElement> createStacktracePointingHere() {
        // get stacktrace
        final ArrayList<StackTraceElement> l = new ArrayList<>(Arrays.asList(Thread.currentThread().getStackTrace()));
        // now strip off until we find this class, which should leave us
        // pointing at this method
        while (!l.isEmpty() && !l.get(0).getClassName().equals(OURCLASSNAME))
            l.remove(0);
        // and remove this method too
        if (!l.isEmpty())
            l.remove(0);
        return l;
    }
}