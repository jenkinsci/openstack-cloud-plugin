package jenkins.plugins.openstack.compute.internal;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

public class CachedFunctionTest {
    private static final Object ARG1 = "ARG1";
    private static final Object ARG2 = "ARG2";
    private static final Object ARG3 = "ARG3";
    private static final Object ARGNULL = null;
    private static final Object VALUE1 = "Value1";
    private static final Object VALUE2 = "Value2";
    private static final Object VALUE3 = "Value3";
    private static final Object VALUENULL = null;

    @Test
    public void getWhenCacheMissThenResultCalculated() {
        final CacheableFunction<Object, Object> mockFn = mock(CacheableFunction.class);
        final CachedFunction<Object, Object> instance = mkInstance(Integer.MAX_VALUE, mockFn);
        when(mockFn.get(ARG1)).thenReturn(VALUE1);

        final Object actual = instance.get(ARG1);

        assertThat(actual, sameInstance(VALUE1));
        verify(mockFn, times(1)).get(ARG1);
        verifyNoMoreInteractions(mockFn);
    }

    @Test
    public void getWhenCacheHitThenResultReturnedNotCalculated() {
        final CacheableFunction<Object, Object> mockFn = mock(CacheableFunction.class);
        final CachedFunction<Object, Object> instance = mkInstance(Integer.MAX_VALUE, mockFn);
        putValueCache(instance, ARG1, VALUE1);

        final Object actual = instance.get(ARG1);

        assertThat(actual, sameInstance(VALUE1));
        verifyNoMoreInteractions(mockFn);
    }

    @Test
    public void getWithNullArgWhenCacheMissThenResultCalculatedForNullArg() {
        final CacheableFunction<Object, Object> mockFn = mock(CacheableFunction.class);
        final CachedFunction<Object, Object> instance = mkInstance(Integer.MAX_VALUE, mockFn);
        when(mockFn.get(ARGNULL)).thenReturn(VALUE3);

        final Object actual = instance.get(ARGNULL);

        assertThat(actual, sameInstance(VALUE3));
        verify(mockFn, times(1)).get(ARGNULL);
        verifyNoMoreInteractions(mockFn);
    }

    @Test
    public void getWithNullArgWhenCacheHitThenResultReturnedNotCalculated() {
        final CacheableFunction<Object, Object> mockFn = mock(CacheableFunction.class);
        final CachedFunction<Object, Object> instance = mkInstance(Integer.MAX_VALUE, mockFn);
        putValueCache(instance, ARGNULL, VALUE3);

        final Object actual = instance.get(ARGNULL);

        assertThat(actual, sameInstance(VALUE3));
        verifyNoMoreInteractions(mockFn);
    }

    @Test
    public void getWhenCacheMissAndCalculationReturnsNullThenReturnsNull() {
        final CacheableFunction<Object, Object> mockFn = mock(CacheableFunction.class);
        final CachedFunction<Object, Object> instance = mkInstance(Integer.MAX_VALUE, mockFn);
        when(mockFn.get(ARG3)).thenReturn(VALUENULL);

        final Object actual = instance.get(ARG3);

        assertThat(actual, sameInstance(VALUENULL));
        verify(mockFn, times(1)).get(ARG3);
        verifyNoMoreInteractions(mockFn);
    }

    @Test
    public void getWhenCacheHitAndCalculationWasNullThenReturnsNull() {
        final CacheableFunction<Object, Object> mockFn = mock(CacheableFunction.class);
        final CachedFunction<Object, Object> instance = mkInstance(Integer.MAX_VALUE, mockFn);
        putValueCache(instance, ARG3, VALUENULL);

        final Object actual = instance.get(ARG3);

        assertThat(actual, sameInstance(VALUENULL));
        verifyNoMoreInteractions(mockFn);
    }

    @Test
    public void getWhenCacheMissAndCalculationThrowsRTEThenThrowsRTEShowingStacktraceToException() {
        final AtomicReference<ExpectedRTE> refToEx = new AtomicReference<>();
        final AtomicReference<StackTraceElement[]> refToExOrigSTE = new AtomicReference<>();
        final CachedFunction<Object, Object> instance = new CachedFunction<Object, Object>(Integer.MAX_VALUE) {
            @Override
            protected Object calculate(Object key) {
                final ExpectedRTE ex = new ExpectedRTE();
                refToEx.set(ex);
                refToExOrigSTE.set(ex.getStackTrace());
                throw ex;
            }
        };

        final ExpectedRTE actual;
        try {
            instance.get(ARG1);
            fail();
            return; // prevent compiler warning
        } catch (ExpectedRTE ex) {
            actual = ex;
        }
        assertThat(actual, sameInstance(refToEx.get()));
        final StackTraceElement[] origSTE = refToExOrigSTE.get();
        final List<StackTraceElement> expectedDeepest = getDeepestSTEsThatAreOurCode(origSTE);
        final List<StackTraceElement> expectedHighest = getHighestSTEsUpToAndIncludingOurTestCode(origSTE);
        final String actualSTString = toMLString(actual.getStackTrace());
        final String expectedStartString = toMLString(expectedDeepest);
        final String expectedEndString = toMLString(expectedHighest);
        assertThat(actualSTString, startsWith(expectedStartString));
        assertThat(actualSTString, endsWith(expectedEndString));
    }

    @Test
    public void getWhenCacheHitAndCalculationThrewRTEThenThrowsRTEShowingStacktraceFromCacheHitToException()
            throws InterruptedException {
        final AtomicReference<ExpectedRTE> ref = new AtomicReference<>();
        final CacheableFunction<Object, Object> mockFn = mock(CacheableFunction.class);
        final CachedFunction<Object, Object> instance = new CachedFunction<Object, Object>(Integer.MAX_VALUE) {
            @Override
            protected Object calculate(Object key) {
                if (ref.get() == null) {
                    final ExpectedRTE ex = new ExpectedRTE();
                    ref.set(ex);
                    throw ex;
                }
                return mockFn.get(key);
            }
        };
        final Thread createRteInAnotherContext = new Thread() {
            @Override
            public void run() {
                try {
                    instance.get(ARG1);
                } catch (ExpectedRTE ignored) {
                }
            }
        };
        createRteInAnotherContext.start();
        createRteInAnotherContext.join(1000L);
        final ExpectedRTE rte = ref.get();
        final StackTraceElement[] origSTE = rte.getStackTrace();

        final ExpectedRTE actual;
        try {
            instance.get(ARG1);
            fail();
            return; // prevent compiler warning
        } catch (ExpectedRTE ex) {
            actual = ex;
        }
        assertThat(actual, sameInstance(rte));
        final List<StackTraceElement> expectedDeepest = getDeepestSTEsThatAreOurCode(origSTE);
        final List<StackTraceElement> unwantedHighest = Collections.singletonList(origSTE[origSTE.length - 1]);
        final String actualSTString = toMLString(actual.getStackTrace());
        final String expectedStartString = toMLString(expectedDeepest);
        final String unwantedEndString = toMLString(unwantedHighest);
        assertThat(actualSTString, startsWith(expectedStartString));
        assertThat(actualSTString, not(endsWith(unwantedEndString)));
        verifyNoMoreInteractions(mockFn);
    }

    @Test
    public void getWithVariousArgsThenCachesResults() {
        final CacheableFunction<Object, Object> mockFn = mock(CacheableFunction.class);
        final CachedFunction<Object, Object> instance = mkInstance(Integer.MAX_VALUE, mockFn);
        final ExpectedRTE rte = new ExpectedRTE();
        when(mockFn.get(ARG1)).thenReturn(VALUE1);
        when(mockFn.get(ARG2)).thenReturn(VALUE2);
        when(mockFn.get(ARG3)).thenThrow(rte);
        when(mockFn.get(ARGNULL)).thenReturn(VALUENULL);

        final Object actual1a = instance.get(ARG1);
        final Object actual2a = instance.get(ARG2);
        final ExpectedRTE actual3a;
        try {
            instance.get(ARG3);
            fail();
            return;
        } catch (ExpectedRTE actual) {
            actual3a = actual;
        }
        final Object actual4a = instance.get(ARGNULL);
        final Object actual1b = instance.get(ARG1);
        final Object actual2b = instance.get(ARG2);
        final ExpectedRTE actual3b;
        try {
            instance.get(ARG3);
            fail();
            return;
        } catch (ExpectedRTE actual) {
            actual3b = actual;
        }
        final Object actual4b = instance.get(ARGNULL);

        assertThat(actual1a, sameInstance(VALUE1));
        assertThat(actual2a, sameInstance(VALUE2));
        assertThat(actual3a, sameInstance(rte));
        assertThat(actual4a, sameInstance(VALUENULL));
        assertThat(actual1b, sameInstance(VALUE1));
        assertThat(actual2b, sameInstance(VALUE2));
        assertThat(actual3b, sameInstance(rte));
        assertThat(actual4b, sameInstance(VALUENULL));
        verify(mockFn, times(1)).get(ARG1);
        verify(mockFn, times(1)).get(ARG2);
        verify(mockFn, times(1)).get(ARG3);
        verify(mockFn, times(1)).get(ARGNULL);
        verifyNoMoreInteractions(mockFn);
    }

    @Test
    public void getWithSameArgWhenCacheHasExpiredThenResultsRecalculated() throws Exception {
        final CacheableFunction<Object, Object> mockFn = mock(CacheableFunction.class);
        final int cacheTimeoutSeconds = 1;
        final long cacheTimeoutMilliseconds = cacheTimeoutSeconds * 1000L;
        final CachedFunction<Object, Object> instance = mkInstance(1, mockFn);
        when(mockFn.get(ARG1)).thenReturn(VALUE1);
        when(mockFn.get(ARG2)).thenReturn(VALUE2);
        putValueCache(instance, ARG1, VALUE1); // data should remain for period
                                               // from here

        final Object actual1a = instance.get(ARG1); // data from cache
        final Object actual2a = instance.get(ARG2); // data calculated and
                                                    // cached
        Thread.sleep(cacheTimeoutMilliseconds / 2L);
        final Object actual1b = instance.get(ARG1); // data from cache
        final Object actual2b = instance.get(ARG2); // data from cache
        Thread.sleep(cacheTimeoutMilliseconds);
        final Object actual1c = instance.get(ARG1); // data was cached,
                                                    // recalculated
        final Object actual2c = instance.get(ARG2); // data was cached,
                                                    // recalculated

        assertThat(actual1a, sameInstance(VALUE1));
        assertThat(actual2a, sameInstance(VALUE2));
        assertThat(actual1b, sameInstance(VALUE1));
        assertThat(actual2b, sameInstance(VALUE2));
        assertThat(actual1c, sameInstance(VALUE1));
        assertThat(actual2c, sameInstance(VALUE2));
        verify(mockFn, times(1)).get(ARG1);
        verify(mockFn, times(2)).get(ARG2);
        verifyNoMoreInteractions(mockFn);
    }

    private static String toMLString(Iterable<?> l) {
        final StringBuilder s = new StringBuilder();
        for (Object o : l) {
            s.append(o).append('\n');
        }
        return s.toString();
    }

    private static <T> String toMLString(T[] l) {
        final List<T> list = Arrays.asList(l);
        return toMLString(list);
    }

    private static List<StackTraceElement> getDeepestSTEsThatAreOurCode(StackTraceElement[] st) {
        final List<StackTraceElement> res = new ArrayList<>();
        for (final StackTraceElement ste : st) {
            if (!steIsInOurCode(ste))
                break;
            res.add(ste);
        }
        return res;
    }

    private static List<StackTraceElement> getHighestSTEsUpToAndIncludingOurTestCode(StackTraceElement[] st) {
        final List<StackTraceElement> res = new ArrayList<>();
        boolean foundOurStuff = false;
        for (int i = st.length - 1; i >= 0; i--) {
            final StackTraceElement ste = st[i];
            if (steIsInOurTestCode(ste)) {
                foundOurStuff = true;
            } else {
                if (foundOurStuff)
                    break; // have found the end of our stuff
            }
            res.add(0, ste);
        }
        return res;
    }

    private static boolean steIsInOurCode(final StackTraceElement ste) {
        return ste.getClassName().startsWith(CachedFunctionTest.class.getPackage().getName());
    }

    private static boolean steIsInOurTestCode(final StackTraceElement ste) {
        return ste.getClassName().startsWith(CachedFunctionTest.class.getName());
    }

    private static <K, V> CachedFunction<K, V> mkInstance(int cacheTimeout,
            final CacheableFunction<K, V> calculationDelegate) {
        return new CachedFunction<K, V>(cacheTimeout) {
            @Override
            protected V calculate(K key) {
                return calculationDelegate.get(key);
            }
        };
    }

    private static <K, V> void putValueCache(CachedFunction<K, V> instance, K key, V value) {
        final CachedFunction<K, V>.Result r = instance.new Result(value);
        instance.cache.put(key == null ? CachedFunction.NULL : key, r);
    }

    private static class ExpectedRTE extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
