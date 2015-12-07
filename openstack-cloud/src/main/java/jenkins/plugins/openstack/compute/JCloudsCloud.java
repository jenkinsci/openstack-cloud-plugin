package jenkins.plugins.openstack.compute;

import javax.servlet.ServletException;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.logging.Logger;

import com.google.inject.Module;

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

import org.jclouds.Constants;
import org.jclouds.ContextBuilder;
import org.jclouds.apis.Apis;
import org.jclouds.compute.ComputeService;
import org.jclouds.compute.ComputeServiceContext;
import org.jclouds.compute.config.ComputeServiceProperties;
import org.jclouds.compute.domain.ComputeMetadata;
import org.jclouds.compute.domain.NodeMetadata;
import org.jclouds.enterprise.config.EnterpriseConfigurationModule;
import org.jclouds.location.reference.LocationConstants;
import org.jclouds.logging.jdk.config.JDKLoggingModule;
import org.jclouds.openstack.neutron.v2.NeutronApiMetadata;
import org.jclouds.openstack.nova.v2_0.NovaApiMetadata;
import org.jclouds.sshj.config.SshjSshClientModule;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.jclouds.openstack.neutron.v2.NeutronApi;

import shaded.com.google.common.base.Objects;
import shaded.com.google.common.base.Predicate;
import shaded.com.google.common.base.Strings;
import shaded.com.google.common.collect.ImmutableSet;
import shaded.com.google.common.collect.ImmutableSet.Builder;
import shaded.com.google.common.collect.ImmutableSortedSet;
import shaded.com.google.common.collect.Iterables;
import shaded.com.google.common.io.Closeables;

import static jenkins.plugins.openstack.compute.CloudInstanceDefaults.DEFAULT_INSTANCE_RETENTION_TIME_IN_MINUTES;

/**
 * The JClouds version of the Jenkins Cloud.
 *
 * @author Vijay Kiran
 */
public class JCloudsCloud extends Cloud {

    static final Logger LOGGER = Logger.getLogger(JCloudsCloud.class.getName());

    public final String identity;
    public final Secret credential;
    public final String endPointUrl;
    public final String profile;
    private final int retentionTime;
    private final int recreationTime;
    public final int instanceCap;
    public final List<JCloudsSlaveTemplate> templates;
    public final int scriptTimeout;
    public final int startTimeout;
    public final String zone;
    // Ask for a floating IP to be associated for every machine provisioned
    private final boolean floatingIps;

    private transient ComputeService compute;

    public enum SlaveType {SSH, JNLP}

    public static List<String> getCloudNames() {
        List<String> cloudNames = new ArrayList<String>();
        for (Cloud c : Jenkins.getInstance().clouds) {
            if (JCloudsCloud.class.isInstance(c)) {
                cloudNames.add(c.name);
            }
        }

        return cloudNames;
    }

    public static JCloudsCloud getByName(String name) {
        return (JCloudsCloud) Jenkins.getInstance().clouds.getByName(name);
    }

    @DataBoundConstructor @Restricted(DoNotUse.class)
    public JCloudsCloud(final String profile, final String identity, final String credential, final String endPointUrl, final int instanceCap, final int retentionTime,
                        final int recreationTime, final int scriptTimeout, final int startTimeout, final String zone, final List<JCloudsSlaveTemplate> templates,
                        final boolean floatingIps
    ) {
        super(Util.fixEmptyAndTrim(profile));
        this.profile = Util.fixEmptyAndTrim(profile);
        this.identity = Util.fixEmptyAndTrim(identity);
        this.credential = Secret.fromString(credential);
        this.endPointUrl = Util.fixEmptyAndTrim(endPointUrl);
        this.instanceCap = instanceCap;
        this.retentionTime = retentionTime;
        this.recreationTime = recreationTime;
        this.scriptTimeout = scriptTimeout;
        this.startTimeout = startTimeout;
        this.templates = Objects.firstNonNull(templates, Collections.<JCloudsSlaveTemplate> emptyList());
        this.zone = Util.fixEmptyAndTrim(zone);
        this.floatingIps = floatingIps;
        readResolve();
    }

    protected Object readResolve() {
        for (JCloudsSlaveTemplate template : templates)
            template.cloud = this;
        return this;
    }

    /**
     * Get the retention time in minutes or default value from CloudInstanceDefaults if not set.
     * @see CloudInstanceDefaults#DEFAULT_INSTANCE_RETENTION_TIME_IN_MINUTES
     */
    public int getRetentionTime() {
        return retentionTime == 0 ? DEFAULT_INSTANCE_RETENTION_TIME_IN_MINUTES : retentionTime;
    }

    public int getRecreationTime() {
        return recreationTime;
    }

    public boolean isFloatingIps() {
        return floatingIps;
    }

    static final Iterable<Module> MODULES = ImmutableSet.<Module>of(new SshjSshClientModule(), new JDKLoggingModule() {
        @Override
        public org.jclouds.logging.Logger.LoggerFactory createLoggerFactory() {
            return new ComputeLogger.Factory();
        }
    }, new EnterpriseConfigurationModule());

    /*package*/ static ComputeServiceContext ctx(String identity, String credential, String endPointUrl, String zone) {
        Properties overrides = new Properties();
        if (!Strings.isNullOrEmpty(endPointUrl)) {
            overrides.setProperty(Constants.PROPERTY_ENDPOINT, endPointUrl);
        }
        return ctx(identity, credential, overrides, zone);
    }

    /*package*/ static ComputeServiceContext ctx(String identity, String credential, Properties overrides, String zone) {
        if (!Strings.isNullOrEmpty(zone)) {
            overrides.setProperty(LocationConstants.PROPERTY_ZONES, zone);
        }
        // correct the classloader so that extensions can be found
        Thread.currentThread().setContextClassLoader(Apis.class.getClassLoader());
        return ContextBuilder
                .newBuilder(new NovaApiMetadata())
                .credentials(identity, credential)
                .overrides(overrides)
                .modules(MODULES)
                .buildView(ComputeServiceContext.class);
    }

    /*package*/ static NeutronApi na(String identity, String credential, String endPointUrl) {
        Thread.currentThread().setContextClassLoader(NeutronApiMetadata.class.getClassLoader());
        return ContextBuilder.newBuilder(new NeutronApiMetadata())
                        .credentials(identity, credential)
                        .endpoint(endPointUrl)
                        .modules(MODULES)
                        .buildApi(NeutronApi.class);
    }

    public ComputeService getCompute() {
        if (this.compute == null) {
            Properties overrides = new Properties();
            if (!Strings.isNullOrEmpty(this.endPointUrl)) {
                overrides.setProperty(Constants.PROPERTY_ENDPOINT, this.endPointUrl);
            }
            if (scriptTimeout > 0) {
                overrides.setProperty(ComputeServiceProperties.TIMEOUT_SCRIPT_COMPLETE, String.valueOf(scriptTimeout));
            }
            if (startTimeout > 0) {
                overrides.setProperty(ComputeServiceProperties.TIMEOUT_NODE_RUNNING, String.valueOf(startTimeout));
            }
            this.compute = ctx(this.identity, Secret.toString(credential), overrides, this.zone).getComputeService();
        }
        return compute;
    }

    public List<JCloudsSlaveTemplate> getTemplates() {
        return Collections.unmodifiableList(templates);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Collection<NodeProvisioner.PlannedNode> provision(Label label, int excessWorkload) {
        final JCloudsSlaveTemplate template = getTemplate(label);
        List<PlannedNode> plannedNodeList = new ArrayList<PlannedNode>();

        while (excessWorkload > 0 && !Jenkins.getInstance().isQuietingDown() && !Jenkins.getInstance().isTerminating()) {

            if ((getRunningNodesCount() + plannedNodeList.size()) >= instanceCap) {
                LOGGER.info("Instance cap reached while adding capacity for label " + ((label != null) ? label.toString() : "null"));
                break; // maxed out
            }

            plannedNodeList.add(new PlannedNode(template.name, Computer.threadPoolForRemoting.submit(new Callable<Node>() {
                public Node call() throws Exception {
                    // TODO: record the output somewhere
                    JCloudsSlave jcloudsSlave = template.provisionSlave(StreamTaskListener.fromStdout());
                    Jenkins.getInstance().addNode(jcloudsSlave);

                    /* Cloud instances may have a long init script. If we declare the provisioning complete by returning
                    without the connect operation, NodeProvisioner may decide that it still wants one more instance,
                    because it sees that (1) all the slaves are offline (because it's still being launched) and (2)
                    there's no capacity provisioned yet. Deferring the completion of provisioning until the launch goes
                    successful prevents this problem.  */
                    ensureLaunched(jcloudsSlave);
                    return jcloudsSlave;
                }
            }), Util.tryParseNumber(template.numExecutors, 1).intValue()));
            excessWorkload -= template.getNumExecutors();
        }
        return plannedNodeList;
    }

    private void ensureLaunched(JCloudsSlave jcloudsSlave) throws InterruptedException, ExecutionException {
        Integer launchTimeoutSec = this.startTimeout;
        Computer computer = jcloudsSlave.toComputer();
        long startMoment = System.currentTimeMillis();
        while (computer.isOffline()) {
            try {
                LOGGER.fine(String.format("Slave [%s] not connected yet", jcloudsSlave.getDisplayName()));
                computer.connect(false).get();
                Thread.sleep(5000l);
            } catch (InterruptedException|ExecutionException|NullPointerException e) {
                LOGGER.fine(String.format("Error while launching slave: %s", e));
            }

            if ((System.currentTimeMillis() - startMoment) > launchTimeoutSec) {
                String message = String.format("Failed to connect to slave within timeout (%d s).", launchTimeoutSec);
                LOGGER.warning(message);
                jcloudsSlave.setPendingDelete(true);
                throw new ExecutionException(new Throwable(message));
            }
        }
    }

    @Override
    public boolean canProvision(final Label label) {
        return getTemplate(label) != null;
    }

    public JCloudsSlaveTemplate getTemplate(String name) {
        for (JCloudsSlaveTemplate t : templates)
            if (t.name.equals(name))
                return t;
        return null;
    }

    /**
     * Gets {@link jenkins.plugins.openstack.compute.JCloudsSlaveTemplate} that has the matching {@link Label}.
     */
    public JCloudsSlaveTemplate getTemplate(Label label) {
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

        if (getRunningNodesCount() < instanceCap) {
            StringWriter sw = new StringWriter();
            StreamTaskListener listener = new StreamTaskListener(sw);
            JCloudsSlave node = t.provisionSlave(listener);
            Jenkins.getInstance().addNode(node);
            rsp.sendRedirect2(req.getContextPath() + "/computer/" + node.getNodeName());
        } else {
            sendError("Instance cap for this cloud is now reached for cloud profile: " + profile + " for template type " + name, req, rsp);
        }
    }

    /**
     * Determine how many nodes are currently running for this cloud.
     */
    public int getRunningNodesCount() {
        int nodeCount = 0;

        for (ComputeMetadata cm : getCompute().listNodes()) {
            if (NodeMetadata.class.isInstance(cm)) {
                String nodeGroup = ((NodeMetadata) cm).getGroup();

                if (getTemplate(nodeGroup) != null && !((NodeMetadata) cm).getStatus().equals(NodeMetadata.Status.SUSPENDED)
                        && !((NodeMetadata) cm).getStatus().equals(NodeMetadata.Status.TERMINATED)) {
                    nodeCount++;
                }
            }
        }
        return nodeCount;
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<Cloud> {

        /**
         * Human readable name of this kind of configurable object.
         */
        @Override
        public String getDisplayName() {
            return "Cloud (Openstack)";
        }

        public FormValidation doTestConnection(@QueryParameter String identity,
                                               @QueryParameter String credential,
                                               @QueryParameter String endPointUrl,
                                               @QueryParameter String zone) throws IOException {
            if (identity == null)
                return FormValidation.error("Invalid (AccessId).");
            if (credential == null)
                return FormValidation.error("Invalid credential (secret key).");

            identity = Util.fixEmptyAndTrim(identity);
            credential = Secret.fromString(credential).getPlainText();
            endPointUrl = Util.fixEmptyAndTrim(endPointUrl);
            zone = Util.fixEmptyAndTrim(zone);

            FormValidation result = FormValidation.ok("Connection succeeded!");
            ComputeServiceContext ctx = null;
            try {
                Properties overrides = new Properties();
                if (!Strings.isNullOrEmpty(endPointUrl)) {
                    overrides.setProperty(Constants.PROPERTY_ENDPOINT, endPointUrl);
                }

                ctx = ctx(identity, credential, overrides, zone);

                ctx.getComputeService().listNodes();
            } catch (Exception ex) {
                result = FormValidation.error("Cannot connect to specified cloud, please check the identity and credentials: " + ex.getMessage());
            } finally {
                Closeables.close(ctx,true);
            }
            return result;
        }

        public FormValidation doCheckProfile(@QueryParameter String value) {
            return FormValidation.validateRequired(value);
        }

        public FormValidation doCheckCredential(@QueryParameter String value) {
            return FormValidation.validateRequired(value);
        }

        public FormValidation doCheckIdentity(@QueryParameter String value) {
            return FormValidation.validateRequired(value);
        }

        public FormValidation doCheckInstanceCap(@QueryParameter String value) {
            return FormValidation.validatePositiveInteger(value);
        }

        public FormValidation doCheckRetentionTime(@QueryParameter String value) {
            try {
                if (Integer.parseInt(value) == -1)
                    return FormValidation.ok();
            } catch (NumberFormatException e) {
            }
            return FormValidation.validateNonNegativeInteger(value);
        }

        public FormValidation doCheckScriptTimeout(@QueryParameter String value) {
            return FormValidation.validatePositiveInteger(value);
        }

        public FormValidation doCheckStartTimeout(@QueryParameter String value) {
            return FormValidation.validatePositiveInteger(value);
        }

        public FormValidation doCheckEndPointUrl(@QueryParameter String value) {
            if (!value.isEmpty() && !value.startsWith("http")) {
                return FormValidation.error("The endpoint must be an URL");
            }
            return FormValidation.ok();
        }
    }
}
