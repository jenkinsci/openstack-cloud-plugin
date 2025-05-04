package jenkins.plugins.openstack.compute.auth;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import hudson.security.ACL;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import jenkins.model.Jenkins;

public class OpenstackCredentials {

    /**
     * @return null when the credentials are not found.
     */
    public static @CheckForNull OpenstackCredential getCredential(@CheckForNull String credentialsId) {
        if (credentialsId == null) return null;

        List<OpenstackCredential> credentials = CredentialsProvider.lookupCredentialsInItemGroup(
                OpenstackCredential.class, Jenkins.get(), ACL.SYSTEM2, Collections.emptyList());
        return CredentialsMatchers.firstOrNull(credentials, CredentialsMatchers.withId(credentialsId));
    }

    public static void add(@Nonnull OpenstackCredential openstackCredential) {
        SystemCredentialsProvider.getInstance().getCredentials().add(openstackCredential);
    }

    public static void save() throws IOException {
        SystemCredentialsProvider.getInstance().save();
    }
}
