package jenkins.plugins.openstack.compute.auth;

import com.cloudbees.plugins.credentials.CredentialsNameProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.NameWith;
import com.cloudbees.plugins.credentials.common.PasswordCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.util.Secret;
import org.kohsuke.stapler.DataBoundConstructor;
import org.openstack4j.api.OSClient;
import org.openstack4j.api.client.IOSClientBuilder;
import org.openstack4j.openstack.OSFactory;

@NameWith(value = OpenstackCredentialv2.NameProvider.class)
public class OpenstackCredentialv2 extends AbstractOpenstackCredential implements
        StandardUsernamePasswordCredentials,PasswordCredentials {

    /**
     * {@inheritDoc}
     */
    @Extension(ordinal = 1)
    public static class DescriptorImpl extends BaseStandardCredentialsDescriptor {

        /**
         * {@inheritDoc}
         */
        @Override
        public String getDisplayName() {
            return "Openstack auth v2";
        }

    }

    private final String tenant;
    private final String username;
    private final Secret password;


    @DataBoundConstructor
    @SuppressWarnings("unused")
    public OpenstackCredentialv2(@CheckForNull CredentialsScope scope,
                                 @CheckForNull String id, @CheckForNull String description,
                                 @CheckForNull  String tenant, @CheckForNull  String username,
                                 @CheckForNull  String password) {
        this(scope,id,description,tenant, username,Secret.fromString(password));
    }

    @SuppressWarnings("unused")
    public OpenstackCredentialv2(@CheckForNull CredentialsScope scope,
                                 @CheckForNull String id, @CheckForNull String description,
                                 @CheckForNull  String tenant, @CheckForNull  String username,
                                 @CheckForNull  Secret password) {
        super(scope,id,description);
        this.tenant =  Util.fixEmptyAndTrim(tenant);
        this.username =  Util.fixEmptyAndTrim(username);
        this.password = password;
    }

    @NonNull
    @Override
    public Secret getPassword() {
        return password;
    }

    @Override
    public IOSClientBuilder<? extends OSClient, ?> getBuilder(String endPointUrl) {
        return OSFactory.builderV2().endpoint(endPointUrl)
                .credentials(username, getPassword().getPlainText())
                .tenantName(tenant);
    }

    @Override
    public String toString() {
        return "OpenstackCredentialv2{" +
                "tenant='" + tenant + '\'' +
                ", username='" + username + '\'' +
                '}';
    }

    public String getTenant() {
        return tenant;
    }

    public String getUsername() {
        return username;
    }

    @SuppressWarnings("unused")
    public static class NameProvider extends CredentialsNameProvider<OpenstackCredentialv2> {

        /**
         * {@inheritDoc}
         */
        @NonNull
        @Override
        public String getName(@NonNull OpenstackCredentialv2 c) {
            String description = Util.fixEmptyAndTrim(c.getDescription());
            return c.getTenant() + ":" + c.getUsername() + "/******" + (description != null ? " (" + description + ")" : "");
        }
    }
}
