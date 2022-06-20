package jenkins.plugins.openstack.compute.auth;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.common.PasswordCredentials;
import com.cloudbees.plugins.credentials.impl.BaseStandardCredentials;
import hudson.ExtensionPoint;

import javax.annotation.CheckForNull;

public abstract class AbstractOpenstackCredential extends BaseStandardCredentials implements OpenstackCredential, ExtensionPoint {
    private static final long serialVersionUID = -8990261308669288787L;

    public AbstractOpenstackCredential(@CheckForNull CredentialsScope scope, @CheckForNull String id, @CheckForNull String description) {
        super(scope, id, description);
        assert this instanceof PasswordCredentials;
    }
}
