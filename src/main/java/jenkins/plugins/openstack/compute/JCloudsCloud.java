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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.servlet.ServletException;

import hudson.model.Item;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.JNLPLauncher;
import org.jenkinsci.plugins.cloudstats.CloudStatistics;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedPlannedNode;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

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

    public final @Nonnull String endPointUrl;
    public final @Nonnull String identity;
    public final @Nonnull Secret credential;
    // OpenStack4j requires null when there is no zone configured
    public final @CheckForNull String zone;

    private final @Nonnull List<JCloudsSlaveTemplate> templates;

    private /*final*/ @Nonnull SlaveOptions slaveOptions;

    // Backward compatibility
    private transient @Deprecated Integer instanceCap;
    private transient @Deprecated Integer retentionTime;
    private transient @Deprecated Integer startTimeout;
    private transient @Deprecated Boolean floatingIps;

    // TODO: refactor to interface/extension point
    public enum SlaveType {
        SSH {
            @Override
            public ComputerLauncher createLauncher(@Nonnull JCloudsSlave slave) throws IOException {
                int maxNumRetries = 5;
                int retryWaitTime = 15;

                SlaveOptions opts = slave.getSlaveOptions();
                String credentialsId = opts.getCredentialsId();
                if (credentialsId == null) {
                    throw new ProvisioningFailedException("No ssh credentials selected");
                }

                String publicAddress = slave.getPublicAddress();
                if (publicAddress == null) {
                    throw new IOException("The slave is likely deleted");
                }
                if ("0.0.0.0".equals(publicAddress)) {
                    throw new IOException("Invalid host 0.0.0.0, your host is most likely waiting for an ip address.");
                }

                Integer timeout = opts.getStartTimeout();
                timeout = timeout == null ? 0 : (timeout / 1000); // Never propagate null - always set some timeout

                return new SSHLauncher(publicAddress, 22, credentialsId, opts.getJvmOptions(), null, "", "", timeout, maxNumRetries, retryWaitTime);
            }
        },
        JNLP {
            @Override
            public ComputerLauncher createLauncher(@Nonnull JCloudsSlave slave) {
                return new JNLPLauncher();
            }
        };

        public abstract ComputerLauncher createLauncher(@Nonnull JCloudsSlave slave) throws IOException;
    }

    public static @Nonnull List<String> getCloudNames() {
        List<String> cloudNames = new ArrayList<>();
        for (Cloud c : Jenkins.getActiveInstance().clouds) {
            if (JCloudsCloud.class.isInstance(c)) {
                cloudNames.add(c.name);
            }
        }

        return cloudNames;
    }

    public static @Nonnull JCloudsCloud getByName(@Nonnull String name) throws IllegalArgumentException {
        Cloud cloud = Jenkins.getActiveInstance().clouds.getByName(name);
        if (cloud instanceof JCloudsCloud) return (JCloudsCloud) cloud;
        throw new IllegalArgumentException(name + " is not an OpenStack cloud: " + cloud);
    }

    @DataBoundConstructor @Restricted(DoNotUse.class)
    public JCloudsCloud(
            final String name, final String identity, final String credential, final String endPointUrl, final String zone,
            final SlaveOptions slaveOptions,
            final List<JCloudsSlaveTemplate> templates
    ) {
        super(Util.fixNull(name).trim());
        this.endPointUrl = Util.fixNull(endPointUrl).trim();
        this.identity = Util.fixNull(identity).trim();
        this.credential = Secret.fromString(credential);
        this.zone = Util.fixEmptyAndTrim(zone);

        this.slaveOptions = slaveOptions.eraseDefaults(DescriptorImpl.DEFAULTS);

        this.templates = Collections.unmodifiableList(Objects.firstNonNull(templates, Collections.<JCloudsSlaveTemplate> emptyList()));
        injectReferenceIntoTemplates();
    }

    @SuppressWarnings({"unused", "deprecation"})
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

        injectReferenceIntoTemplates();

        return this;
    }

    private void injectReferenceIntoTemplates() {
        for(JCloudsSlaveTemplate t: templates) {
            t.setOwner(this);
        }
    }

    public @Nonnull SlaveOptions getEffectiveSlaveOptions() {
        // Make sure only diff of defaults is saved so when defaults will change users are not stuck with outdated config
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

    private static class NodeCallable implements Callable<Node> {
        private final JCloudsCloud cloud;
        private final JCloudsSlaveTemplate template;
        private final ProvisioningActivity.Id id;
        private final SlaveOptions opts;

        public NodeCallable(JCloudsCloud cloud, JCloudsSlaveTemplate template, ProvisioningActivity.Id id) {
            this.cloud = cloud;
            this.template = template;
            this.id = id;
            this.opts = template.getEffectiveSlaveOptions();
        }

        @Override
        public Node call() throws Exception {
            // TODO: record the output somewhere
            JCloudsSlave jcloudsSlave;
            try {
                jcloudsSlave = template.provisionSlave(cloud, id, StreamTaskListener.fromStdout());
            } catch (Openstack.ActionFailed ex) {
                throw new ProvisioningFailedException(ex.getMessage(), ex);
            }
            Jenkins.getActiveInstance().addNode(jcloudsSlave);

            /* Cloud instances may have a long init script. If we declare the provisioning complete by returning
            without the connect operation, NodeProvisioner may decide that it still wants one more instance,
            because it sees that (1) all the slaves are offline (because it's still being launched) and (2)
            there's no capacity provisioned yet. Deferring the completion of provisioning until the launch goes
            successful prevents this problem.  */
            // TODO: this seems to be a cause of constant problems, consider removing this. If the problem really
            // TODO: exists, it should be fixed in core instead as several plugins must face this problem. Also,
            // TODO: we do not use init script anymore.
            ensureLaunched(jcloudsSlave, opts);
            return jcloudsSlave;
        }

        private void ensureLaunched(JCloudsSlave jcloudsSlave, SlaveOptions opts) throws InterruptedException, ProvisioningFailedException {
            JCloudsComputer computer = (JCloudsComputer) jcloudsSlave.toComputer();
            if (computer == null) throw new ProvisioningFailedException("Computer does not exist");

            Integer launchTimeout = opts.getStartTimeout();
            long startMoment = System.currentTimeMillis();

            String timeoutMessage = String.format("Failed to connect to slave %s within timeout (%d ms).", computer.getName(), launchTimeout);
            while (computer.isOffline()) {
                LOGGER.fine(String.format("Waiting for slave %s to launch", jcloudsSlave.getDisplayName()));
                Thread.sleep(2000);
                Throwable lastError = null;
                Future<?> connectionActivity = computer.connect(false);
                try {
                    connectionActivity.get(launchTimeout, TimeUnit.MILLISECONDS);
                } catch (ExecutionException e) {
                    lastError = e.getCause() == null ? e : e.getCause();
                    LOGGER.log(Level.FINE, "Error while launching slave, retrying: " + computer.getName(), lastError);
                    // Retry
                } catch (TimeoutException e) {
                    LOGGER.log(Level.WARNING, timeoutMessage, e);

                    // Wait for the activity to be canceled before letting the slave to get deleted. Otherwise launcher can
                    // still be using slave/computer pair while it is being deleted producing misleading exceptions.
                    connectionActivity.cancel(true);
                    try {
                        connectionActivity.get(5, TimeUnit.SECONDS);
                    } catch (Throwable ex) { }

                    computer.setPendingDelete(true);
                    throw new ProvisioningFailedException("Slave launch of " + computer.getName() + "timed out", e);
                }

                if ((System.currentTimeMillis() - startMoment) > launchTimeout) {
                    LOGGER.warning(timeoutMessage);
                    computer.setPendingDelete(true);
                    throw new ProvisioningFailedException(timeoutMessage, lastError);
                }
            }

            LOGGER.fine(String.format("Slave %s launched successfully", jcloudsSlave.getDisplayName()));
        }
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
    public void doProvision(StaplerRequest req, StaplerResponse rsp, @QueryParameter String name) throws ServletException, IOException, Descriptor.FormException {
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
            StringWriter sw = new StringWriter();
            StreamTaskListener listener = new StreamTaskListener(sw);
            provisioningListener.onStarted(id);
            node = t.provisionSlave(this, id, listener);
            provisioningListener.onComplete(id, node);
        } catch (Openstack.ActionFailed ex) {
            provisioningListener.onFailure(id, ex);
            req.setAttribute("message", ex.getMessage());
            req.setAttribute("exception", ex);
            rsp.forward(this,"error",req);
            return;
        }
        Jenkins.getActiveInstance().addNode(node);
        rsp.sendRedirect2(req.getContextPath() + "/computer/" + node.getNodeName());
    }

    /**
     * Get connected OpenStack client wrapper.
     */
    @Restricted(DoNotUse.class)
    public @Nonnull Openstack getOpenstack() {
        return new Openstack(endPointUrl, identity, credential, zone);
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
                .slaveType(SlaveType.SSH)
                .build()
        ;

        @Override
        public String getDisplayName() {
            return "Cloud (OpenStack)";
        }

        public SlaveOptions getDefaultOptions() {
            return DEFAULTS;
        }

        @Restricted(DoNotUse.class)
        public FormValidation doTestConnection(
                @QueryParameter String zone,
                @QueryParameter String endPointUrl,
                @QueryParameter String identity,
                @QueryParameter String credential
        ) {
            try {
                Throwable ex = Openstack.Factory.get(endPointUrl, identity, credential, zone).sanityCheck();
                if (ex != null) {
                    return FormValidation.warning(ex, "Connection not validated, plugin might not operate correctly: " + ex.getMessage());
                }
            } catch (FormValidation ex) {
                return ex;
            } catch (Exception ex) {
                return FormValidation.error(ex, "Cannot connect to specified cloud, please check the identity and credentials: " + ex.getMessage());
            }
            return FormValidation.ok("Connection succeeded!");
        }

        @Restricted(DoNotUse.class)
        public FormValidation doCheckEndPointUrl(@QueryParameter String value) {
            if (Util.fixEmpty(value) == null) return FormValidation.validateRequired(value);

            try {
                new URL(value);
            } catch (MalformedURLException ex) {
                FormValidation.error(ex, "The endpoint must be an URL"  );
            }
            return FormValidation.ok();
        }
    }

    /**
     * The request to provision was not fulfilled.
     */
    public static final class ProvisioningFailedException extends RuntimeException {

        public ProvisioningFailedException(String msg, Throwable cause) {
            super(msg, cause);
        }

        public ProvisioningFailedException(String msg) {
            super(msg);
        }
    }
}
