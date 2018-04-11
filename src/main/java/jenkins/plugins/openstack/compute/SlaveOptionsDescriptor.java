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

import hudson.util.ComboBoxModel;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import jenkins.plugins.openstack.compute.auth.OpenstackCredential;
import jenkins.plugins.openstack.compute.auth.OpenstackCredentials;
import jenkins.plugins.openstack.compute.internal.Openstack;
import org.jenkinsci.lib.configprovider.ConfigProvider;
import org.jenkinsci.lib.configprovider.model.Config;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.QueryParameter;
import org.openstack4j.api.exceptions.AuthenticationException;
import org.openstack4j.api.exceptions.ConnectionException;
import org.openstack4j.model.compute.Flavor;
import org.openstack4j.model.compute.ext.AvailabilityZone;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author ogondza.
 */
@Extension @Restricted(NoExternalUse.class)
public final class SlaveOptionsDescriptor extends OsAuthDescriptor<SlaveOptions> {
    private static final Logger LOGGER = Logger.getLogger(SlaveOptionsDescriptor.class.getName());
    public static final FormValidation OK = FormValidation.ok();
    public static final FormValidation REQUIRED = FormValidation.error(hudson.util.Messages.FormValidation_ValidateRequired());

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
    public FormValidation doCheckInstanceCap(
            @QueryParameter String value,
            @RelativePath("../../slaveOptions") @QueryParameter("instanceCap") String def
    ) {
        if (Util.fixEmpty(value) == null) {
            String d = getDefault(def, opts().getInstanceCap());
            if (d != null) return FormValidation.ok(def(d));
            return REQUIRED;
        }
        return FormValidation.validatePositiveInteger(value);
    }

    @Restricted(DoNotUse.class)
    public FormValidation doCheckStartTimeout(
            @QueryParameter String value,
            @RelativePath("../../slaveOptions") @QueryParameter("startTimeout") String def
    ) {
        if (Util.fixEmpty(value) == null) {
            String d = getDefault(def, opts().getStartTimeout());
            if (d != null) return FormValidation.ok(def(d));
            return REQUIRED;
        }
        return FormValidation.validatePositiveInteger(value);
    }

    @Restricted(DoNotUse.class)
    public FormValidation doCheckNumExecutors(
            @QueryParameter String value,
            @RelativePath("../../slaveOptions") @QueryParameter("numExecutors") String def
    ) {
        if (Util.fixEmpty(value) == null) {
            String d = getDefault(def, opts().getNumExecutors());
            if (d != null) return FormValidation.ok(def(d));
            return REQUIRED;
        }
        return FormValidation.validatePositiveInteger(value);
    }

    @Restricted(DoNotUse.class)
    public FormValidation doCheckRetentionTime(
            @QueryParameter String value,
            @RelativePath("../../slaveOptions") @QueryParameter("retentionTime") String def
    ) {
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
    public ListBoxModel doFillFloatingIpPoolItems(
            @QueryParameter String floatingIpPool,
            @QueryParameter String endPointUrl, @QueryParameter boolean ignoreSsl,
            @QueryParameter String credentialId, @QueryParameter String zone
    ) {
        ListBoxModel m = new ListBoxModel();
        m.add("None specified", "");
        final String valueOrEmpty = Util.fixNull(floatingIpPool);
        try {
            OpenstackCredential openstackCredential = OpenstackCredentials.getCredential(credentialId);
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
    public FormValidation doCheckFloatingIpPool(
            @QueryParameter String value,
            @RelativePath("../../slaveOptions") @QueryParameter("floatingIpPool") String def
    ) {
        if (Util.fixEmpty(value) == null) {
            String d = getDefault(def, opts().getFloatingIpPool());
            if (d != null) return FormValidation.ok(def(d));
            // Not required
        }
        return OK;
    }

    @Restricted(DoNotUse.class)
    @InjectOsAuth
    public ListBoxModel doFillHardwareIdItems(
            @QueryParameter String hardwareId, @QueryParameter String endPointUrl,
            @QueryParameter boolean ignoreSsl,
            @QueryParameter String credentialId, @QueryParameter String zone
    ) {
        ListBoxModel m = new ListBoxModel();
        m.add("None specified", "");
        final String valueOrEmpty = Util.fixNull(hardwareId);
        try {
            OpenstackCredential openstackCredential = OpenstackCredentials.getCredential(credentialId);
            if (haveAuthDetails(endPointUrl, openstackCredential, zone)) {
                final Openstack openstack = Openstack.Factory.get(endPointUrl, ignoreSsl, openstackCredential, zone);
                for (Flavor flavor : openstack.getSortedFlavors()) {
                    final String value = flavor.getId();
                    final String displayText = String.format("%s (%s)", flavor.getName(), value);
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
    public FormValidation doCheckHardwareId(
            @QueryParameter String value,
            @RelativePath("../../slaveOptions") @QueryParameter("hardwareId") String def
    ) {
        if (Util.fixEmpty(value) == null) {
            String d = getDefault(def, opts().getHardwareId());
            if (d != null) return FormValidation.ok(def(d));
            return REQUIRED;
        }
        return OK;
    }

    @Restricted(DoNotUse.class)
    public FormValidation doCheckNetworkId(
            @QueryParameter String value,
            @RelativePath("../../slaveOptions") @QueryParameter("networkId") String def
    ) {
        if (Util.fixEmpty(value) == null) {
            String d = getDefault(def, opts().getNetworkId());
            if (d != null) return FormValidation.ok(def(d));
            return OK;
        }
        return OK;
    }

    @Restricted(DoNotUse.class)
    public ListBoxModel doFillUserDataIdItems() {
        ListBoxModel m = new ListBoxModel();
        m.add("None specified", "");
        ConfigProvider provider = getConfigProvider();
        for (Config config : provider.getAllConfigs()) {
            m.add(config.name, config.id);
        }
        return m;
    }

    @Restricted(DoNotUse.class)
    public FormValidation doCheckUserDataId(
            @QueryParameter String value,
            @RelativePath("../../slaveOptions") @QueryParameter("userDataId") String def
    ) {
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

    private FormValidation getUserDataLink(String id, String name) {
        return FormValidation.okWithMarkup(
                "<a target='_blank' href='" + Jenkins.getActiveInstance().getRootUrl() + "configfiles/editConfig?id=" + Util.escape(id) + "'>" + Util.escape(name) + "</a>"
        );
    }

    private ConfigProvider getConfigProvider() {
        ExtensionList<ConfigProvider> providers = ConfigProvider.all();
        return providers.get(UserDataConfig.UserDataConfigProvider.class);
    }

    @Restricted(DoNotUse.class)
    public FormValidation doCheckSecurityGroups(
            @QueryParameter String value,
            @RelativePath("../../slaveOptions") @QueryParameter("securityGroups") String def
    ) {
        if (Util.fixEmpty(value) == null) {
            String d = getDefault(def, opts().getSecurityGroups());
            if (d != null) return FormValidation.ok(def(d));
            return OK;
        }
        return OK;
    }

    @Restricted(DoNotUse.class)
    @InjectOsAuth
    public ComboBoxModel doFillAvailabilityZoneItems(
            @QueryParameter String availabilityZone, @QueryParameter String endPointUrl,
            @QueryParameter boolean ignoreSsl,
            @QueryParameter String credentialId, @QueryParameter String zone
    ) {
        // Support for availabilityZones is optional in OpenStack, so this is a f:combobox not f:select field.
        // Therefore we suggest some options if we can, but if we can't then we assume it's because they're not needed.
        final ComboBoxModel m = new ComboBoxModel();
        try {
            OpenstackCredential openstackCredential = OpenstackCredentials.getCredential(credentialId);
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
    public FormValidation doCheckAvailabilityZone(
            @QueryParameter String value,
            @RelativePath("../../slaveOptions") @QueryParameter("availabilityZone") String def,
            // authentication fields can be in two places relative to us.
            @RelativePath("..") @QueryParameter("endPointUrl") String endPointUrlCloud,
            @RelativePath("..") @QueryParameter("ignoreSsl") boolean ignoreSsl,
            @RelativePath("../..") @QueryParameter("endPointUrl") String endPointUrlTemplate,
            @RelativePath("..") @QueryParameter("credentialId") String credentialIdCloud,
            @RelativePath("../..") @QueryParameter("credentialId") String credentialIdTemplate,
            @RelativePath("..") @QueryParameter("zone") String zoneCloud,
            @RelativePath("../..") @QueryParameter("zone") String zoneTemplate
    ) {
        // Warn user if they've not selected anything AND there's multiple availability zones
        // as this can lead to non-deterministic behavior.
        // But if we can't find any availability zones then we assume that all is OK
        // because not all OpenStack deployments support them.
        if (Util.fixEmpty(value) == null) {
            final String d = getDefault(def, opts().getAvailabilityZone());
            if (d != null) return FormValidation.ok(def(d));
            final String endPointUrl = getDefault(endPointUrlCloud,endPointUrlTemplate);
            final String credentiaId = getDefault(credentialIdCloud,credentialIdTemplate);
            final OpenstackCredential openstackCredential = OpenstackCredentials.getCredential(credentiaId);
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
    public ListBoxModel doFillKeyPairNameItems(
            @QueryParameter String keyPairName,
            @QueryParameter String endPointUrl,
            @QueryParameter boolean ignoreSsl,
            @QueryParameter String credentialId, @QueryParameter String zone
    ) {
        ListBoxModel m = new ListBoxModel();
        m.add("None specified", "");
        final String valueOrEmpty = Util.fixNull(keyPairName);
        try {
            OpenstackCredential openstackCredential = OpenstackCredentials.getCredential(credentialId);
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
    public FormValidation doCheckKeyPairName(
            @QueryParameter String value,
            @RelativePath("../../slaveOptions") @QueryParameter("keyPairName") String def
    ) {
        if (Util.fixEmpty(value) == null) {
            String d = getDefault(def, opts().getKeyPairName());
            if (d != null) return FormValidation.ok(def(d));
            return OK;
        }
        return OK;
    }

    @Restricted(DoNotUse.class)
    public FormValidation doCheckJvmOptions(
            @QueryParameter String value,
            @RelativePath("../../slaveOptions") @QueryParameter("jvmOptions") String def
    ) {
        if (Util.fixEmpty(value) == null) {
            String d = getDefault(def, opts().getJvmOptions());
            if (d != null) return FormValidation.ok(def(d));
            return OK;
        }
        return OK;
    }

    @Restricted(DoNotUse.class)
    public FormValidation doCheckFsRoot(
            @QueryParameter String value,
            @RelativePath("../../slaveOptions") @QueryParameter("fsRoot") String def
    ) {
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
}
