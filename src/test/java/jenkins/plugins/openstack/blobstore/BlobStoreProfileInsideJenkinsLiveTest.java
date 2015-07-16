package jenkins.plugins.openstack.blobstore;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

public class BlobStoreProfileInsideJenkinsLiveTest {

    public @Rule JenkinsRule j = new JenkinsRule();

    private BlobStoreTestFixture fixture;
    private BlobStoreProfile profile;

    @Before
    public void setUp() {
        fixture = new BlobStoreTestFixture();
        fixture.setUp();

        // TODO: this may need to vary per test
        profile = new BlobStoreProfile("profile", fixture.getIdentity(), fixture.getCredential());
    }

    public static final String CONTAINER_PREFIX = System.getProperty("user.name") + "-blobstore-profile";

    @Test
    public void testUpload() {
        String container = CONTAINER_PREFIX + "-upload";
        try {
            fixture.getBlobStore().createContainerInLocation(null, container);
            // filePath == ??
            // profile.upload(container, filePath)
            // assertTrue(fixture.getBlobStore().blobExists(container, filename));
        } finally {
            fixture.getBlobStore().deleteContainer(container);
        }
    }

    @After
    public void tearDown() {
        if (fixture != null)
            fixture.tearDown();
    }
}
