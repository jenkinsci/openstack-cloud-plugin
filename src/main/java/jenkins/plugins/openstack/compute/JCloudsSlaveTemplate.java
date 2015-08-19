package jenkins.plugins.openstack.compute;

import static com.google.common.base.Throwables.propagate;
import static com.google.common.collect.Iterables.getOnlyElement;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;

import hudson.Extension;
import hudson.ExtensionList;
import hudson.RelativePath;
import hudson.Util;
import hudson.model.Describable;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.ItemGroup;
import hudson.model.TaskListener;
import hudson.model.labels.LabelAtom;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.security.ACL;
import hudson.security.AccessControlled;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import jenkins.model.Jenkins;
import jenkins.plugins.openstack.compute.internal.Openstack;

import org.jclouds.compute.RunNodesException;
import org.jclouds.compute.domain.NodeMetadata;
import org.jclouds.compute.domain.Template;
import org.jclouds.compute.domain.TemplateBuilder;
import org.jclouds.domain.LoginCredentials;
import org.jclouds.openstack.nova.v2_0.compute.options.NovaTemplateOptions;
import org.jclouds.predicates.validators.DnsNameValidator;
import org.jenkinsci.lib.configprovider.ConfigProvider;
import org.jenkinsci.lib.configprovider.model.Config;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.openstack4j.model.compute.Flavor;
import org.openstack4j.model.compute.Server;
import org.openstack4j.model.image.Image;

import au.com.bytecode.opencsv.CSVReader;
import com.google.common.base.Strings;
import com.google.common.base.Supplier;

import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernameListBoxModel;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.jenkins.plugins.sshcredentials.SSHAuthenticator;

import com.trilead.ssh2.Connection;

/**
 * @author Vijay Kiran
 */
public class JCloudsSlaveTemplate implements Describable<JCloudsSlaveTemplate>, Supplier<Server> {

    private static final Logger LOGGER = Logger.getLogger(JCloudsSlaveTemplate.class.getName());
    private static final char SEPARATOR_CHAR = ',';

    public final String name;
    public final String imageId;
    public final String hardwareId;
    public final String labelString;
    public final String userDataId;
    public final String numExecutors;
    private final String jvmOptions;
    private final String fsRoot;
    public final boolean installPrivateKey;
    public final int overrideRetentionTime;
    public final String keyPairName;
    public final String networkId;
    public final String securityGroups;
    public final String credentialsId;
    public final JCloudsCloud.SlaveType slaveType;
    public final String availabilityZone;

    private transient Set<LabelAtom> labelSet;

    protected transient JCloudsCloud cloud;

    @DataBoundConstructor
    public JCloudsSlaveTemplate(final String name, final String imageId, final String hardwareId,
                                final String labelString, final String userDataId, final String numExecutors,
                                final String jvmOptions, final String fsRoot, final boolean installPrivateKey,
                                final int overrideRetentionTime, final String keyPairName, final String networkId,
                                final String securityGroups, final String credentialsId, final JCloudsCloud.SlaveType slaveType, final String availabilityZone) {

        this.name = Util.fixEmptyAndTrim(name);
        this.imageId = Util.fixEmptyAndTrim(imageId);
        this.hardwareId = Util.fixEmptyAndTrim(hardwareId);
        this.labelString = Util.fixNull(labelString);
        this.numExecutors = Util.fixNull(numExecutors);
        this.jvmOptions = Util.fixEmptyAndTrim(jvmOptions);
        this.userDataId = userDataId;

        this.fsRoot = Util.fixEmptyAndTrim(fsRoot);
        this.installPrivateKey = installPrivateKey;
        this.overrideRetentionTime = overrideRetentionTime;
        this.keyPairName = keyPairName;
        this.networkId = networkId;
        this.securityGroups = securityGroups;
        this.credentialsId = credentialsId;
        this.slaveType = slaveType;
        this.availabilityZone = availabilityZone;

        readResolve();
    }

    public JCloudsCloud getCloud() {
        return cloud;
    }

    /**
     * Initializes data structure that we don't persist.
     */
    protected Object readResolve() {
        labelSet = Label.parse(labelString);
        return this;
    }

    public String getJvmOptions() {
        if (jvmOptions == null) {
            return "";
        } else {
            return jvmOptions;
        }
    }

    public int getNumExecutors() {
        return Util.tryParseNumber(numExecutors, 1).intValue();
    }

    public String getFsRoot() {
        if (fsRoot == null || fsRoot.equals("")) {
            return "/jenkins";
        } else {
            return fsRoot;
        }
    }

    public Set<LabelAtom> getLabelSet() {
        return labelSet;
    }

    public JCloudsSlave provisionSlave(TaskListener listener) throws IOException {
        Server nodeMetadata = get();

        try {
            return new JCloudsSlave(getCloud().getDisplayName(), getFsRoot(), nodeMetadata, labelString,
                    numExecutors, overrideRetentionTime, getJvmOptions(), credentialsId, slaveType);
        } catch (Descriptor.FormException e) {
            throw new AssertionError("Invalid configuration " + e.getMessage());
        }
    }

    // TODO refactor
    @Override
    public Server get() {
        final String nodeName = name + "-" + System.currentTimeMillis() % 1000;
        LOGGER.info("Provisioning new openstack node " + nodeName);

        TemplateBuilder templateBuilder = getCloud().getCompute().templateBuilder();

        if (!Strings.isNullOrEmpty(imageId)) {
            LOGGER.info("Setting image id to " + imageId);
            templateBuilder.imageId(imageId);
        }
        if (!Strings.isNullOrEmpty((hardwareId))) {
            LOGGER.info("Setting hardware Id to " + hardwareId);
            templateBuilder.hardwareId(hardwareId);
        }

        Template template = templateBuilder.build();
        NovaTemplateOptions options = template.getOptions().as(NovaTemplateOptions.class);

        // Ensure predictable node name so we can inject it into user data
        options.nodeNames(Arrays.asList(nodeName));

        if (!Strings.isNullOrEmpty(networkId)) {
            LOGGER.info("Setting network to " + networkId);
            options.networks(networkId);
        }

        if (!Strings.isNullOrEmpty(securityGroups)) {
            LOGGER.info("Setting security groups to " + securityGroups);
            options.securityGroups(csvToArray(securityGroups));
        }

        if (cloud.isFloatingIps()) {
            LOGGER.info("Asking for floating IP");
            options.as(NovaTemplateOptions.class).autoAssignFloatingIp(true);
        }

        if (!Strings.isNullOrEmpty(keyPairName)) {
            LOGGER.info("Setting keyPairName to " + keyPairName);
            options.keyPairName(keyPairName);
        }

        if (!Strings.isNullOrEmpty(availabilityZone)) {
            LOGGER.info("Setting availabilityZone to " + availabilityZone);
            options.availabilityZone(availabilityZone);
        }

        StandardUsernameCredentials credentials = SSHLauncher.lookupSystemCredentials(credentialsId);
        LoginCredentials loginCredentials = null;

        if (credentials instanceof StandardUsernamePasswordCredentials) {
            loginCredentials = LoginCredentials.builder()
                .user(credentials.getUsername())
                .password(((StandardUsernamePasswordCredentials) credentials).getPassword().getPlainText())
                .build();
        } else if (credentials instanceof SSHUserPrivateKey) {
            loginCredentials = LoginCredentials.builder().
                user(credentials.getUsername()).
                privateKey(((BasicSSHUserPrivateKey) credentials).getPrivateKey()).build();
        } else {
            throw new AssertionError("Unknown credential type configured: " + credentials);
        }
        options.overrideLoginCredentials(loginCredentials);

        if (installPrivateKey && credentials instanceof SSHUserPrivateKey) {
            options.installPrivateKey(((BasicSSHUserPrivateKey) credentials).getPrivateKey());
        }

        ExtensionList<ConfigProvider> providers = ConfigProvider.all();
        UserDataConfig.UserDataConfigProvider myProvider = providers.get(UserDataConfig.UserDataConfigProvider.class);
        Config userData = myProvider.getConfigById(userDataId);
        if (userData != null && !userData.content.isEmpty()) {
            HashMap<String, String> vars = new HashMap<String, String>();
            String rootUrl = Jenkins.getInstance().getRootUrl();
            vars.put("JENKINS_URL", rootUrl);
            vars.put("SLAVE_JAR_URL", rootUrl + "jnlpJars/slave.jar");
            vars.put("SLAVE_JNLP_URL", rootUrl + "computer/" + nodeName + "/slave-agent.jnlp");
            String content = Util.replaceMacro(userData.content, vars);
            LOGGER.info("Sending user-data:\n" + content);
            options.as(NovaTemplateOptions.class).userData(content.getBytes(StandardCharsets.UTF_8));
        }

        NodeMetadata nodeMetadata = null;
        try {
            nodeMetadata = getOnlyElement(getCloud().getCompute().createNodesInGroup(name, 1, template));
        } catch (RunNodesException e) {
            throw destroyBadNodesAndPropagate(e);
        }
        
        return cloud.getOpenstack().getServerById(nodeMetadata.getId());
    }

    private RuntimeException destroyBadNodesAndPropagate(RunNodesException e) {
        for (Map.Entry<? extends NodeMetadata, ? extends Throwable> nodeError : e.getNodeErrors().entrySet()) {
            getCloud().getCompute().destroyNode(nodeError.getKey().getId());
        }
        throw propagate(e);
    }

    private static String[] csvToArray(final String csv) {
        try {
            final CSVReader reader = new CSVReader(new StringReader(csv), SEPARATOR_CHAR);
            final String[] line = reader.readNext();
            return (line != null) ? line : new String[0];
        } catch (Exception e) {
            return new String[0];
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Descriptor<JCloudsSlaveTemplate> getDescriptor() {
        return Jenkins.getInstance().getDescriptor(getClass());
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<JCloudsSlaveTemplate> {

        @Override
        public String getDisplayName() {
            return null;
        }

        public FormValidation doCheckName(@QueryParameter String value) {
            try {
                new DnsNameValidator(1, 80).validate(value);
                return FormValidation.ok();
            } catch (Exception e) {
                return FormValidation.error(e.getMessage());
            }
        }

        public FormValidation doCheckNumExecutors(@QueryParameter String value) {
            return FormValidation.validatePositiveInteger(value);
        }

        public ListBoxModel doFillSlaveTypeItems() {
            ListBoxModel items = new ListBoxModel();
            items.add("SSH", "SSH");
            items.add("JNLP", "JNLP");

            return items;
        }

        @Restricted(DoNotUse.class)
        public ListBoxModel doFillHardwareIdItems(@QueryParameter String hardwareId,
                                                  @RelativePath("..") @QueryParameter String endPointUrl,
                                                  @RelativePath("..") @QueryParameter String identity,
                                                  @RelativePath("..") @QueryParameter String credential,
                                                  @RelativePath("..") @QueryParameter String zone
        ) throws FormValidation {

            ListBoxModel m = new ListBoxModel();
            m.add("None specified", "");

            final Openstack openstack = JCloudsCloud.getOpenstack(endPointUrl, identity, credential, zone);
            try {
                for (Flavor flavor : openstack.getSortedFlavors()) {
                    m.add(String.format("%s (%s)", flavor.getName(), flavor.getId()), flavor.getId());
                }
            } catch (Exception ex) {
                LOGGER.log(Level.SEVERE, ex.getMessage(), ex);
                if(Util.fixEmptyAndTrim(hardwareId) != null) {m.add(hardwareId);}
            }

            return m;
        }

        @Restricted(DoNotUse.class)
        public ListBoxModel doFillImageIdItems(@QueryParameter String imageId,
                                               @RelativePath("..") @QueryParameter String endPointUrl,
                                               @RelativePath("..") @QueryParameter String identity,
                                               @RelativePath("..") @QueryParameter String credential,
                                               @RelativePath("..") @QueryParameter String zone
        ) throws FormValidation {

            ListBoxModel m = new ListBoxModel();
            m.add("None specified", "");

            final Openstack openstack = JCloudsCloud.getOpenstack(endPointUrl, identity, credential, zone);
            try {
                for (Image image : openstack.getSortedImages()) {
                    m.add(String.format("%s (%s)", image.getName(), image.getId()), image.getId());
                }
            } catch (Exception ex) {
                LOGGER.log(Level.SEVERE, ex.getMessage(), ex);
                if(Util.fixEmptyAndTrim(imageId) != null) {m.add(imageId);}
            }

            return m;
        }

        @Restricted(DoNotUse.class)
        public ListBoxModel doFillNetworkIdItems(@QueryParameter String networkId,
                                                 @RelativePath("..") @QueryParameter String endPointUrl,
                                                 @RelativePath("..") @QueryParameter String identity,
                                                 @RelativePath("..") @QueryParameter String credential,
                                                 @RelativePath("..") @QueryParameter String zone
        ) throws FormValidation {

            ListBoxModel m = new ListBoxModel();
            m.add("None specified", "");

            Openstack openstack = JCloudsCloud.getOpenstack(endPointUrl, identity, credential, zone);
            try {
                for (org.openstack4j.model.network.Network network: openstack.getSortedNetworks()) {
                    m.add(String.format("%s (%s)", network.getName(), network.getId()), network.getId());
                }
            } catch (Exception ex) {
                LOGGER.log(Level.SEVERE, ex.getMessage(), ex);
                if(Util.fixEmptyAndTrim(networkId) != null) {m.add(networkId);}
            }

            return m;
        }

        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath ItemGroup context) {
            if (!(context instanceof AccessControlled ? (AccessControlled) context : Jenkins.getInstance()).hasPermission(Computer.CONFIGURE)) {
                return new ListBoxModel();
            }
            return new StandardUsernameListBoxModel().withMatching(SSHAuthenticator.matcher(Connection.class),
                    CredentialsProvider.lookupCredentials(StandardUsernameCredentials.class, context,
                            ACL.SYSTEM, SSHLauncher.SSH_SCHEME));
        }

        public FormValidation doCheckOverrideRetentionTime(@QueryParameter String value) {
            try {if (Integer.parseInt(value) == -1) {return FormValidation.ok();}
                } catch (NumberFormatException e) {}
            return FormValidation.validateNonNegativeInteger(value);
        }

        public ListBoxModel doFillUserDataIdItems() {

            ListBoxModel m = new ListBoxModel();
            m.add("None specified", "");

            ConfigProvider provider = getConfigProvider();
            for(Config config : provider.getAllConfigs()) {
                m.add(config.name, config.id);
            }

            return m;
        }

        private ConfigProvider getConfigProvider() {
            ExtensionList<ConfigProvider> providers = ConfigProvider.all();
            return providers.get(UserDataConfig.UserDataConfigProvider.class);
        }
    }
}
