package jenkins.plugins.openstack.compute;

import java.io.IOException;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.servlet.ServletException;

import hudson.plugins.sshslaves.SSHLauncher;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.JNLPLauncher;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
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

/**
 * The JClouds version of the Jenkins Cloud.
 *
 * @author Vijay Kiran
 */
public class JCloudsCloud extends Cloud implements SlaveOptions.Holder {

    private static final Logger LOGGER = Logger.getLogger(JCloudsCloud.class.getName());

    public final @Nonnull String profile;
    public final @Nonnull String endPointUrl;
    public final @Nonnull String identity;
    public final @Nonnull Secret credential;
    public final String zone;

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

                String publicAddress = slave.getPublicAddress();
                if (publicAddress == null) {
                    throw new IOException("The slave is likely deleted");
                }
                if ("0.0.0.0".equals(publicAddress)) {
                    throw new IOException("Invalid host 0.0.0.0, your host is most likely waiting for an ip address.");
                }
                SlaveOptions opts = slave.getSlaveOptions();
                Integer timeout = opts.getStartTimeout();
                timeout = timeout == null ? 0 : (timeout / 1000); // Never propagate null - always set some timeout
                return new SSHLauncher(publicAddress, 22, opts.getCredentialsId(), opts.getJvmOptions(), null, "", "", timeout, maxNumRetries, retryWaitTime);
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
        for (Cloud c : Jenkins.getInstance().clouds) {
            if (JCloudsCloud.class.isInstance(c)) {
                cloudNames.add(c.name);
            }
        }

        return cloudNames;
    }

    public static @Nonnull JCloudsCloud getByName(@Nonnull String name) throws IllegalArgumentException {
        Cloud cloud = Jenkins.getInstance().clouds.getByName(name);
        if (cloud instanceof JCloudsCloud) return (JCloudsCloud) cloud;
        throw new IllegalArgumentException(name + " is not an OpenStack cloud: " + cloud);
    }

    @DataBoundConstructor @Restricted(DoNotUse.class)
    public JCloudsCloud(
            final String profile, final String identity, final String credential, final String endPointUrl, final String zone,
            final SlaveOptions slaveOptions,
            final List<JCloudsSlaveTemplate> templates
    ) {
        super(Util.fixEmptyAndTrim(profile));
        this.profile = Util.fixEmptyAndTrim(profile);
        this.endPointUrl = Util.fixEmptyAndTrim(endPointUrl);
        this.identity = Util.fixEmptyAndTrim(identity);
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
     * {@inheritDoc}
     */
    @Override
    public Collection<NodeProvisioner.PlannedNode> provision(Label label, int excessWorkload) {
        final JCloudsSlaveTemplate template = getTemplate(label);
        if (template == null) throw new AssertionError("No template for label: " + label);
        final SlaveOptions opts = template.getEffectiveSlaveOptions();

        List<PlannedNode> plannedNodeList = new ArrayList<>();

        while (excessWorkload > 0 && !Jenkins.getInstance().isQuietingDown() && !Jenkins.getInstance().isTerminating()) {

            if ((getRunningNodesCount() + plannedNodeList.size()) >= opts.getInstanceCap()) {
                LOGGER.info("Instance cap reached while adding capacity for label " + ((label != null) ? label.toString() : "null"));
                break; // maxed out
            }

            plannedNodeList.add(new PlannedNode(template.name, Computer.threadPoolForRemoting.submit(new Callable<Node>() {
                @Override
                public Node call() throws Exception {
                    // TODO: record the output somewhere
                    JCloudsSlave jcloudsSlave;
                    try {
                        jcloudsSlave = template.provisionSlave(JCloudsCloud.this, StreamTaskListener.fromStdout());
                    } catch (Openstack.ActionFailed ex) {
                        throw new ProvisioningFailedException("Openstack failed to provision the slave", ex);
                    }
                    Jenkins.getInstance().addNode(jcloudsSlave);

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
            }), opts.getNumExecutors()));
            excessWorkload -= opts.getNumExecutors();
        }
        return plannedNodeList;
    }

    private void ensureLaunched(JCloudsSlave jcloudsSlave, SlaveOptions opts) throws InterruptedException, ProvisioningFailedException {
        JCloudsComputer computer = (JCloudsComputer) jcloudsSlave.toComputer();

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

    @Override
    public boolean canProvision(final Label label) {
        return getTemplate(label) != null;
    }

    public @CheckForNull JCloudsSlaveTemplate getTemplate(String name) {
        for (JCloudsSlaveTemplate t : templates)
            if (t.name.equals(name))
                return t;
        return null;
    }

    /**
     * Gets {@link jenkins.plugins.openstack.compute.JCloudsSlaveTemplate} that has the matching {@link Label}.
     */
    private @CheckForNull JCloudsSlaveTemplate getTemplate(@CheckForNull Label label) {
        for (JCloudsSlaveTemplate t : templates)
            if (label == null || label.matches(t.getLabelSet()))
                return t;
        return null;
    }

    /**
     * Provisions a new node manually (by clicking a button in the computer list)
     *
     * @param req  {@link StaplerRequest}
     * @param rsp  {@link StaplerResponse}
     * @param name Name of the template to provision
     * @throws ServletException
     * @throws IOException
     * @throws Descriptor.FormException
     */
    @Restricted(DoNotUse.class)
    public void doProvision(StaplerRequest req, StaplerResponse rsp, @QueryParameter String name) throws ServletException, IOException,
            Descriptor.FormException {
        checkPermission(PROVISION);
        if (name == null) {
            sendError("The slave template name query parameter is missing", req, rsp);
            return;
        }
        JCloudsSlaveTemplate t = getTemplate(name);
        if (t == null) {
            sendError("No such slave template with name : " + name, req, rsp);
            return;
        }

        Integer instanceCap = t.getEffectiveSlaveOptions().getInstanceCap();
        if (getRunningNodesCount() < instanceCap) {
            JCloudsSlave node;
            try {
                StringWriter sw = new StringWriter();
                StreamTaskListener listener = new StreamTaskListener(sw);
                node = t.provisionSlave(this, listener);
            } catch (Openstack.ActionFailed ex) {
                sendError(ex.getMessage());
                return;
            }
            Jenkins.getInstance().addNode(node);
            rsp.sendRedirect2(req.getContextPath() + "/computer/" + node.getNodeName());
        } else {
            String msg = String.format("Instance cap for this cloud/profile (%s/%s) is now reached: %d", profile, name, instanceCap);
            sendError(msg, req, rsp);
        }
    }

    /**
     * Determine how many nodes are currently running for this cloud.
     */
    private int getRunningNodesCount() {
        return getOpenstack().getRunningNodes().size();
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
                Openstack.Factory.get(endPointUrl, identity, credential, zone);
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
    public static final class ProvisioningFailedException extends Exception {

        public ProvisioningFailedException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }
}
