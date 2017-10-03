package jenkins.plugins.openstack.compute.internal;

/**
 * A stateless, deterministic, function that takes no arguments whose result can
 * be cached.
 * <p>
 * When communicating with an OpenStack endpoint, we often request the same
 * information, e.g. "Tell me what boot images are defined", and the answers
 * don't change often. It makes some sense to cache these answers (for a short
 * period of time) so that we can avoid making the same API calls over and over
 * again in parallel.
 * </p>
 * This can have a noticeable effect on overall UI responsiveness, e.g. the
 * cloud configuration page when there are a lot of templates defined.
 *
 * @param <T>
 *            The type of data the function returns.
 */
public interface CacheableData<T> {
    /**
     * Gets the result of calling this function. The result may have come from
     * the cache, or may have been calculated in-line.
     * <p>
     * <b>NOTE:</b> If the calculation throws a {@link RuntimeException}, this
     * will be cached too.
     * </p>
     * 
     * @return The answer.
     */
    public T get();
}