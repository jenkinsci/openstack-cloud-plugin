package jenkins.plugins.openstack.compute;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import hudson.Extension;
import hudson.Util;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Failure;
import hudson.model.Item;
import hudson.model.Label;
import hudson.model.Node;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import jenkins.model.Jenkins;
import jenkins.plugins.openstack.compute.auth.OpenstackCredential;
import jenkins.plugins.openstack.compute.auth.OpenstackCredentials;
import jenkins.plugins.openstack.compute.auth.OpenstackCredentialv2;
import jenkins.plugins.openstack.compute.auth.OpenstackCredentialv3;
import jenkins.plugins.openstack.compute.internal.Openstack;
import jenkins.plugins.openstack.compute.slaveopts.LauncherFactory;
import jenkins.util.Timer;
import org.acegisecurity.Authentication;
import org.jenkinsci.Symbol;
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
import org.kohsuke.stapler.interceptor.RequirePOST;
import org.openstack4j.api.exceptions.AuthenticationException;
import org.openstack4j.model.compute.Server;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static java.lang.Boolean.TRUE;

/**
 * The JClouds version of the Jenkins Cloud.
 *
 * @author Vijay Kiran
 */
public class JCloudsCloud extends Cloud implements SlaveOptions.Holder {

    private static final Logger LOGGER = Logger.getLogger(JCloudsCloud.class.getName());

    private final @Nonnull String endPointUrl;

    private final boolean ignoreSsl;

    private final @CheckForNull String zone; // OpenStack4j requires null when there is no zone configured

    // Make sure only diff of defaults is saved so when plugin defaults will change users are not stuck with outdated config
    private /*final*/ @Nonnull SlaveOptions slaveOptions;

    private final @Nonnull List<JCloudsSlaveTemplate> templates;

    private /*final*/ @Nonnull String credentialId; // Name differs from property name not to break the persistence

    // Backward compatibility
    private transient @Deprecated Integer instanceCap;
    private transient @Deprecated Integer retentionTime;
    private transient @Deprecated Integer startTimeout;
    private transient @Deprecated Boolean floatingIps;
    private transient @Deprecated String identity;
    private transient @Deprecated Secret credential;

    public static @Nonnull List<JCloudsCloud> getClouds() {
        List<JCloudsCloud> clouds = new ArrayList<>();
        for (Cloud c : Jenkins.get().clouds) {
            if (c instanceof JCloudsCloud) {
                clouds.add((JCloudsCloud) c);
            }
        }

        return clouds;
    }

    /**
     * @throws IllegalArgumentException If the OpenStack cloud with given name does not exist.
     */
    public static @Nonnull JCloudsCloud getByName(@Nonnull String name) throws IllegalArgumentException {
        Cloud cloud = Jenkins.get().clouds.getByName(name);
        if (cloud instanceof JCloudsCloud) return (JCloudsCloud) cloud;
        throw new IllegalArgumentException("'" + name + "' is not an OpenStack cloud but " + cloud);
    }

    @DataBoundConstructor @Restricted(DoNotUse.class)
    public JCloudsCloud(
            final @Nonnull String name,
            final @Nonnull String endPointUrl,
            final boolean ignoreSsl,
            final @CheckForNull String zone,
            final @CheckForNull SlaveOptions slaveOptions,
            final @CheckForNull List<JCloudsSlaveTemplate> templates,
            final @Nonnull String credentialsId
    ) {
        super(Util.fixNull(name).trim());

        this.endPointUrl = Util.fixNull(endPointUrl).trim();
        this.ignoreSsl = TRUE.equals(ignoreSsl);
        this.zone = Util.fixEmptyAndTrim(zone);
        this.credentialId = credentialsId;
        this.slaveOptions = slaveOptions == null ? SlaveOptions.empty() : slaveOptions.eraseDefaults(DescriptorImpl.DEFAULTS);

        this.templates = templates == null ? Collections.emptyList() : Collections.unmodifiableList(templates);

        injectReferenceIntoTemplates();
    }

    @SuppressWarnings({"unused", "deprecation", "ConstantConditions"})
    private Object readResolve() {
        if (retentionTime != null || startTimeout != null || floatingIps != null || instanceCap != null ) {
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
                String tenant = id[0];
                String username = id[1];
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

    public @Nonnull String getEndPointUrl() {
        return endPointUrl;
    }

    public @CheckForNull String getZone() {
        return zone;
    }

    /**
     * Get a queue of templates to be used to provision slaves of label.
     *
     * The queue contains the same template in as many instances as is the number of machines that can be safely
     * provisioned without violating instanceCap constrain.
     */
    private @Nonnull Queue<JCloudsSlaveTemplate> getAvailableTemplateProvider(@CheckForNull Label label, int excessWorkload) {
        final int globalMax = getEffectiveSlaveOptions().getInstanceCap();

        final Queue<JCloudsSlaveTemplate> queue = new ConcurrentLinkedDeque<>();

        List<JCloudsComputer> cloudComputers = JCloudsComputer.getAll().stream().filter(
                it -> name.equals(it.getId().getCloudName())
        ).collect(Collectors.toList());

        int nodeCount = cloudComputers.size();
        if (nodeCount >= globalMax) {
            return queue; // more slaves then declared - no need to query openstack
        }

         // Check the number of current servers
         int serverCount = 0;
         List<Server> runningNodes = new ArrayList<Server>();
         try {
             // get the running nodes
             runningNodes = getOpenstack().getRunningNodes();
 
             serverCount = runningNodes.size();
             if (serverCount >= globalMax) {
                 return queue; // more servers than needed - no need to proceed any further
             }
           }  catch (JCloudsCloud.LoginFailure ex) {
            LOGGER.log(Level.WARNING, "Login failure: " + ex.getMessage());
            return queue;
        } 

       
        int globalCapacity = globalMax - Math.max(nodeCount, serverCount);
        assert globalCapacity > 0;

        for (JCloudsSlaveTemplate t : templates) {
            if (t.canProvision(label)) {
                SlaveOptions opts = t.getEffectiveSlaveOptions();
                final int templateMax = opts.getInstanceCap();
                long templateNodeCount = Math.max(
                        cloudComputers.stream().filter(it -> t.getName().equals(it.getId().getTemplateName())).count(),
                        runningNodes.stream().filter(t::hasProvisioned).count()
                );
                if (templateNodeCount >= templateMax) continue; // Exceeded

                long templateCapacity = templateMax - templateNodeCount;
                assert templateCapacity > 0;

                for (int i = 0; i < templateCapacity; i++) {
                    int size = queue.size();
                    if (size >= globalCapacity || size >= excessWorkload) return queue;

                    queue.add(t);
                }
            }
        }

        return queue;
    }

    @Override
    public Collection<NodeProvisioner.PlannedNode> provision(Label label, int excessWorkload) {
        Queue<JCloudsSlaveTemplate> templateProvider = getAvailableTemplateProvider(label, excessWorkload);

        List<PlannedNode> plannedNodeList = new ArrayList<>();
        while (excessWorkload > 0 && !Jenkins.get().isQuietingDown() && !Jenkins.get().isTerminating()) {

            final JCloudsSlaveTemplate template = templateProvider.poll();
            if (template == null) {
                // two cases 
                // cloud authentication issue or Instance cap exceeded 
                try {
                    // try to authenticate 
                    getOpenstack();
                    // if okay, then the problem is related to the instance cap 
                    LOGGER.info("Instance cap exceeded for cloud " + name + " while provisioning for label " + label);
                    break;
                  } catch (JCloudsCloud.LoginFailure ex) {
                   // no need to log here because it has already been logged in the getAvailableTemplates method. 
                   break; 
               } 
                
            }

            LOGGER.fine("Provisioning slave for " + label + " from template " + template.getName());

            int numExecutors = template.getEffectiveSlaveOptions().getNumExecutors();

            ProvisioningActivity.Id id = new ProvisioningActivity.Id(this.name, template.getName());
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
            if (t.getName().equals(name))
                return t;
        return null;
    }

    /**
     * Provisions a new node manually (by clicking a button in the computer list).
     *
     * @param req  {@link StaplerRequest}
     * @param rsp  {@link StaplerResponse}
     * @param name Name of the template to provision
     */
    @Restricted(NoExternalUse.class)
    @RequirePOST
    public void doProvision(StaplerRequest req, StaplerResponse rsp, @QueryParameter String name) throws IOException {

        // Temporary workaround for https://issues.jenkins-ci.org/browse/JENKINS-37616
        // Using Item.CONFIGURE as users authorized to do so can provision via job execution.
        // Once the plugins starts to depend on core new enough, we can use Cloud.PROVISION again.
        if (!hasPermission(Item.CONFIGURE) && !hasPermission(Cloud.PROVISION)) {
            checkPermission(Cloud.PROVISION);
        }

        if (rsp.getContentType() == null) { // test hack for JenkinsRule#executeOnServer
            rsp.setContentType("text/xml");
        }

        if (name == null) {
            sendPlaintextError("The slave template name query parameter is missing", rsp);
            return;
        }
        JCloudsSlaveTemplate t = getTemplate(name);
        if (t == null) {
            sendPlaintextError("No such slave template with name : " + name, rsp);
            return;
        }

        List<Server> nodes = getOpenstack().getRunningNodes();
        final int global = nodes.size();

        int globalCap = getEffectiveSlaveOptions().getInstanceCap();
        if (global >= globalCap) {
            String msg = String.format("Instance cap of %s is now reached: %d", this.name, globalCap);
            sendPlaintextError(msg, rsp);
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
            sendPlaintextError(msg, rsp);
            return;
        }

        try {
            provisionAsynchronouslyNotToBlockTheRequestThread(t);
            rsp.getWriter().println("<ok>Provisioning started</ok>");
        } catch (Throwable ex) {
            sendPlaintextError(ex.getMessage(), rsp);
        }
    }

    private void provisionAsynchronouslyNotToBlockTheRequestThread(JCloudsSlaveTemplate t) throws Throwable {
        Authentication auth = Jenkins.getAuthentication();
        Callable<Void> performProvisioning = () -> {
            // Impersonate current identity inside the worker thread not to lose the owner info
            try (ACLContext ignored = ACL.as(auth)) {
                try {
                    provisionSlaveExplicitly(t);
                    return null;
                } catch (Throwable ex) {
                    LOGGER.log(Level.WARNING, "Provisioning failed", ex);
                    throw ex;
                }
            }
        };
        ScheduledFuture<?> schedule = Timer.get().schedule(performProvisioning, 0, TimeUnit.SECONDS);
        // Wait for fast failures and present them to user on best effort basis
        try {
            schedule.get(3, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            // Fast failure
            throw e.getCause();
        } catch (TimeoutException e) {
            // Expected - success or slow failure
        }
    }

    // This is served by AJAX so we are stripping the html
    private static void sendPlaintextError(String message, StaplerResponse rsp) throws IOException {
        rsp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        PrintWriter response = rsp.getWriter();
        response.println("<error>" + message + "</error>");
    }

    /**
     * Provision slave out of {@link NodeProvisioner} context.
     */
    @Restricted(NoExternalUse.class)
    /*package*/ @Nonnull JCloudsSlave provisionSlaveExplicitly(@Nonnull JCloudsSlaveTemplate template) throws IOException, Openstack.ActionFailed{
        CloudStatistics.ProvisioningListener provisioningListener = CloudStatistics.ProvisioningListener.get();
        ProvisioningActivity.Id id = new ProvisioningActivity.Id(name, template.getName());

        JCloudsSlave node;
        try {
            provisioningListener.onStarted(id);
            node = template.provisionSlave(this, id);
            provisioningListener.onComplete(id, node);
        } catch (Throwable ex) {
            provisioningListener.onFailure(id, ex);
            throw ex;
        }
        Jenkins.get().addNode(node);
        return node;
    }

    /**
     * Get connected OpenStack client wrapper.
     *
     * @throws LoginFailure In case the details are incomplete or rejected by OpenStack.
     */
    @Restricted(NoExternalUse.class)
    public @Nonnull Openstack getOpenstack() throws LoginFailure {
        try {
            OpenstackCredential credential = OpenstackCredentials.getCredential(getCredentialsId());
            if (credential == null) {
                throw new LoginFailure("No credentials found for cloud " + name + " (id=" + getCredentialsId() + ")");
            }
            return Openstack.Factory.get(endPointUrl, ignoreSsl, credential, zone);
        } catch (AuthenticationException ex) {
            throw new LoginFailure(name, ex);
        } catch (FormValidation ex) {
            throw new LoginFailure(name, ex);
        }
    }

    public String getCredentialsId() {
        return credentialId;
    }

    @Restricted(DoNotUse.class) // Jelly
    public boolean getIgnoreSsl() {
        return ignoreSsl;
    }

    @Override
    public String toString() {
        return String.format("OpenStack cloud %s for %s", name, endPointUrl);
    }

    @Extension
    @Symbol("openstack")
    public static class DescriptorImpl extends Descriptor<Cloud> {

        // Plugin default slave attributes - the root of all overriding
        private static final SlaveOptions DEFAULTS = SlaveOptions.builder()
                .instanceCap(10)
                .instancesMin(0)
                .retentionTime(30)
                .startTimeout(600000)
                .numExecutors(1)
                .fsRoot("/jenkins")
                .securityGroups("default")
                .configDrive(false)
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
        @RequirePOST
        public FormValidation doCheckName(@QueryParameter String value) {
            try {
                Jenkins.checkGoodName(value);
                return FormValidation.ok();
            } catch (Failure ex) {
                return FormValidation.error(ex.getMessage());
            }
        }

        @Restricted(DoNotUse.class)
        @RequirePOST
        public FormValidation doTestConnection(
                @QueryParameter boolean ignoreSsl,
                @QueryParameter String credentialsId,
                @QueryParameter String endPointUrl,
                @QueryParameter String zone
        ) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            try {
                OpenstackCredential openstackCredential = OpenstackCredentials.getCredential(credentialsId);
                if (openstackCredential == null) throw FormValidation.error("No credential found for " + credentialsId);
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
        @RequirePOST
        public FormValidation doCheckEndPointUrl(@QueryParameter String value) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            if (Util.fixEmpty(value) == null) return FormValidation.validateRequired(value);

            try {
                new URL(value);
            } catch (MalformedURLException ex) {
                return FormValidation.error(ex, "The endpoint must be URL");
            }
            return FormValidation.ok();
        }

        @Restricted(DoNotUse.class)
        @RequirePOST
        public ListBoxModel doFillCredentialsIdItems() {
            Jenkins jenkins = Jenkins.get();
            jenkins.checkPermission(Jenkins.ADMINISTER);

            return new StandardListBoxModel()
                    .includeMatchingAs(ACL.SYSTEM, jenkins, StandardCredentials.class,
                            Collections.<DomainRequirement>emptyList(),
                            CredentialsMatchers.instanceOf(OpenstackCredential.class))
                    .includeEmptyValue();
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

    /*package*/ 
    public static final class LoginFailure extends RuntimeException {

        private static final long serialVersionUID = 4085466675398031930L;

        private LoginFailure(String name, FormValidation ex) {
            super("Openstack authentication invalid fro cloud " + name + ": " + ex.getMessage(), ex);
        }

        private LoginFailure(String name, AuthenticationException ex) {
            super("Failure to authenticate for cloud " + name + ": " + ex.toString());
        }

        public LoginFailure(String msg) {
            super(msg);
        }
    }
}
