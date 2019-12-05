/*
 * The MIT License
 *
 * Copyright (c) Red Hat, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package jenkins.plugins.openstack.compute;

import hudson.Extension;
import hudson.ExtensionList;
import hudson.RelativePath;
import hudson.Util;
import hudson.slaves.NodePropertyDescriptor;
import hudson.util.ComboBoxModel;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import jenkins.plugins.openstack.compute.JCloudsSlave.JCloudsSlaveDescriptor;
import jenkins.plugins.openstack.compute.auth.OpenstackCredential;
import jenkins.plugins.openstack.compute.auth.OpenstackCredentials;
import jenkins.plugins.openstack.compute.internal.Openstack;
import net.sf.json.JSONObject;
import org.jenkinsci.lib.configprovider.ConfigProvider;
import org.jenkinsci.lib.configprovider.model.Config;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.interceptor.RequirePOST;
import org.openstack4j.api.exceptions.AuthenticationException;
import org.openstack4j.api.exceptions.ConnectionException;
import org.openstack4j.model.compute.Flavor;
import org.openstack4j.model.compute.ext.AvailabilityZone;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author ogondza.
 */
@Extension @Restricted(NoExternalUse.class)
public final class SlaveOptionsDescriptor extends OsAuthDescriptor<SlaveOptions> {
    private static final Logger LOGGER = Logger.getLogger(SlaveOptionsDescriptor.class.getName());
    public static final FormValidation OK = FormValidation.ok();
    public static final FormValidation REQUIRED = FormValidation.error("Required");

    public SlaveOptionsDescriptor() {
        super(SlaveOptions.class);
    }

    @Override
    public String getDisplayName() {
        return "Slave Options";
    }

    private SlaveOptions opts() {
        return JCloudsCloud.DescriptorImpl.getDefaultOptions();
    }

    @Restricted(DoNotUse.class)
    @RequirePOST
    public FormValidation doCheckInstanceCap(
            @QueryParameter String value,
            @RelativePath("../../slaveOptions") @QueryParameter("instanceCap") String def
    ) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        if (Util.fixEmpty(value) == null) {
            String d = getDefault(def, opts().getInstanceCap());
            if (d != null) return FormValidation.ok(def(d));
            return REQUIRED;
        }
        return FormValidation.validatePositiveInteger(value);
    }

    @Restricted(DoNotUse.class)
    @RequirePOST
    public FormValidation doCheckInstancesMin(
            @QueryParameter String value,
            @RelativePath("../../slaveOptions") @QueryParameter("instancesMin") String def
    ) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        if (Util.fixEmpty(value) == null) {
            String d = getDefault(def, opts().getInstancesMin());
            if (d != null) return FormValidation.ok(def(d));
            return REQUIRED;
        }
        return FormValidation.validateNonNegativeInteger(value);
    }

    @Restricted(DoNotUse.class)
    @RequirePOST
    public FormValidation doCheckStartTimeout(
            @QueryParameter String value,
            @RelativePath("../../slaveOptions") @QueryParameter("startTimeout") String def
    ) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        if (Util.fixEmpty(value) == null) {
            String d = getDefault(def, opts().getStartTimeout());
            if (d != null) return FormValidation.ok(def(d));
            return REQUIRED;
        }
        return FormValidation.validatePositiveInteger(value);
    }

    @Restricted(DoNotUse.class)
    @RequirePOST
    public FormValidation doCheckNumExecutors(
            @QueryParameter String value,
            @RelativePath("../../slaveOptions") @QueryParameter("numExecutors") String def
    ) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        if (Util.fixEmpty(value) == null) {
            String d = getDefault(def, opts().getNumExecutors());
            if (d != null) return FormValidation.ok(def(d));
            return REQUIRED;
        }
        return FormValidation.validatePositiveInteger(value);
    }

    @Restricted(DoNotUse.class)
    @RequirePOST
    public FormValidation doCheckRetentionTime(
            @QueryParameter String value,
            @RelativePath("../../slaveOptions") @QueryParameter("retentionTime") String def
    ) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        if (Util.fixEmpty(value) == null) {
            String d = getDefault(def, opts().getRetentionTime());
            if (d != null) return FormValidation.ok(def(d));
            return REQUIRED;
        }
        try {
            if (Integer.parseInt(value) == -1)
                return FormValidation.ok("Keep forever");
        } catch (NumberFormatException e) {
        }
        return FormValidation.validateNonNegativeInteger(value);
    }

    @Restricted(DoNotUse.class)
    @InjectOsAuth
    @RequirePOST
    public ListBoxModel doFillFloatingIpPoolItems(
            @QueryParameter String floatingIpPool,
            @QueryParameter String endPointUrl, @QueryParameter boolean ignoreSsl,
            @QueryParameter String credentialsId, @QueryParameter String zone
    ) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        ListBoxModel m = new ListBoxModel();
        m.add("None specified", "");
        final String valueOrEmpty = Util.fixNull(floatingIpPool);
        try {
            OpenstackCredential openstackCredential = OpenstackCredentials.getCredential(credentialsId);
            if (haveAuthDetails(endPointUrl, openstackCredential, zone)) {
                final Openstack openstack = Openstack.Factory.get(endPointUrl, ignoreSsl, openstackCredential, zone);
                for (String p : openstack.getSortedIpPools()) {
                    m.add(p);
                }
            }
        } catch (AuthenticationException | FormValidation | ConnectionException ex) {
            LOGGER.log(Level.FINEST, "Openstack call failed", ex);
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, ex.getMessage(), ex);
        }
        if (!hasValue(m, valueOrEmpty)) {
            m.add(valueOrEmpty);
        }
        return m;
    }

    @Restricted(DoNotUse.class)
    @RequirePOST
    public FormValidation doCheckFloatingIpPool(
            @QueryParameter String value,
            @RelativePath("../../slaveOptions") @QueryParameter("floatingIpPool") String def
    ) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        if (Util.fixEmpty(value) == null) {
            String d = getDefault(def, opts().getFloatingIpPool());
            if (d != null) return FormValidation.ok(def(d));
            // Not required
        }
        return OK;
    }

    @Restricted(DoNotUse.class)
    @InjectOsAuth
    @RequirePOST
    public ListBoxModel doFillHardwareIdItems(
            @QueryParameter String hardwareId, @QueryParameter String endPointUrl,
            @QueryParameter boolean ignoreSsl,
            @QueryParameter String credentialsId, @QueryParameter String zone
    ) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        ListBoxModel m = new ListBoxModel();
        m.add("None specified", "");
        final String valueOrEmpty = Util.fixNull(hardwareId);
        try {
            OpenstackCredential openstackCredential = OpenstackCredentials.getCredential(credentialsId);
            if (haveAuthDetails(endPointUrl, openstackCredential, zone)) {
                final Openstack openstack = Openstack.Factory.get(endPointUrl, ignoreSsl, openstackCredential, zone);
                for (Flavor flavor : openstack.getSortedFlavors()) {
                    final String value = flavor.getId();
                    final String displayText = Openstack.getFlavorInfo(flavor);
                    m.add(displayText, value);
                }
            }
        } catch (AuthenticationException | FormValidation | ConnectionException ex) {
            LOGGER.log(Level.FINEST, "Openstack call failed", ex);
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, ex.getMessage(), ex);
        }
        if (!hasValue(m, valueOrEmpty)) {
            m.add(hardwareId);
        }
        return m;
    }

    @Restricted(DoNotUse.class)
    @RequirePOST
    public FormValidation doCheckHardwareId(
            @QueryParameter String value,
            @RelativePath("../../slaveOptions") @QueryParameter("hardwareId") String def
    ) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        if (Util.fixEmpty(value) == null) {
            String d = getDefault(def, opts().getHardwareId());
            if (d != null) return FormValidation.ok(def(d));
            return REQUIRED;
        }
        return OK;
    }

    @Restricted(DoNotUse.class)
    @RequirePOST
    public FormValidation doCheckNetworkId(
            @QueryParameter String value,
            @RelativePath("../../slaveOptions") @QueryParameter("networkId") String def,
            // authentication fields can be in two places relative to us.
            @RelativePath("..") @QueryParameter("endPointUrl") String endPointUrlCloud,
            @RelativePath("..") @QueryParameter("ignoreSsl") boolean ignoreSsl,
            @RelativePath("../..") @QueryParameter("endPointUrl") String endPointUrlTemplate,
            @RelativePath("..") @QueryParameter("credentialsId") String credentialsIdCloud,
            @RelativePath("../..") @QueryParameter("credentialsId") String credentialsIdTemplate,
            @RelativePath("..") @QueryParameter("zone") String zoneCloud,
            @RelativePath("../..") @QueryParameter("zone") String zoneTemplate
    ) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        if (Util.fixEmpty(value) == null) {
            String d = getDefault(def, opts().getNetworkId());
            if (d != null) return FormValidation.ok(def(d));
            return OK;
        }

        final String endPointUrl = getDefault(endPointUrlCloud, endPointUrlTemplate);
        final String credentialsId = getDefault(credentialsIdCloud, credentialsIdTemplate);
        final OpenstackCredential openstackCredential = OpenstackCredentials.getCredential(credentialsId);
        final String zone = getDefault(zoneCloud, zoneTemplate);
        if (haveAuthDetails(endPointUrl, openstackCredential, zone)) {
            try {
                final Openstack openstack = Openstack.Factory.get(endPointUrl, ignoreSsl, openstackCredential, zone);
                List<String> nids = JCloudsSlaveTemplate.selectNetworkIds(openstack, value);
                return FormValidation.ok("Will connect to " + nids.size() + " network(s). Ex.: " + nids);
            } catch (IllegalArgumentException | NoSuchElementException ex) {
                return FormValidation.warning(ex.getMessage());
            } catch (AuthenticationException | FormValidation | ConnectionException ex) {
                LOGGER.log(Level.FINEST, "Openstack call failed", ex);
            } catch (Exception ex) {
                LOGGER.log(Level.SEVERE, ex.getMessage(), ex);
            }
        }
        return OK;
    }

    @Restricted(DoNotUse.class)
    @RequirePOST
    public ListBoxModel doFillUserDataIdItems() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        ListBoxModel m = new ListBoxModel();
        m.add("None specified", "");
        ConfigProvider provider = getConfigProvider();
        for (Config config : provider.getAllConfigs()) {
            m.add(config.name, config.id);
        }
        return m;
    }

    @Restricted(DoNotUse.class)
    @RequirePOST
    public FormValidation doCheckUserDataId(
            @QueryParameter String value,
            @RelativePath("../../slaveOptions") @QueryParameter("userDataId") String def
    ) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        if (Util.fixEmpty(value) == null) {
            String d = getDefault(def, opts().getUserDataId());
            if (d != null) {
                String name = getConfigProvider().getConfigById(d).name;
                return getUserDataLink(d, def(name));
            }
            return OK;
        }
        return getUserDataLink(value, "View file");
    }

    @Restricted(DoNotUse.class)
    @RequirePOST
    public ListBoxModel doFillConfigDriveItems(
            @QueryParameter String value,
            @RelativePath("../../slaveOptions") @QueryParameter("configDrive") String def
    ) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        ListBoxModel m = new ListBoxModel();
        m.add("Inherited / Override later", "");
        m.add("True", "true");
        m.add("False", "false");
        return m;
    }

    private FormValidation getUserDataLink(String id, String name) {
        return FormValidation.okWithMarkup(
                "<a target='_blank' href='" + Jenkins.get().getRootUrl() + "configfiles/editConfig?id=" + Util.escape(id) + "'>" + Util.escape(name) + "</a>"
        );
    }

    private ConfigProvider getConfigProvider() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        ExtensionList<ConfigProvider> providers = ConfigProvider.all();
        return providers.get(UserDataConfig.UserDataConfigProvider.class);
    }

    @Restricted(DoNotUse.class)
    @RequirePOST
    public FormValidation doCheckSecurityGroups(
            @QueryParameter String value,
            @RelativePath("../../slaveOptions") @QueryParameter("securityGroups") String def
    ) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        if (Util.fixEmpty(value) == null) {
            String d = getDefault(def, opts().getSecurityGroups());
            if (d != null) return FormValidation.ok(def(d));
            return OK;
        }
        return OK;
    }

    @Restricted(DoNotUse.class)
    @InjectOsAuth
    @RequirePOST
    public ComboBoxModel doFillAvailabilityZoneItems(
            @QueryParameter String availabilityZone, @QueryParameter String endPointUrl,
            @QueryParameter boolean ignoreSsl,
            @QueryParameter String credentialsId, @QueryParameter String zone
    ) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        // Support for availabilityZones is optional in OpenStack, so this is a f:combobox not f:select field.
        // Therefore we suggest some options if we can, but if we can't then we assume it's because they're not needed.
        final ComboBoxModel m = new ComboBoxModel();
        try {
            OpenstackCredential openstackCredential = OpenstackCredentials.getCredential(credentialsId);
            if (haveAuthDetails(endPointUrl, openstackCredential, zone)) {
                final Openstack openstack = Openstack.Factory.get(endPointUrl, ignoreSsl, openstackCredential, zone);
                for (final AvailabilityZone az : openstack.getAvailabilityZones()) {
                    final String value = az.getZoneName();
                    m.add(value);
                }
            }
        } catch (AuthenticationException | FormValidation | ConnectionException ex) {
            LOGGER.log(Level.FINEST, "Openstack call failed", ex);
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, ex.getMessage(), ex);
        }
        return m;
    }

    @Restricted(DoNotUse.class)
    @RequirePOST
    public FormValidation doCheckAvailabilityZone(
            @QueryParameter String value,
            @RelativePath("../../slaveOptions") @QueryParameter("availabilityZone") String def,
            // authentication fields can be in two places relative to us.
            @RelativePath("..") @QueryParameter("endPointUrl") String endPointUrlCloud,
            @RelativePath("..") @QueryParameter("ignoreSsl") boolean ignoreSsl,
            @RelativePath("../..") @QueryParameter("endPointUrl") String endPointUrlTemplate,
            @RelativePath("..") @QueryParameter("credentialsId") String credentialsIdCloud,
            @RelativePath("../..") @QueryParameter("credentialsId") String credentialsIdTemplate,
            @RelativePath("..") @QueryParameter("zone") String zoneCloud,
            @RelativePath("../..") @QueryParameter("zone") String zoneTemplate
    ) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        // Warn user if they've not selected anything AND there's multiple availability zones
        // as this can lead to non-deterministic behavior.
        // But if we can't find any availability zones then we assume that all is OK
        // because not all OpenStack deployments support them.
        if (Util.fixEmpty(value) == null) {
            final String d = getDefault(def, opts().getAvailabilityZone());
            if (d != null) return FormValidation.ok(def(d));
            final String endPointUrl = getDefault(endPointUrlCloud,endPointUrlTemplate);
            final String credentialsId = getDefault(credentialsIdCloud,credentialsIdTemplate);
            final OpenstackCredential openstackCredential = OpenstackCredentials.getCredential(credentialsId);
            final String zone = getDefault(zoneCloud, zoneTemplate);
            if (haveAuthDetails(endPointUrl, openstackCredential, zone)) {
                try {
                    final Openstack openstack = Openstack.Factory.get(endPointUrl, ignoreSsl, openstackCredential, zone);
                    final int numberOfAZs = openstack.getAvailabilityZones().size();
                    if (numberOfAZs > 1) {
                        return FormValidation.warning("Ambiguity warning: Multiple zones found.");
                    }
                } catch (AuthenticationException | FormValidation | ConnectionException ex) {
                    LOGGER.log(Level.FINEST, "Openstack call failed", ex);
                } catch (Exception ex) {
                    LOGGER.log(Level.SEVERE, ex.getMessage(), ex);
                }
            }
        }
        return OK;
    }

    @Restricted(DoNotUse.class)
    @InjectOsAuth
    @RequirePOST
    public ListBoxModel doFillKeyPairNameItems(
            @QueryParameter String keyPairName,
            @QueryParameter String endPointUrl,
            @QueryParameter boolean ignoreSsl,
            @QueryParameter String credentialsId, @QueryParameter String zone
    ) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        ListBoxModel m = new ListBoxModel();
        m.add("None specified", "");
        final String valueOrEmpty = Util.fixNull(keyPairName);
        try {
            OpenstackCredential openstackCredential = OpenstackCredentials.getCredential(credentialsId);
            if (haveAuthDetails(endPointUrl, openstackCredential, zone)) {
                Openstack openstack = Openstack.Factory.get(endPointUrl, ignoreSsl, openstackCredential, zone);
                for (String value : openstack.getSortedKeyPairNames()) {
                    m.add(value);
                }
            }
        } catch (AuthenticationException | FormValidation | ConnectionException ex) {
            LOGGER.log(Level.FINEST, "Openstack call failed", ex);
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, ex.getMessage(), ex);
        }
        if (!hasValue(m, valueOrEmpty)) {
            m.add(keyPairName);
        }
        return m;
    }

    @Restricted(DoNotUse.class)
    @RequirePOST
    public FormValidation doCheckKeyPairName(
            @QueryParameter String value,
            @RelativePath("../../slaveOptions") @QueryParameter("keyPairName") String def
    ) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        if (Util.fixEmpty(value) == null) {
            String d = getDefault(def, opts().getKeyPairName());
            if (d != null) return FormValidation.ok(def(d));
            return OK;
        }
        return OK;
    }

    @Restricted(DoNotUse.class)
    @RequirePOST
    public FormValidation doCheckJvmOptions(
            @QueryParameter String value,
            @RelativePath("../../slaveOptions") @QueryParameter("jvmOptions") String def
    ) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        if (Util.fixEmpty(value) == null) {
            String d = getDefault(def, opts().getJvmOptions());
            if (d != null) return FormValidation.ok(def(d));
            return OK;
        }
        return OK;
    }

    @Restricted(DoNotUse.class)
    @RequirePOST
    public FormValidation doCheckFsRoot(
            @QueryParameter String value,
            @RelativePath("../../slaveOptions") @QueryParameter("fsRoot") String def
    ) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        if (Util.fixEmpty(value) == null) {
            String d = getDefault(def, opts().getFsRoot());
            if (d != null) return FormValidation.ok(def(d));
            return OK;
        }
        return OK;
    }

    @Override
    public List<String> getAuthFieldsOffsets() {
        return Arrays.asList("..", "../..");
    }

    /**
     * Get Default value label.
     */
    private @Nonnull String def(@CheckForNull Object val) {
        return val == null ? "" : ("Inherited value: " + val);
    }

    /**
     * Returns the list of {@link NodePropertyDescriptor} appropriate for the
     * {@link JCloudsSlave}s that are created from these options.
     *
     * @return the filtered list
     */
    @Nonnull
    @Restricted(NoExternalUse.class) // used by Jelly EL only
    public List<NodePropertyDescriptor> getNodePropertiesDescriptors() {
        final Jenkins j = Jenkins.get();
        final JCloudsSlaveDescriptor jcsd = (JCloudsSlaveDescriptor) j.getDescriptorOrDie(JCloudsSlave.class);
        return jcsd.nodePropertyDescriptors(null);
    }

    @Override
    @Restricted(NoExternalUse.class) // Stapler
    public SlaveOptions newInstance(@Nullable StaplerRequest req, @Nonnull JSONObject formData) throws FormException {
        // Permit null and empty nodeProperties:
        // f:optionalBlock does not adhere to its documented contract when inline=true. The expected behavior here is
        // nodeProperties=null when the block is collapsed (no matter what is in). However, it is being sent even when
        // collapsed so SlaveOptions constructor is forced to consult the dummy property to tell null from value on the
        // real property making the API awful. When inline=false, the field is always wrapped in extra level of json object forcing extra
        // describable to be created (between SlaveOptions and properties list) making the API awful again.
        // Therefore, nodeProperties is only propagated to @DBC when hasNodeProperties is true and hasNodeProperties is only processed here.
        if (!formData.optBoolean("hasNodeProperties")) {
            formData.remove("nodeProperties");
        }
        formData.remove("hasNodeProperties");
        return super.newInstance(req, formData);
    }
}
