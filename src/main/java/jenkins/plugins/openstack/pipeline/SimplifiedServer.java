package jenkins.plugins.openstack.pipeline;

import jenkins.plugins.openstack.compute.JCloudsCloud;
import jenkins.plugins.openstack.compute.JCloudsSlaveTemplate;
import jenkins.plugins.openstack.compute.ServerScope;
import jenkins.plugins.openstack.compute.internal.DestroyMachine;
import jenkins.plugins.openstack.compute.internal.Openstack;
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.Whitelisted;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.openstack4j.model.compute.Server;

import java.io.Serializable;

/**
 * Server wrapper for pipeline use.
 */
@Restricted(NoExternalUse.class)
public class SimplifiedServer implements Serializable {

    private Server srv = null;
    private String cloud = "";
    private String template = "";
    private String scope = "";

    public SimplifiedServer(String cloud, String template, String scope) {
        this.template = template;
        this.cloud = cloud;
        this.scope = scope;

        ServerScope serverscope = ServerScope.parse(scope);
        JCloudsCloud jcl = JCloudsCloud.getByName(cloud);
        JCloudsSlaveTemplate t = jcl.getTemplate(template);
        if (t != null) {
            this.srv = t.provision(jcl, serverscope);
        } else {
            throw new IllegalArgumentException("Invalid template: " + template);
        }
    }

    @Whitelisted
    public void destroy() {
        if (srv != null) {
            DestroyMachine dm = new DestroyMachine(this.cloud, srv.getId());
            dm.dispose();
            srv = null;
        } else {
            //Cant destroy what isn't created
        }
    }

    @Whitelisted
    public String getAddress() {
        String addr = "";
        if (srv != null) {
            try {
                addr = Openstack.getPublicAddress(srv);
            } catch (NullPointerException npe) {
                //Seems the machine doesnt have any address (related to boot-up?)
            }
        } else {
            //No IPs from unprovisioned servers
        }
        return addr;
    }

    @Whitelisted
    public String getStatus() {
        if (srv != null) {
            return srv.getStatus().name();
        } else {
            return null;
        }
    }

    @Whitelisted
    public String getId() {
        if (srv != null) {
           return srv.getId();
        } else {
            return null;
        }
    }
}
