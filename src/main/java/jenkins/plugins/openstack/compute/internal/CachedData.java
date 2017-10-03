package jenkins.plugins.openstack.compute.internal;

import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Implementation of {@link CacheableData} that requires {@link #calculate()} to
 * be implemented.
 *
 * @param <V>
 *            See {@link CacheableData}
 */
public abstract class CachedData<V> implements CacheableData<V> {
    @Restricted(NoExternalUse.class) // non-private just for testing
    final CacheableFunction<String, V> cache;

    protected CachedData(final int secondsToCacheData) {
        cache = new CachedFunction<String, V>(secondsToCacheData) {
            @Override
            protected V calculate(String ignored) {
                return CachedData.this.calculate();
            }
        };
    }

    /** {@inheritDoc} */
    @Override
    public V get() {
        return cache.get("");
    }

    /**
     * The function whose results should be cached.
     * 
     * @return The result to be cached.
     * @throws RuntimeException
     *             If thrown, this will be cached too.
     */
    protected abstract V calculate();
}