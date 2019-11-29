package jenkins.plugins.openstack.compute.auth;

import com.cloudbees.plugins.credentials.common.StandardCredentials;
import org.openstack4j.api.OSClient;
import org.openstack4j.api.client.IOSClientBuilder;

import javax.annotation.Nonnull;

/**
 * Represents the credentials to connect to Openstack
 */
public interface OpenstackCredential extends StandardCredentials {

    @Nonnull IOSClientBuilder<? extends OSClient<?>, ?> getBuilder(String endPointUrl);
}
