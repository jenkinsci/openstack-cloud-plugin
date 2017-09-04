package jenkins.plugins.openstack.compute.auth;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.impl.BaseStandardCredentials;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.ExtensionPoint;


public abstract class AbstractOpenstackCredential extends BaseStandardCredentials implements OpenstackCredential,ExtensionPoint {


    public AbstractOpenstackCredential(@CheckForNull CredentialsScope scope,
                               @CheckForNull String id, @CheckForNull String description) {
        super(scope, id, description);
    }
}
