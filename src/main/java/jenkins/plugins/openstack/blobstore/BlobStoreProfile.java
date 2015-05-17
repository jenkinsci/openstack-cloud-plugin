package jenkins.plugins.openstack.blobstore;

import java.io.IOException;
import java.util.Properties;
import java.util.logging.Logger;

import org.jclouds.openstack.nova.v2_0.NovaApiMetadata;
import com.google.common.collect.ImmutableSet;
import com.google.inject.Module;
import hudson.FilePath;
import org.jclouds.ContextBuilder;
import org.jclouds.apis.Apis;
import org.jclouds.blobstore.BlobStore;
import org.jclouds.blobstore.BlobStoreContext;
import org.jclouds.blobstore.domain.Blob;
import org.jclouds.enterprise.config.EnterpriseConfigurationModule;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Model class for Blobstore profile. User can configure multiple profiles to upload artifacts to different providers.
 *
 * @author Vijay Kiran
 */
public class BlobStoreProfile {

    private static final Logger LOGGER = Logger.getLogger(BlobStoreProfile.class.getName());

    private String profileName;
    private String identity;
    private String credential;

    @DataBoundConstructor
    public BlobStoreProfile(final String profileName, final String identity, final String credential) {
        this.profileName = profileName;
        this.identity = identity;
        this.credential = credential;
    }

    /**
     * Configured profile.
     *
     * @return - name of the profile.
     */
    public String getProfileName() {
        return profileName;
    }

    /**
     * Cloud provider identity.
     *
     * @return
     */
    public String getIdentity() {
        return identity;
    }

    /**
     * Cloud provider credential.
     *
     * @return
     */
    public String getCredential() {
        return credential;
    }

    static final Iterable<Module> MODULES = ImmutableSet.<Module>of(new EnterpriseConfigurationModule());

    static BlobStoreContext ctx(String identity, String credential, Properties overrides) {
        return ContextBuilder.newBuilder(new NovaApiMetadata()).credentials(identity, credential).overrides(overrides).modules(MODULES)
                .buildView(BlobStoreContext.class);
    }

    /**
     * Upload the specified file from the
     *
     * @param filePath  to container
     * @param container - The container where the file needs to be uploaded.
     * @param path      - The path in container where the file needs to be uploaded.
     * @param filePath  - the {@link FilePath} of the file which needs to be uploaded.
     * @throws IOException
     * @throws InterruptedException
     */
    public void upload(String container, String path, FilePath filePath) throws IOException, InterruptedException {
        if (filePath.isDirectory()) {
            throw new IOException(filePath + " is a directory");
        }
        // correct the classloader so that extensions can be found
        Thread.currentThread().setContextClassLoader(Apis.class.getClassLoader());
        // TODO: endpoint
        final BlobStoreContext context = ctx(this.identity, this.credential, new Properties());
        try {
            final BlobStore blobStore = context.getBlobStore();
            if (!blobStore.containerExists(container)) {
                blobStore.createContainerInLocation(null, container);
            }
            if (!path.equals("") && !blobStore.directoryExists(container, path)) {
                blobStore.createDirectory(container, path);
            }
            String destPath;
            if (path.equals("")) {
                destPath = filePath.getName();
            } else {
                destPath = path + "/" + filePath.getName();
            }
            LOGGER.info("Publishing now to container: " + container + " path: " + destPath);
            Blob blob = context.getBlobStore().blobBuilder(destPath).payload(filePath.read()).build();
            context.getBlobStore().putBlob(container, blob);
            LOGGER.info("Published " + destPath + " to container " + container + " with profile " + this.profileName);
        } finally {
            context.close();
        }
    }
}
