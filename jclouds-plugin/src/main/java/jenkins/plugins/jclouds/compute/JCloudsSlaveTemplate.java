package jenkins.plugins.jclouds.compute;

import static shaded.com.google.common.base.Throwables.propagate;
import static shaded.com.google.common.collect.Iterables.getOnlyElement;
import static shaded.com.google.common.collect.Lists.newArrayList;
import static java.util.Collections.sort;

import java.io.IOException;
import java.io.StringReader;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import hudson.Extension;
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

import org.jclouds.compute.ComputeService;
import org.jclouds.compute.RunNodesException;
import org.jclouds.compute.domain.Hardware;
import org.jclouds.compute.domain.Image;
import org.jclouds.compute.domain.NodeMetadata;
import org.jclouds.compute.domain.Template;
import org.jclouds.compute.domain.TemplateBuilder;
import org.jclouds.compute.options.TemplateOptions;
import org.jclouds.domain.Location;
import org.jclouds.domain.LoginCredentials;
import org.jclouds.openstack.neutron.v2.domain.Network;
import org.jclouds.openstack.neutron.v2.features.NetworkApi;
import org.jclouds.openstack.nova.v2_0.compute.options.NovaTemplateOptions;
import org.jclouds.predicates.validators.DnsNameValidator;
import org.jclouds.scriptbuilder.domain.Statements;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import au.com.bytecode.opencsv.CSVReader;
import shaded.com.google.common.base.Strings;
import shaded.com.google.common.base.Supplier;
import shaded.com.google.common.collect.ImmutableMap;

import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernameListBoxModel;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.jenkins.plugins.sshcredentials.SSHAuthenticator;

import com.trilead.ssh2.Connection;

/**
 * @author Marat Mavlyutov
 */
public class JCloudsSlaveTemplate implements Describable<JCloudsSlaveTemplate>, Supplier<NodeMetadata> {

    private static final Logger LOGGER = Logger.getLogger(JCloudsSlaveTemplate.class.getName());
    private static final char SEPARATOR_CHAR = ',';

    public final String name;
    public final String imageId;
    public final String imageNameRegex;
    public final String hardwareId;
    public final String labelString;
    public final String locationId;
    public final String initScript;
    public final String numExecutors;
    public final boolean stopOnTerminate;
    private final String jvmOptions;
    private final String fsRoot;
    public final boolean installPrivateKey;
    public final int overrideRetentionTime;
    public final int spoolDelayMs;
    private final Object delayLockObject = new Object();
    public final String keyPairName;
    public final String networkId;
    public final String securityGroups;
    public final String credentialsId;

    private transient Set<LabelAtom> labelSet;

    protected transient JCloudsCloud cloud;

    @DataBoundConstructor
    public JCloudsSlaveTemplate(final String name, final String imageId, final String imageNameRegex, final String hardwareId,
                                final String locationId, final String labelString,
                                final String initScript, final String numExecutors, final boolean stopOnTerminate, final String jvmOptions,
                                final String fsRoot, final boolean installPrivateKey, final int overrideRetentionTime, final int spoolDelayMs,
                                final String keyPairName, final String networkId, final String securityGroups, final String credentialsId) {

        this.name = Util.fixEmptyAndTrim(name);
        this.imageId = Util.fixEmptyAndTrim(imageId);
        this.imageNameRegex = Util.fixEmptyAndTrim(imageNameRegex);
        this.hardwareId = Util.fixEmptyAndTrim(hardwareId);
        this.locationId = Util.fixEmptyAndTrim(locationId);
        this.labelString = Util.fixNull(labelString);
        this.initScript = Util.fixNull(initScript);
        this.numExecutors = Util.fixNull(numExecutors);
        this.jvmOptions = Util.fixEmptyAndTrim(jvmOptions);
        this.stopOnTerminate = stopOnTerminate;

        this.fsRoot = Util.fixEmptyAndTrim(fsRoot);
        this.installPrivateKey = installPrivateKey;
        this.overrideRetentionTime = overrideRetentionTime;
        this.spoolDelayMs = spoolDelayMs;
        this.keyPairName = keyPairName;
        this.networkId = networkId;
        this.securityGroups = securityGroups;
        this.credentialsId = credentialsId;
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
        NodeMetadata nodeMetadata = get();

        try {
            return new JCloudsSlave(getCloud().getDisplayName(), getFsRoot(), nodeMetadata, labelString,
                    numExecutors, stopOnTerminate, overrideRetentionTime, getJvmOptions(), credentialsId);
        } catch (Descriptor.FormException e) {
            throw new AssertionError("Invalid configuration " + e.getMessage());
        }
    }

    @Override
    public NodeMetadata get() {
        LOGGER.info("Provisioning new jclouds node");
        ImmutableMap<String, String> userMetadata = ImmutableMap.of("Name", name);
        TemplateBuilder templateBuilder = getCloud().getCompute().templateBuilder();
        if (!Strings.isNullOrEmpty(imageId)) {
            LOGGER.info("Setting image id to " + imageId);
            templateBuilder.imageId(imageId);
        } else if (!Strings.isNullOrEmpty(imageNameRegex)) {
            LOGGER.info("Setting image name regex to " + imageNameRegex);
            templateBuilder.imageNameMatches(imageNameRegex);
        }
        if (!Strings.isNullOrEmpty((hardwareId))) {
            LOGGER.info("Setting hardware Id to " + hardwareId);
            templateBuilder.hardwareId(hardwareId);
        }
        if (!Strings.isNullOrEmpty(locationId)) {
            LOGGER.info("Setting location Id to " + locationId);
            templateBuilder.locationId(locationId);
        }

        Template template = templateBuilder.build();
        TemplateOptions options = template.getOptions();

        if (!Strings.isNullOrEmpty(networkId)) {
            LOGGER.info("Setting network to " + networkId);
            options.networks(networkId);
        }

        if (!Strings.isNullOrEmpty(securityGroups)) {
            LOGGER.info("Setting security groups to " + securityGroups);
            options.securityGroups(csvToArray(securityGroups));
        }

        if (!Strings.isNullOrEmpty((keyPairName)) && options instanceof NovaTemplateOptions) {
            LOGGER.info("Setting keyPairName to " + keyPairName);
            options.as(NovaTemplateOptions.class).keyPairName(keyPairName);
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
            throw new AssertionError("Invalid configuration. Cant use neither privateKey auth not password");
        }
        options.overrideLoginCredentials(loginCredentials);

        if (installPrivateKey && credentials instanceof SSHUserPrivateKey) {
            options.installPrivateKey(((BasicSSHUserPrivateKey) credentials).getPrivateKey());
        }

        if (spoolDelayMs > 0) {
            // (JENKINS-15970) Add optional delay before spooling. Author: Adam Rofer
            synchronized (delayLockObject) {
                LOGGER.info("Delaying " + spoolDelayMs + " milliseconds. Current ms -> " + System.currentTimeMillis());
                try {
                    Thread.sleep(spoolDelayMs);
                } catch (InterruptedException e) {
                }
            }
        }

        options.inboundPorts(22).userMetadata(userMetadata);
        options.runScript(Statements.exec(this.initScript));

        NodeMetadata nodeMetadata = null;
        try {
            nodeMetadata = getOnlyElement(getCloud().getCompute().createNodesInGroup(name, 1, template));
        } catch (RunNodesException e) {
            throw destroyBadNodesAndPropagate(e);
        }

        // Check if nodeMetadata is null and throw
        return nodeMetadata;
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

        public ListBoxModel doFillHardwareIdItems(@RelativePath("..") @QueryParameter String identity,
                                                  @RelativePath("..") @QueryParameter String credential,
                                                  @RelativePath("..") @QueryParameter String endPointUrl,
                                                  @RelativePath("..") @QueryParameter String zones) {

            ListBoxModel m = new ListBoxModel();

            if (Strings.isNullOrEmpty(identity)) {
                LOGGER.warning("identity is null or empty");
                return m;
            }
            if (Strings.isNullOrEmpty(credential)) {
                LOGGER.warning("credential is null or empty");
                return m;
            }

            identity = Util.fixEmptyAndTrim(identity);
            credential = Secret.fromString(credential).getPlainText();
            endPointUrl = Util.fixEmptyAndTrim(endPointUrl);

            ComputeService computeService = null;
            m.add("None specified", "");
            try {
                // TODO: endpoint is ignored
                computeService = JCloudsCloud.ctx(identity, credential, endPointUrl, zones).getComputeService();

                ArrayList<Hardware> hws = newArrayList(computeService.listHardwareProfiles());
                sort(hws);

                for (Hardware hardware : hws) {
                    m.add(String.format("%s (%s)", hardware.getId(), hardware.getName()), hardware.getId());
                }
            } catch (Exception ex) {
                LOGGER.log(Level.SEVERE, ex.getMessage(), ex);
            } finally {
                if (computeService != null) {
                    computeService.getContext().close();
                }
            }

            return m;
        }

        public ListBoxModel doFillImageIdItems(@RelativePath("..") @QueryParameter String identity,
                                               @RelativePath("..") @QueryParameter String credential,
                                               @RelativePath("..") @QueryParameter String endPointUrl,
                                               @RelativePath("..") @QueryParameter String zones) {

            ListBoxModel m = new ListBoxModel();

            if (Strings.isNullOrEmpty(identity)) {
                LOGGER.warning("identity is null or empty");
                return m;
            }
            if (Strings.isNullOrEmpty(credential)) {
                LOGGER.warning("credential is null or empty");
                return m;
            }

            identity = Util.fixEmptyAndTrim(identity);
            credential = Secret.fromString(credential).getPlainText();
            endPointUrl = Util.fixEmptyAndTrim(endPointUrl);

            ComputeService computeService = null;
            m.add("None specified", "");
            try {
                computeService = JCloudsCloud.ctx(identity, credential, endPointUrl, zones).getComputeService();

                ArrayList<Image> hws = newArrayList(computeService.listImages());
                sort(hws, new Comparator<Image>() {
                    @Override
                    public int compare(Image o1, Image o2) {
                        return o1.getName().compareTo(o2.getName());
                    }
                });

                for (Image image : hws) {
                    m.add(String.format("%s (%s)", image.getName(), image.getId()), image.getId());
                }
            } catch (Exception ex) {
                LOGGER.log(Level.SEVERE, ex.getMessage(), ex);
            } finally {
                if (computeService != null) {
                    computeService.getContext().close();
                }
            }

            return m;
        }

        public ListBoxModel doFillLocationIdItems(@RelativePath("..") @QueryParameter String identity,
                                                  @RelativePath("..") @QueryParameter String credential,
                                                  @RelativePath("..") @QueryParameter String endPointUrl,
                                                  @RelativePath("..") @QueryParameter String zones) {

            ListBoxModel m = new ListBoxModel();

            if (Strings.isNullOrEmpty(identity)) {
                LOGGER.warning("identity is null or empty");
                return m;
            }
            if (Strings.isNullOrEmpty(credential)) {
                LOGGER.warning("credential is null or empty");
                return m;
            }

            identity = Util.fixEmptyAndTrim(identity);
            credential = Secret.fromString(credential).getPlainText();
            endPointUrl = Util.fixEmptyAndTrim(endPointUrl);

            ComputeService computeService = null;
            m.add("None specified", "");
            try {
                computeService = JCloudsCloud.ctx(identity, credential, endPointUrl, zones).getComputeService();

                ArrayList<Location> locations = newArrayList(computeService.listAssignableLocations());
                sort(locations, new Comparator<Location>() {
                    @Override
                    public int compare(Location o1, Location o2) {
                        return o1.getId().compareTo(o2.getId());
                    }
                });

                for (Location location : locations) {
                    m.add(String.format("%s (%s)", location.getId(), location.getDescription()), location.getId());
                }
            } catch (Exception ex) {
                LOGGER.log(Level.SEVERE, ex.getMessage(), ex);
            } finally {
                if (computeService != null) {
                    computeService.getContext().close();
                }
            }

            return m;
        }

        public ListBoxModel doFillNetworkIdItems(@RelativePath("..") @QueryParameter String identity,
                                                 @RelativePath("..") @QueryParameter String credential,
                                                 @RelativePath("..") @QueryParameter String endPointUrl,
                                                 @RelativePath("..") @QueryParameter String zones) {

            ListBoxModel m = new ListBoxModel();

            if (Strings.isNullOrEmpty(identity)) {
                LOGGER.warning("identity is null or empty");
                return m;
            }
            if (Strings.isNullOrEmpty(credential)) {
                LOGGER.warning("credential is null or empty");
                return m;
            }

            identity = Util.fixEmptyAndTrim(identity);
            credential = Secret.fromString(credential).getPlainText();
            endPointUrl = Util.fixEmptyAndTrim(endPointUrl);

            NetworkApi networkApi = null;
            m.add("None specified", "");
            try {

                networkApi = JCloudsCloud.na(identity, credential, endPointUrl).getNetworkApi(zones);

                List<? extends Network> networks = networkApi.list().concat().toSortedList(new Comparator<Network>() {
                    @Override
                    public int compare(Network o1, Network o2) {
                        return o1.getName().compareTo(o2.getName());
                    }
                });

                for (Network network : networks) {
                    m.add(String.format("%s (%s)", network.getName(), network.getId()), network.getId());
                }
            } catch (Exception ex) {
                LOGGER.log(Level.SEVERE, ex.getMessage(), ex);
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
            try {
                if (Integer.parseInt(value) == -1) {
                    return FormValidation.ok();
                }
            } catch (NumberFormatException e) {
            }
            return FormValidation.validateNonNegativeInteger(value);
        }

        public FormValidation doCheckSpoolDelayMs(@QueryParameter String value) {
            return FormValidation.validateNonNegativeInteger(value);
        }
    }
}
