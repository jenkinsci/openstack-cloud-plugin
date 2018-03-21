package jenkins.plugins.openstack.compute;

import java.io.IOException;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.servlet.ServletException;

import com.cloudbees.plugins.credentials.*;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import hudson.model.Item;
import hudson.security.ACL;
import hudson.util.ListBoxModel;
import jenkins.plugins.openstack.compute.auth.*;
import jenkins.plugins.openstack.compute.slaveopts.LauncherFactory;
import org.jenkinsci.plugins.cloudstats.CloudStatistics;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedPlannedNode;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.*;

import com.google.common.base.Objects;

import hudson.Extension;
import hudson.Util;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.util.FormValidation;
import hudson.util.Secret;
import hudson.util.StreamTaskListener;
import jenkins.model.Jenkins;
import jenkins.plugins.openstack.compute.internal.Openstack;
import org.openstack4j.model.compute.Server;

/**
 * The JClouds version of the Jenkins Cloud.
 *
 * @author Vijay Kiran
 */
public class JCloudsCloud extends Cloud implements SlaveOptions.Holder {

    private static final Logger LOGGER = Logger.getLogger(JCloudsCloud.class.getName());

    private String credentialId;

    public final @Nonnull String endPointUrl;
    private boolean ignoreSsl;

    // OpenStack4j requires null when there is no zone configured
    public final @CheckForNull String zone;

    private final @Nonnull List<JCloudsSlaveTemplate> templates;

    // Make sure only diff of defaults is saved so when plugin defaults will change users are not stuck with outdated config
    private /*final*/ @Nonnull SlaveOptions slaveOptions;

    // Backward compatibility
    private transient @Deprecated Integer instanceCap;
    private transient @Deprecated Integer retentionTime;
    private transient @Deprecated Integer startTimeout;
    private transient @Deprecated Boolean floatingIps;
    private transient @Deprecated String identity;
    private transient @Deprecated Secret credential;

    public static @Nonnull List<JCloudsCloud> getClouds() {
        List<JCloudsCloud> clouds = new ArrayList<>();
        for (Cloud c : Jenkins.getActiveInstance().clouds) {
            if (JCloudsCloud.class.isInstance(c)) {
                clouds.add((JCloudsCloud) c);
            }
        }

        return clouds;
    }

    public static @Nonnull JCloudsCloud getByName(@Nonnull String name) throws IllegalArgumentException {
        Cloud cloud = Jenkins.getActiveInstance().clouds.getByName(name);
        if (cloud instanceof JCloudsCloud) return (JCloudsCloud) cloud;
        throw new IllegalArgumentException("'" + name + "' is not an OpenStack cloud but " + cloud);
    }

    @DataBoundConstructor @Restricted(DoNotUse.class)
    public JCloudsCloud(
            final String name, final String endPointUrl, final boolean ignoreSsl, final String zone,
            final SlaveOptions slaveOptions,
            final List<JCloudsSlaveTemplate> templates,
            final String credentialId
    ) {
        super(Util.fixNull(name).trim());
        this.endPointUrl = Util.fixNull(endPointUrl).trim();
        this.ignoreSsl = ignoreSsl;
        this.zone = Util.fixEmptyAndTrim(zone);
        this.credentialId = credentialId;
        this.slaveOptions = slaveOptions.eraseDefaults(DescriptorImpl.DEFAULTS);

        this.templates = Collections.unmodifiableList(Objects.firstNonNull(templates, Collections.<JCloudsSlaveTemplate> emptyList()));

        injectReferenceIntoTemplates();
    }

    @SuppressWarnings({"unused", "deprecation", "ConstantConditions"})
    private Object readResolve() {
        if (retentionTime != null || startTimeout != null || floatingIps != null || instanceCap != null) {
            SlaveOptions carry = SlaveOptions.builder()
                    .instanceCap(instanceCap)
                    .retentionTime(retentionTime)
                    .startTimeout(startTimeout)
                    .floatingIpPool(floatingIps ? "public" : null)
                    .build()
            ;
            slaveOptions = DescriptorImpl.DEFAULTS.override(carry);
            retentionTime = null;
            startTimeout = null;
            floatingIps = null;
            instanceCap = null;
        }

        // Migrate to v2.24
        LauncherFactory lf = null;
        if ("JNLP".equals(slaveOptions.slaveType)) {
            lf = LauncherFactory.JNLP.JNLP;
        } else if (!"JNLP".equals(slaveOptions.slaveType) && slaveOptions.credentialsId != null) {
            // user configured credentials and clearly rely on SSH launcher that used to be the default so bring it back
            lf = new LauncherFactory.SSH(slaveOptions.credentialsId);
        }
        if (lf != null) {
            slaveOptions = slaveOptions.getBuilder().launcherFactory(lf).build();
        }

        injectReferenceIntoTemplates();

        if (identity != null) {
            String[] id = identity.split(":");
            OpenstackCredential migratedOpenstackCredential = null;
            if (id.length == 2) {
                //If id.length == 2, it is assumed that is being used API V2
                String tenant = id.length > 0 ? id[0] : "";
                String username = id.length > 1 ? id[1] : "";
                migratedOpenstackCredential = new OpenstackCredentialv2(CredentialsScope.SYSTEM,null,null,tenant,username,credential);
            } else if (id.length == 3) {
                // convert the former identity string PROJECT_NAME:USER_NAME:DOMAIN_NAME
                // to the new OpenstackV3 credential
                String project = id[0];
                String username = id[1];
                String domain = id[2];
                migratedOpenstackCredential = new OpenstackCredentialv3(CredentialsScope.SYSTEM,null,null,username,domain,project,domain,credential);
            }
            if (migratedOpenstackCredential != null) {
                credentialId = migratedOpenstackCredential.getId();
                try {
                    OpenstackCredentials.add(migratedOpenstackCredential);
                    OpenstackCredentials.save();
                    identity = null;
                    credential = null;
                } catch (IOException e) {
                    LOGGER.log(Level.SEVERE, "Unable to migrate " + name + " cloud credential to the new version", e);
                }
            } else {
                LOGGER.log(Level.SEVERE, "Unable to migrate " + name + " cloud credential to the new version");
            }
        }

        return this;
    }

    private void injectReferenceIntoTemplates() {
        for(JCloudsSlaveTemplate t: templates) {
            t.setOwner(this);
        }
    }

    public @Nonnull SlaveOptions getEffectiveSlaveOptions() {
        return DescriptorImpl.DEFAULTS.override(slaveOptions);
    }

    public @Nonnull SlaveOptions getRawSlaveOptions() {
        return slaveOptions;
    }

    public @Nonnull List<JCloudsSlaveTemplate> getTemplates() {
        return templates;
    }

    /**
     * Get a queue of templates to be used to provision slaves of label.
     *
     * The queue contains the same template in as many instances as is the number of machines that can be safely
     * provisioned without violating instanceCap constrain.
     */
    private @CheckForNull Queue<JCloudsSlaveTemplate> getAvailableTemplateProvider(@CheckForNull Label label) {
        final String labelString = (label != null) ? label.toString() : "none";
        final List<Server> runningNodes = getOpenstack().getRunningNodes();
        final int globalMax = getEffectiveSlaveOptions().getInstanceCap();

        final Queue<JCloudsSlaveTemplate> queue = new ConcurrentLinkedDeque<>();
        int globalCapacity = globalMax - runningNodes.size();
        if (globalCapacity <= 0) {
            LOGGER.log(Level.INFO,
                    "Global instance cap ({0}) reached while adding capacity for label: {1}",
                    new Object[] { globalMax, labelString}
            );
            return queue; // No need to proceed any further;
        }

        final Map<JCloudsSlaveTemplate, Integer> template2capacity = new LinkedHashMap<>();
        for (JCloudsSlaveTemplate t : templates) {
            if (t.canProvision(label)) {
                final int templateMax = t.getEffectiveSlaveOptions().getInstanceCap();

                int templateCapacity = templateMax;
                for (Server server : runningNodes) {
                    if (t.hasProvisioned(server)) {
                        templateCapacity--;
                    }
                }

                if (templateCapacity > 0) {
                    template2capacity.put(t, templateCapacity);
                } else {
                    LOGGER.log(Level.INFO,
                            "Template instance cap for {0} ({1}) reached while adding capacity for label: {2}",
                            new Object[] { t.name, templateMax, labelString }
                    );
                }
            }
        }

        done: for (Map.Entry<JCloudsSlaveTemplate, Integer> e : template2capacity.entrySet()) {
            for (int i = e.getValue(); i > 0; i--) {
                if (globalCapacity > 0) {
                    queue.add(e.getKey());
                    globalCapacity--;
                } else {
                    LOGGER.log(Level.INFO,
                            "Global instance cap ({0}) reached while adding capacity for label: {1}",
                            new Object[] { globalMax, labelString}
                    );
                    break done;
                }
            }
        }

        return queue;
    }

    @Override
    public Collection<NodeProvisioner.PlannedNode> provision(Label label, int excessWorkload) {
        Queue<JCloudsSlaveTemplate> templateProvider = getAvailableTemplateProvider(label);

        List<PlannedNode> plannedNodeList = new ArrayList<>();
        while (excessWorkload > 0 && !Jenkins.getActiveInstance().isQuietingDown() && !Jenkins.getActiveInstance().isTerminating()) {

            final JCloudsSlaveTemplate template = templateProvider.poll();
            if (template == null) {
                LOGGER.info("Instance cap exceeded on all available templates");
                break;
            }

            LOGGER.fine("Provisioning slave for " + label + " from template " + template.name);

            int numExecutors = template.getEffectiveSlaveOptions().getNumExecutors();

            ProvisioningActivity.Id id = new ProvisioningActivity.Id(this.name, template.name);
            Future<Node> task = Computer.threadPoolForRemoting.submit(new NodeCallable(this, template, id));
            plannedNodeList.add(new TrackedPlannedNode(id, numExecutors, task));

            excessWorkload -= numExecutors;
        }
        return plannedNodeList;
    }

    private static final class NodeCallable implements Callable<Node> {
        private final JCloudsCloud cloud;
        private final JCloudsSlaveTemplate template;
        private final ProvisioningActivity.Id id;

        NodeCallable(JCloudsCloud cloud, JCloudsSlaveTemplate template, ProvisioningActivity.Id id) {
            this.cloud = cloud;
            this.template = template;
            this.id = id;
        }

        @Override
        public Node call() {
            JCloudsSlave jcloudsSlave = template.provisionSlave(cloud, id);

            LOGGER.fine(String.format("Slave %s launched successfully", jcloudsSlave.getDisplayName()));
            return jcloudsSlave;
        }
    }

    @Restricted(NoExternalUse.class)
    public /*for mocking*/ @CheckForNull String slaveIsWaitingFor(@Nonnull JCloudsSlave slave) throws ProvisioningFailedException {
        return slave.getSlaveOptions().getLauncherFactory().isWaitingFor(slave);
    }

    @Override
    public boolean canProvision(final Label label) {
        for (JCloudsSlaveTemplate t : templates)
            if (t.canProvision(label))
                return true;
        return false;
    }

    public @CheckForNull JCloudsSlaveTemplate getTemplate(String name) {
        for (JCloudsSlaveTemplate t : templates)
            if (t.name.equals(name))
                return t;
        return null;
    }

    /**
     * Provisions a new node manually (by clicking a button in the computer list)
     *
     * @param req  {@link StaplerRequest}
     * @param rsp  {@link StaplerResponse}
     * @param name Name of the template to provision
     */
    @Restricted(NoExternalUse.class)
    public void doProvision(
            StaplerRequest req, StaplerResponse rsp, @QueryParameter String name
    ) throws ServletException, IOException, Descriptor.FormException, InterruptedException {

        // Temporary workaround for https://issues.jenkins-ci.org/browse/JENKINS-37616
        // Using Item.CONFIGURE as users authorized to do so can provision via job execution.
        // Once the plugins starts to depend on core new enough, we can use Cloud.PROVISION again.
        if (!hasPermission(Item.CONFIGURE) && !hasPermission(Cloud.PROVISION)) {
            checkPermission(Cloud.PROVISION);
        }

        if (name == null) {
            sendError("The slave template name query parameter is missing", req, rsp);
            return;
        }
        JCloudsSlaveTemplate t = getTemplate(name);
        if (t == null) {
            sendError("No such slave template with name : " + name, req, rsp);
            return;
        }

        List<Server> nodes = getOpenstack().getRunningNodes();
        final int global = nodes.size();

        int globalCap = getEffectiveSlaveOptions().getInstanceCap();
        if (global >= globalCap) {
            String msg = String.format("Instance cap of %s is now reached: %d", this.name, globalCap);
            sendError(msg, req, rsp);
            return;
        }

        int template = 0;
        for (Server node : nodes) {
            if (t.hasProvisioned(node)) {
                template++;
            }
        }

        int templateCap = t.getEffectiveSlaveOptions().getInstanceCap();
        if (template >= templateCap) {
            String msg = String.format("Instance cap for this template (%s/%s) is now reached: %d", this.name, name, templateCap);
            sendError(msg, req, rsp);
            return;
        }

        CloudStatistics.ProvisioningListener provisioningListener = CloudStatistics.ProvisioningListener.get();
        ProvisioningActivity.Id id = new ProvisioningActivity.Id(this.name, t.name);

        JCloudsSlave node;
        try {
            provisioningListener.onStarted(id);
            node = t.provisionSlave(this, id);
            provisioningListener.onComplete(id, node);
        } catch (Openstack.ActionFailed ex) {
            provisioningListener.onFailure(id, ex);
            req.setAttribute("message", ex.getMessage());
            req.setAttribute("exception", ex);
            rsp.forward(this,"error",req);
            return;
        } catch (Throwable ex) {
            provisioningListener.onFailure(id, ex);
            throw ex;
        }
        Jenkins.getActiveInstance().addNode(node);
        rsp.sendRedirect2(req.getContextPath() + "/computer/" + node.getNodeName());
    }

    /**
     * Get connected OpenStack client wrapper.
     */
    @Restricted(NoExternalUse.class)
    public @Nonnull Openstack getOpenstack() {
        final Openstack os;
        try {
            os = Openstack.Factory.get(endPointUrl, ignoreSsl, OpenstackCredentials.getCredential(credentialId), zone);
        } catch (FormValidation ex) {
            LOGGER.log(Level.SEVERE, "Openstack authentication invalid", ex);
            throw new RuntimeException("Openstack authentication invalid", ex);
        }
        return os;
    }

    public String getCredentialId() {
        return credentialId;
    }

    public boolean getIgnoreSsl() {
        return ignoreSsl;
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<Cloud> {

        // Plugin default slave attributes - the root of all overriding
        private static final SlaveOptions DEFAULTS = SlaveOptions.builder()
                .instanceCap(10)
                .retentionTime(30)
                .startTimeout(600000)
                .numExecutors(1)
                .fsRoot("/jenkins")
                .securityGroups("default")
                .build()
        ;

        @Override
        public @Nonnull String getDisplayName() {
            return "Cloud (OpenStack)";
        }

        public static SlaveOptions getDefaultOptions() {
            return DEFAULTS;
        }

        @Restricted(DoNotUse.class)
        public FormValidation doTestConnection(
                @QueryParameter boolean ignoreSsl,
                @QueryParameter String credentialId,
                @QueryParameter String endPointUrl,
                @QueryParameter String zone
        ) {
            try {
                // Get credential defined by user, using credential ID
                OpenstackCredential openstackCredential = OpenstackCredentials.getCredential(credentialId);
                Openstack openstack = Openstack.Factory.get(endPointUrl, ignoreSsl, openstackCredential, zone);
                Throwable ex = openstack.sanityCheck();

                if (ex != null) {
                    return FormValidation.warning(ex, "Connection not validated, plugin might not operate correctly: " + ex.getMessage());
                }
                return FormValidation.okWithMarkup("Connection succeeded!<br/><small>" + Util.escape(openstack.getInfo()) + "</small>");
            } catch (FormValidation ex) {
                return ex;
            } catch (Exception ex) {
                return FormValidation.error(ex, "Cannot connect to specified cloud, please check the identity and credentials: " + ex.getMessage());
            }
        }

        @Restricted(DoNotUse.class)
        public FormValidation doCheckEndPointUrl(@QueryParameter String value) {
            if (Util.fixEmpty(value) == null) return FormValidation.validateRequired(value);

            try {
                new URL(value);
            } catch (MalformedURLException ex) {
                return FormValidation.error(ex, "The endpoint must be URL");
            }
            return FormValidation.ok();
        }

        @Restricted(DoNotUse.class)
        public ListBoxModel doFillCredentialIdItems(@AncestorInPath Jenkins context) {
            if (context == null || !context.hasPermission(Item.CONFIGURE)) {
                return new StandardListBoxModel();
            }

            List<StandardCredentials> credentials = CredentialsProvider.lookupCredentials(
                    StandardCredentials.class, context, ACL.SYSTEM, Collections.<DomainRequirement>emptyList()
            );
            return new StandardListBoxModel()
                    .withEmptySelection()
                    .withMatching(CredentialsMatchers.instanceOf(OpenstackCredential.class), credentials)
            ;
        }
    }

    /**
     * The request to provision was not fulfilled.
     */
    public static final class ProvisioningFailedException extends RuntimeException {
        private static final long serialVersionUID = -8524954909721965323L;

        public ProvisioningFailedException(String msg, Throwable cause) {
            super(msg, cause);
        }

        public ProvisioningFailedException(String msg) {
            super(msg);
        }
    }
}
