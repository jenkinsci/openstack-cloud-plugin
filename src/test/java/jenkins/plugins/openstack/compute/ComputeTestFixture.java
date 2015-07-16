package jenkins.plugins.openstack.compute;

import java.util.Map;

import org.jclouds.compute.ComputeService;
import org.jclouds.compute.internal.BaseComputeServiceContextLiveTest;
import org.jclouds.sshj.config.SshjSshClientModule;
import org.jclouds.util.Maps2;
import org.junit.Assume;

import com.google.common.base.Function;
import com.google.common.base.Predicates;
import com.google.common.collect.Maps;
import com.google.inject.Module;

@SuppressWarnings("unchecked")
public class ComputeTestFixture extends BaseComputeServiceContextLiveTest {
    public static String PROVIDER;

    /**
     * base openstack tests expect properties to arrive in a different naming convention, based on provider name.
     *
     * ex.
     *
     * <pre>
     *  test.jenkins.compute.provider=aws-ec2
     *  test.jenkins.compute.identity=access
     *  test.jenkins.compute.credential=secret
     * </pre>
     *
     * should turn into
     *
     * <pre>
     *  test.aws-ec2.identity=access
     *  test.aws-ec2.credential=secret
     * </pre>
     */
    public ComputeTestFixture() {
        final String PROVIDER = System.getProperty("test.jenkins.compute.provider");
        Assume.assumeTrue("test.jenkins.compute.provider variable must be set!", PROVIDER != null); // Skip the test
        Map<String, String> filtered = Maps.filterKeys(Map.class.cast(System.getProperties()), Predicates.containsPattern("^test\\.jenkins\\.compute"));
        Map<String, String> transformed = Maps2.transformKeys(filtered, new Function<String, String>() {

            @Override
            public String apply(String arg0) {
                return arg0.replaceAll("test.jenkins.compute", "test." + PROVIDER);
            }

        });
        System.getProperties().putAll(transformed);
        provider = PROVIDER;
    }

    @Override
    protected Module getSshModule() {
        return new SshjSshClientModule();
    }

    public ComputeService getComputeService() {
        return view.getComputeService();
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

}
