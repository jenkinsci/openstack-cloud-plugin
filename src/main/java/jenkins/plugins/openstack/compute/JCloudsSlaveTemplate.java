package jenkins.plugins.openstack.compute;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import hudson.remoting.Base64;
import org.jenkinsci.lib.configprovider.ConfigProvider;
import org.jenkinsci.lib.configprovider.model.Config;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.openstack4j.api.Builders;
import org.openstack4j.model.compute.Server;
import org.openstack4j.model.compute.builder.ServerCreateBuilder;

import com.google.common.base.Strings;
import com.google.common.base.Supplier;

import au.com.bytecode.opencsv.CSVReader;
import hudson.Extension;
import hudson.Util;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.TaskListener;
import hudson.model.labels.LabelAtom;
import hudson.util.FormValidation;
import jenkins.model.Jenkins;
import jenkins.plugins.openstack.compute.internal.Openstack;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

/**
 * @author Vijay Kiran
 */
public class JCloudsSlaveTemplate implements Describable<JCloudsSlaveTemplate>, SlaveOptions.Holder {

    private static final Logger LOGGER = Logger.getLogger(JCloudsSlaveTemplate.class.getName());
    private static final char SEPARATOR_CHAR = ',';
    /*package*/ static final String OPENSTACK_TEMPLATE_NAME_KEY = "jenkins-template-name";

    public final String name;
    public final String labelString;
    private /*final*/ SlaveOptions slaveOptions;

    private transient Set<LabelAtom> labelSet;
    private /*final*/ transient JCloudsCloud cloud;

    // Backward compatibility
    private transient @Deprecated String imageId;
    private transient @Deprecated String hardwareId;
    private transient @Deprecated String userDataId;
    private transient @Deprecated String numExecutors;
    private transient @Deprecated String jvmOptions;
    private transient @Deprecated String fsRoot;
    private transient @Deprecated Integer overrideRetentionTime;
    private transient @Deprecated String keyPairName;
    private transient @Deprecated String networkId;
    private transient @Deprecated String securityGroups;
    private transient @Deprecated String credentialsId;
    private transient @Deprecated JCloudsCloud.SlaveType slaveType;
    private transient @Deprecated String availabilityZone;

    @DataBoundConstructor
    public JCloudsSlaveTemplate(final String name, final String labelString, final SlaveOptions slaveOptions) {

        this.name = Util.fixEmptyAndTrim(name);
        this.labelString = Util.fixNull(labelString);

        this.slaveOptions = slaveOptions;

        readResolve();
    }

    @SuppressWarnings("deprecation")
    protected Object readResolve() {
        // Initializes data structure that we don't persist.
        labelSet = Label.parse(labelString);

        // Migrate from 1.X to 2.0
        int i;
        if (hardwareId != null && (i = hardwareId.indexOf('/')) != -1) {
            hardwareId = hardwareId.substring(i + 1);
        }

        if (networkId != null && (i = networkId.indexOf('/')) != -1) {
            networkId = networkId.substring(i + 1);
        }

        if (imageId != null && (i = imageId.indexOf('/')) != -1) {
            imageId = imageId.substring(i + 1);
        }

        // Migrate from 2.0 to 2.1
        if (slaveOptions == null) {
            slaveOptions = SlaveOptions.builder().imageId(imageId).hardwareId(hardwareId).numExecutors(Integer.getInteger(numExecutors)).jvmOptions(jvmOptions).userDataId(userDataId)
                    .fsRoot(fsRoot).retentionTime(overrideRetentionTime).keyPairName(keyPairName).networkId(networkId).securityGroups(securityGroups)
                    .credentialsId(credentialsId).slaveType(slaveType).availabilityZone(availabilityZone).build()
            ;

            this.hardwareId = null;
            this.numExecutors = null;
            this.jvmOptions = null;
            this.userDataId = null;
            this.fsRoot = null;
            this.overrideRetentionTime = null;
            this.keyPairName = null;
            this.networkId = null;
            this.securityGroups = null;
            this.credentialsId = null;
            this.slaveType = null;
            this.availabilityZone = null;
        }

        return this;
    }

    // Called when registered into cloud, this class is not supposed to be persisted before this is called
    @Restricted(NoExternalUse.class)
    /*package*/ void setOwner(JCloudsCloud cloud) {
        this.cloud = cloud;
        slaveOptions = slaveOptions.eraseDefaults(cloud.getEffectiveSlaveOptions());
    }

    public @Nonnull SlaveOptions getEffectiveSlaveOptions() {
        // Make sure only diff of defaults is saved so when defaults will change users are not stuck with outdated config
        return cloud.getEffectiveSlaveOptions().override(slaveOptions);
    }

    public @Nonnull SlaveOptions getRawSlaveOptions() {
        if (cloud == null) throw new IllegalStateException("Owner not set properly");
        return slaveOptions;
    }

    public Set<LabelAtom> getLabelSet() {
        return labelSet;
    }

    /*package*/ Supplier<Server> getSuplier(@Nonnull final JCloudsCloud cloud) throws Openstack.ActionFailed {
        return new Supplier<Server>() {
            @Override public Server get() {
                return JCloudsSlaveTemplate.this.provision(cloud);
            }
        };
    }

    /**
     * Provision and connect as a slave.
     *
     * @throws Openstack.ActionFailed Provisioning failed.
     */
    public @Nonnull JCloudsSlave provisionSlave(@Nonnull JCloudsCloud cloud, @Nonnull TaskListener listener) throws IOException, Openstack.ActionFailed {
        Server nodeMetadata = provision(cloud);
        SlaveOptions opts = getEffectiveSlaveOptions();

        try {
            return new JCloudsSlave(cloud.getDisplayName(), nodeMetadata, labelString, opts);
        } catch (Descriptor.FormException e) {
            throw new AssertionError("Invalid configuration " + e.getMessage());
        }
    }

    /**
     * Provision OpenStack machine.
     *
     * @throws Openstack.ActionFailed In case the provisioning failed.
     * @see #provisionSlave(JCloudsCloud, TaskListener)
     */
    public @Nonnull Server provision(@Nonnull JCloudsCloud cloud) throws Openstack.ActionFailed {
        final SlaveOptions opts = getEffectiveSlaveOptions();
        final ServerCreateBuilder builder = Builders.server();
        builder.addMetadataItem(OPENSTACK_TEMPLATE_NAME_KEY, name);

        final String nodeName = name + "-" + System.currentTimeMillis() % 1000;
        LOGGER.info("Provisioning new openstack node " + nodeName);
        // Ensure predictable node name so we can inject it into user data
        builder.name(nodeName);

        if (!Strings.isNullOrEmpty(opts.getImageId())) {
            LOGGER.fine("Setting image id to " + opts.getImageId());
            builder.image(opts.getImageId());
        }

        String hwid = opts.getHardwareId();
        if (!Strings.isNullOrEmpty(hwid)) {
            LOGGER.fine("Setting hardware Id to " + hwid);
            builder.flavor(hwid);
        }

        String nid = opts.getNetworkId();
        if (!Strings.isNullOrEmpty(nid)) {
            LOGGER.fine("Setting network to " + nid);
            builder.networks(Arrays.asList(nid));
        }

        if (!Strings.isNullOrEmpty(opts.getSecurityGroups())) {
            LOGGER.fine("Setting security groups to " + opts.getSecurityGroups());
            for (String sg: csvToArray(opts.getSecurityGroups())) {
                builder.addSecurityGroup(sg);
            }
        }

        String kpn = opts.getKeyPairName();
        if (!Strings.isNullOrEmpty(kpn)) {
            LOGGER.fine("Setting keyPairName to " + kpn);
            builder.keypairName(kpn);
        }

        String az = opts.getAvailabilityZone();
        if (!Strings.isNullOrEmpty(az)) {
            LOGGER.fine("Setting availabilityZone to " + az);
            builder.availabilityZone(az);
        }

        @CheckForNull String userDataText = getUserData();
        if (userDataText != null) {
            HashMap<String, String> vars = new HashMap<>();
            String rootUrl = Jenkins.getInstance().getRootUrl();
            vars.put("JENKINS_URL", rootUrl);
            vars.put("SLAVE_JAR_URL", rootUrl + "jnlpJars/slave.jar");
            vars.put("SLAVE_JNLP_URL", rootUrl + "computer/" + nodeName + "/slave-agent.jnlp");
            vars.put("SLAVE_LABELS", labelString);
            String content = Util.replaceMacro(userDataText, vars);
            LOGGER.fine("Sending user-data:\n" + content);
            builder.userData(Base64.encode(content.getBytes()));
        }

        final Openstack openstack = cloud.getOpenstack();
        final Server server = openstack.bootAndWaitActive(builder, opts.getStartTimeout());
        LOGGER.info("Provisioned: " + server.toString());

        String poolName = opts.getFloatingIpPool();
        if (poolName != null) {
            LOGGER.fine("Assiging floating IP from " + poolName + " to " + nodeName);
            openstack.assignFloatingIp(server, poolName);
            // Make sure address information is refreshed
            return openstack.updateInfo(server);
        }

        return server;
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

    /*package for testing*/ @CheckForNull String getUserData() {
        Config userData = ConfigProvider.all().get(UserDataConfig.UserDataConfigProvider.class).getConfigById(getEffectiveSlaveOptions().getUserDataId());

        return (userData == null || userData.content.isEmpty())
                ? null
                : userData.content
        ;
    }

    /*package*/ List<? extends Server> getRunningNodes() {
        List<Server> tmplt = new ArrayList<>();
        for (Server server : cloud.getOpenstack().getRunningNodes()) {
            Map<String, String> md = server.getMetadata();
            if (name.equals(md.get(OPENSTACK_TEMPLATE_NAME_KEY))) {
                tmplt.add(server);
            }
        }
        return tmplt;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Descriptor<JCloudsSlaveTemplate> getDescriptor() {
        return Jenkins.getInstance().getDescriptor(getClass());
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<JCloudsSlaveTemplate> {
        private static final Pattern NAME_PATTERN = Pattern.compile("[a-z0-9][-a-zA-Z0-9]{0,79}");

        @Override
        public String getDisplayName() {
            return null;
        }

        @Restricted(DoNotUse.class)
        public FormValidation doCheckName(@QueryParameter String value) {
            // org.jclouds.predicates.validators.DnsNameValidator
            if (NAME_PATTERN.matcher(value).find()) return FormValidation.ok();

            return FormValidation.error(
                    "Must be a lowercase string, from 1 to 80 characteres long, starting with letter or number"
            );
        }
    }
}
