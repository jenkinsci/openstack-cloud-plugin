package jenkins.plugins.openstack.compute.internal;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.junit.Test;

public class CachedDataTest {
    private static final Object VALUE = "Value1";
    private static final Object VALUENULL = null;

    @Test
    public void getWhenCacheMissThenResultCalculated() {
        final CacheableData<Object> mockFn = mock(CacheableData.class);
        final CachedData<Object> instance = mkInstance(Integer.MAX_VALUE, mockFn);
        when(mockFn.get()).thenReturn(VALUE);

        final Object actual = instance.get();

        assertThat(actual, sameInstance(VALUE));
        verify(mockFn, times(1)).get();
        verifyNoMoreInteractions(mockFn);
    }

    @Test
    public void getWhenCacheHitThenResultReturnedNotCalculated() {
        final CacheableData<Object> mockFn = mock(CacheableData.class);
        final CachedData<Object> instance = mkInstance(Integer.MAX_VALUE, mockFn);
        putValueCache(instance, VALUE);

        final Object actual = instance.get();

        assertThat(actual, sameInstance(VALUE));
        verifyNoMoreInteractions(mockFn);
    }

    @Test
    public void getWhenCacheMissAndCalculationReturnsNullThenReturnsNull() {
        final CacheableData<Object> mockFn = mock(CacheableData.class);
        final CachedData<Object> instance = mkInstance(Integer.MAX_VALUE, mockFn);
        when(mockFn.get()).thenReturn(VALUENULL);

        final Object actual = instance.get();

        assertThat(actual, sameInstance(VALUENULL));
        verify(mockFn, times(1)).get();
        verifyNoMoreInteractions(mockFn);
    }

    @Test
    public void getWhenCacheHitAndCalculationWasNullThenReturnsNull() {
        final CacheableData<Object> mockFn = mock(CacheableData.class);
        final CachedData<Object> instance = mkInstance(Integer.MAX_VALUE, mockFn);
        putValueCache(instance, VALUENULL);

        final Object actual = instance.get();

        assertThat(actual, sameInstance(VALUENULL));
        verifyNoMoreInteractions(mockFn);
    }

    @Test
    public void getWhenCacheMissAndCalculationThrowsRTEThenThrowsRTE() {
        final CacheableData<Object> mockFn = mock(CacheableData.class);
        final CachedData<Object> instance = mkInstance(Integer.MAX_VALUE, mockFn);
        final ExpectedRTE rte = new ExpectedRTE();
        when(mockFn.get()).thenThrow(rte);

        try {
            instance.get();
            fail();
        } catch (ExpectedRTE actual) {
            assertThat(actual, sameInstance(rte));
        }
        verify(mockFn, times(1)).get();
        verifyNoMoreInteractions(mockFn);
    }

    @Test
    public void getWhenCacheHitAndCalculationThrewRTEThenThrowsRTE() {
        final CacheableData<Object> mockFn = mock(CacheableData.class);
        final CachedData<Object> instance = mkInstance(Integer.MAX_VALUE, mockFn);
        final ExpectedRTE rte = new ExpectedRTE();
        putExceptionCache(instance, rte);

        try {
            instance.get();
            fail();
        } catch (ExpectedRTE actual) {
            assertThat(actual, sameInstance(rte));
        }
        verifyNoMoreInteractions(mockFn);
    }

    @Test
    public void getWithSameArgWhenCacheHasExpiredThenResultsRecalculated() throws Exception {
        final CacheableData<Object> mockFn = mock(CacheableData.class);
        final int cacheTimeoutSeconds = 1;
        final long cacheTimeoutMilliseconds = cacheTimeoutSeconds * 1000L;
        final CachedData<Object> instance = mkInstance(1, mockFn);
        when(mockFn.get()).thenReturn(VALUE);
        putValueCache(instance, VALUE);

        final Object actual1a = instance.get(); // from cache
        Thread.sleep(cacheTimeoutMilliseconds / 2L);
        final Object actual1b = instance.get(); // from cache
        Thread.sleep(cacheTimeoutMilliseconds);
        final Object actual1c = instance.get(); // recalculated

        assertThat(actual1a, sameInstance(VALUE));
        assertThat(actual1b, sameInstance(VALUE));
        assertThat(actual1c, sameInstance(VALUE));
        verify(mockFn, times(1)).get();
        verifyNoMoreInteractions(mockFn);
    }

    private static <V> CachedData<V> mkInstance(int cacheTimeout, final CacheableData<V> calculationDelegate) {
        return new CachedData<V>(cacheTimeout) {
            @Override
            protected V calculate() {
                return calculationDelegate.get();
            }
        };
    }

    private static <V> void putValueCache(CachedData<V> instance, V value) {
        final CachedFunction<String, V> cf = (CachedFunction<String, V>) instance.cache;
        final CachedFunction<String, V>.Result r = cf.new Result(value);
        cf.cache.put("", r);
    }

    private static <V> void putExceptionCache(CachedData<V> instance, RuntimeException ex) {
        final CachedFunction<String, V> cf = (CachedFunction<String, V>) instance.cache;
        final CachedFunction<String, V>.Result r = cf.new Result(ex);
        cf.cache.put("", r);
    }

    private static class ExpectedRTE extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
