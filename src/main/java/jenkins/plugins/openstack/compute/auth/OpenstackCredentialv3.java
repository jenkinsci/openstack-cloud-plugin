package jenkins.plugins.openstack.compute.auth;

import com.cloudbees.plugins.credentials.CredentialsNameProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.NameWith;
import com.cloudbees.plugins.credentials.common.PasswordCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import hudson.Extension;
import hudson.Util;
import hudson.util.Secret;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.openstack4j.api.client.IOSClientBuilder;
import org.openstack4j.model.common.Identifier;
import org.openstack4j.openstack.OSFactory;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

@NameWith(value = OpenstackCredentialv3.NameProvider.class)
public class OpenstackCredentialv3 extends AbstractOpenstackCredential implements StandardUsernamePasswordCredentials, PasswordCredentials {

    private static final long serialVersionUID = -1868447356467542586L;

    private final String username;
    private final String userDomain;
    private final String projectName;
    private final String projectDomain;
    private final Secret password;

    @DataBoundConstructor
    @SuppressWarnings("unused")
    public OpenstackCredentialv3(@CheckForNull CredentialsScope scope,
                                 @CheckForNull String id, @CheckForNull String description,
                                 @Nonnull String userName,
                                 @Nonnull String userDomain, @Nonnull String projectName,
                                 @Nonnull String projectDomain, @Nonnull String password) {
        this(scope,id,description,userName, userDomain, projectName, projectDomain, Secret.fromString(password));
    }

    @SuppressWarnings("unused")
    public OpenstackCredentialv3(@CheckForNull CredentialsScope scope,
                                 @CheckForNull String id, @CheckForNull String description,
                                 @Nonnull String userName, @Nonnull String userDomain, @Nonnull String projectName,
                                 @Nonnull String projectDomain, @Nonnull Secret password) {
        super(scope,id,description);
        this.username = userName;
        this.userDomain = userDomain;
        this.projectName = projectName;
        this.projectDomain = projectDomain;
        this.password = password;
    }

    @Nonnull
    @Override
    public Secret getPassword() {
        return password;
    }

    @Override
    public @Nonnull IOSClientBuilder.V3 getBuilder(String endPointUrl) {

        Identifier projectDomainIdentifier = Identifier.byName(projectDomain);
        Identifier projectNameIdentifier = Identifier.byName(projectName);
        Identifier userDomainIdentifier = Identifier.byName(userDomain);

        return OSFactory.builderV3().endpoint(endPointUrl)
                .credentials(username, getPassword().getPlainText(), userDomainIdentifier)
                .scopeToProject(projectNameIdentifier, projectDomainIdentifier);
    }

    @Override
    public String toString() {
        return "OpenstackCredentialv3{" +
                "username='" + username + '\'' +
                ", userDomain='" + userDomain + '\'' +
                ", projectName='" + projectName + '\'' +
                ", projectDomain='" + projectDomain + '\'' +
                '}';
    }

    public @Nonnull String getUsername() {
        return username;
    }

    public String getUserDomain() {
        return userDomain;
    }

    public String getProjectName() {
        return projectName;
    }

    public String getProjectDomain() {
        return projectDomain;
    }

    @SuppressWarnings("unused")
    public static class NameProvider extends CredentialsNameProvider<OpenstackCredentialv3> {

        /**
         * {@inheritDoc}
         */
        @Nonnull
        @Override
        public String getName(@Nonnull OpenstackCredentialv3 c) {
            String description = Util.fixEmptyAndTrim(c.getDescription());
            return c.getProjectDomain() + ":"
                    + c.getProjectName() + ":"
                    + c.getUserDomain() + ":"
                    + c.getUsername() + "/******"
                    + (description != null ? " (" + description + ")" : "");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Extension(ordinal = 1) @Symbol("openstackV3")
    public static class DescriptorImpl extends BaseStandardCredentialsDescriptor {

        /**
         * {@inheritDoc}
         */
        @Override
        public @Nonnull String getDisplayName() {
            return "OpenStack auth v3";
        }
    }
}
