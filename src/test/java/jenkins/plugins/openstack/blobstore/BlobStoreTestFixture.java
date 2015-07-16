package jenkins.plugins.openstack.blobstore;

import java.util.Map;

import org.jclouds.apis.BaseViewLiveTest;
import org.jclouds.blobstore.BlobStore;
import org.jclouds.blobstore.BlobStoreContext;
import org.jclouds.util.Maps2;
import org.junit.Assume;

import com.google.common.base.Function;
import com.google.common.base.Predicates;
import com.google.common.collect.Maps;
import com.google.common.reflect.TypeToken;

@SuppressWarnings("unchecked")
public class BlobStoreTestFixture extends BaseViewLiveTest<BlobStoreContext> {

    /**
     * base openstack tests expect properties to arrive in a different naming convention, based on provider name.
     *
     * ex.
     *
     * <pre>
     *  test.jenkins.blobstore.provider=aws-s3
     *  test.jenkins.blobstore.identity=access
     *  test.jenkins.blobstore.credential=secret
     * </pre>
     *
     * should turn into
     *
     * <pre>
     *  test.aws-s3.identity=access
     *  test.aws-s3.credential=secret
     * </pre>
     */
    public BlobStoreTestFixture() {
        final String PROVIDER = System.getProperty("test.jenkins.blobstore.provider");
        Assume.assumeTrue("test.blobstore.provider variable must be set!", PROVIDER != null); // Skip the test
        Map<String, String> filtered = Maps.filterKeys(Map.class.cast(System.getProperties()), Predicates.containsPattern("^test\\.jenkins\\.blobstore"));
        Map<String, String> transformed = Maps2.transformKeys(filtered, new Function<String, String>() {

            @Override
            public String apply(String arg0) {
                return arg0.replaceAll("test.jenkins.blobstore", "test." + PROVIDER);
            }

        });
        System.getProperties().putAll(transformed);
        provider = PROVIDER;
    }

    public BlobStore getBlobStore() {
        return view.getBlobStore();
    }

    public String getProvider() {
        return provider;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getIdentity() {
        return identity;
    }

    public String getCredential() {
        return credential;
    }

    public void setUp() {
        super.setupContext();
    }

    public void tearDown() {
        super.tearDownContext();
    }

    @Override
    protected TypeToken<BlobStoreContext> viewType() {
        return TypeToken.of(BlobStoreContext.class);
    }

}
