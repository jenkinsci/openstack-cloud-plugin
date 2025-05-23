package jenkins.plugins.openstack.compute.auth;

import com.cloudbees.plugins.credentials.CredentialsNameProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.NameWith;
import com.cloudbees.plugins.credentials.common.PasswordCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import hudson.Extension;
import hudson.Util;
import hudson.util.Secret;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.openstack4j.api.client.IOSClientBuilder;
import org.openstack4j.openstack.OSFactory;

@NameWith(value = OpenstackCredentialv2.NameProvider.class)
public class OpenstackCredentialv2 extends AbstractOpenstackCredential
        implements StandardUsernamePasswordCredentials, PasswordCredentials {

    private static final long serialVersionUID = -2723193057734405814L;

    private final String tenant;
    private final String username;
    private final Secret password;

    @DataBoundConstructor
    @SuppressWarnings("unused")
    public OpenstackCredentialv2(
            @CheckForNull CredentialsScope scope,
            @CheckForNull String id,
            @CheckForNull String description,
            @CheckForNull String tenant,
            @CheckForNull String username,
            @CheckForNull String password) {
        this(scope, id, description, tenant, username, Secret.fromString(password));
    }

    @SuppressWarnings("unused")
    public OpenstackCredentialv2(
            @CheckForNull CredentialsScope scope,
            @CheckForNull String id,
            @CheckForNull String description,
            @CheckForNull String tenant,
            @CheckForNull String username,
            @CheckForNull Secret password) {
        super(scope, id, description);
        this.tenant = Util.fixEmptyAndTrim(tenant);
        this.username = Util.fixEmptyAndTrim(username);
        this.password = password;
    }

    @Nonnull
    @Override
    public Secret getPassword() {
        return password;
    }

    @Override
    public @Nonnull IOSClientBuilder.V2 getBuilder(String endPointUrl) {
        return OSFactory.builderV2()
                .endpoint(endPointUrl)
                .credentials(username, getPassword().getPlainText())
                .tenantName(tenant);
    }

    @Override
    public String toString() {
        return "OpenstackCredentialv2{" + "tenant='" + tenant + '\'' + ", username='" + username + '\'' + '}';
    }

    public String getTenant() {
        return tenant;
    }

    public @Nonnull String getUsername() {
        return username;
    }

    @SuppressWarnings("unused")
    public static class NameProvider extends CredentialsNameProvider<OpenstackCredentialv2> {

        /**
         * {@inheritDoc}
         */
        @Nonnull
        @Override
        public String getName(@Nonnull OpenstackCredentialv2 c) {
            String description = Util.fixEmptyAndTrim(c.getDescription());
            return c.getTenant() + ":" + c.getUsername() + "/******"
                    + (description != null ? " (" + description + ")" : "");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Extension(ordinal = 1)
    @Symbol("openstackV2")
    public static class DescriptorImpl extends BaseStandardCredentialsDescriptor {

        /**
         * {@inheritDoc}
         */
        @Override
        public @Nonnull String getDisplayName() {
            return "OpenStack auth v2";
        }
    }
}
