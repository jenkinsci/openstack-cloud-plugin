package jenkins.plugins.openstack.compute.auth;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import hudson.security.ACL;
import jenkins.model.Jenkins;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

public class OpenstackCredentials {

    public static OpenstackCredential getCredential(String credentialId) {
        List<OpenstackCredential> credentials =
                CredentialsProvider.lookupCredentials(
                        OpenstackCredential.class, Jenkins.getInstance(), ACL.SYSTEM,
                        Collections.<DomainRequirement>emptyList());
        OpenstackCredential openstackCredential =
                CredentialsMatchers.firstOrNull(credentials,
                        CredentialsMatchers.allOf(CredentialsMatchers.withId(credentialId)));
        return openstackCredential;
    }

    public static void add(OpenstackCredential openstackCredential) {
        SystemCredentialsProvider.getInstance().getCredentials().add(openstackCredential);
    }

    public static void save() throws IOException {
       SystemCredentialsProvider.getInstance().save();
    }
}
