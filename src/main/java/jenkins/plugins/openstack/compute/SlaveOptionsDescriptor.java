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

import com.cloudbees.jenkins.plugins.sshcredentials.SSHAuthenticator;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernameListBoxModel;
import com.trilead.ssh2.Connection;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.RelativePath;
import hudson.Util;
import hudson.model.Computer;
import hudson.model.ItemGroup;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.security.ACL;
import hudson.security.AccessControlled;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import jenkins.plugins.openstack.compute.internal.Openstack;
import org.jenkinsci.lib.configprovider.ConfigProvider;
import org.jenkinsci.lib.configprovider.model.Config;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.QueryParameter;
import org.openstack4j.api.exceptions.AuthenticationException;
import org.openstack4j.model.compute.Flavor;
import org.openstack4j.model.image.Image;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author ogondza.
 */
@Extension @Restricted(NoExternalUse.class)
public final class SlaveOptionsDescriptor extends hudson.model.Descriptor<SlaveOptions> {
    private static final Logger LOGGER = Logger.getLogger(SlaveOptionsDescriptor.class.getName());
    private static final FormValidation OK = FormValidation.ok();

    public SlaveOptionsDescriptor() {
        super(SlaveOptions.class);
    }

    @Override
    public String getDisplayName() {
        return "Slave Options";
    }

    private SlaveOptions opts() {
        return ((JCloudsCloud.DescriptorImpl) Jenkins.getInstance().getDescriptorOrDie(JCloudsCloud.class)).getDefaultOptions();
    }

    @Restricted(DoNotUse.class)
    public FormValidation doCheckInstanceCap(
            @QueryParameter String value,
            @RelativePath("../../slaveOptions") @QueryParameter("instanceCap") String def
    ) {
        if (Util.fixEmpty(value) == null && (def != null || opts().getInstanceCap() != null)) return OK;
        return FormValidation.validatePositiveInteger(value);
    }

    @Restricted(DoNotUse.class)
    public FormValidation doCheckStartTimeout(
            @QueryParameter String value,
            @RelativePath("../../slaveOptions") @QueryParameter("startTimeout") String def
    ) {
        if (Util.fixEmpty(value) == null && (def != null || opts().getStartTimeout() != null)) return OK;
        return FormValidation.validatePositiveInteger(value);
    }

    @Restricted(DoNotUse.class)
    public FormValidation doCheckNumExecutors(
            @QueryParameter String value,
            @RelativePath("../../slaveOptions") @QueryParameter("numExecutors") String def
    ) {
        if (Util.fixEmpty(value) == null && (def != null || opts().getNumExecutors() != null)) return OK;
        return FormValidation.validatePositiveInteger(value);
    }

    @Restricted(DoNotUse.class)
    public FormValidation doCheckRetentionTime(
            @QueryParameter String value,
            @RelativePath("../../slaveOptions") @QueryParameter("retentionTime") String def
    ) {
        if (Util.fixEmpty(value) == null && (def != null || opts().getRetentionTime() != null)) return OK;
        try {
            if (Integer.parseInt(value) == -1)
                return OK;
        } catch (NumberFormatException e) {
        }
        return FormValidation.validateNonNegativeInteger(value);
    }

    @Restricted(DoNotUse.class)
    public ListBoxModel doFillFloatingIpPoolItems(
            @QueryParameter String floatingIpPool,
            @RelativePath("..") @QueryParameter String endPointUrl, @RelativePath("../..") @QueryParameter("endPointUrl") String e,
            @RelativePath("..") @QueryParameter String identity, @RelativePath("../..") @QueryParameter("identity") String i,
            @RelativePath("..") @QueryParameter String credential, @RelativePath("../..") @QueryParameter("credential") String c,
            @RelativePath("..") @QueryParameter String zone, @RelativePath("../..") @QueryParameter("zone") String z
    ) {
        ListBoxModel m = new ListBoxModel();
        m.add("None specified", "");

        try {
            final Openstack openstack = JCloudsCloud.getOpenstack(endPointUrl, identity, credential, zone);
            for (String p : openstack.getSortedIpPools()) {
                m.add(p);
            }
            return m;
        } catch (AuthenticationException | FormValidation _) {
            // Incorrect credentials - noop
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, ex.getMessage(), ex);
        }

        if (Util.fixEmpty(floatingIpPool) != null) {
            m.add(floatingIpPool);
        }

        return m;
    }

    @Restricted(DoNotUse.class)
    public ListBoxModel doFillHardwareIdItems(
            @QueryParameter String hardwareId,
            @RelativePath("..") @QueryParameter String endPointUrl, @RelativePath("../..") @QueryParameter("endPointUrl") String e,
            @RelativePath("..") @QueryParameter String identity, @RelativePath("../..") @QueryParameter("identity") String i,
            @RelativePath("..") @QueryParameter String credential, @RelativePath("../..") @QueryParameter("credential") String c,
            @RelativePath("..") @QueryParameter String zone, @RelativePath("../..") @QueryParameter("zone") String z
    ) {

        ListBoxModel m = new ListBoxModel();
        m.add("None specified", "");

        try {
            final Openstack openstack = JCloudsCloud.getOpenstack(endPointUrl, identity, credential, zone);
            for (Flavor flavor : openstack.getSortedFlavors()) {
                m.add(String.format("%s (%s)", flavor.getName(), flavor.getId()), flavor.getId());
            }
            return m;
        } catch (AuthenticationException | FormValidation _) {
            // Incorrect credentials - noop
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, ex.getMessage(), ex);
        }

        if (Util.fixEmpty(hardwareId) != null) {
            m.add(hardwareId);
        }

        return m;
    }

    @Restricted(DoNotUse.class)
    public ListBoxModel doFillImageIdItems(
            @QueryParameter String imageId,
            @RelativePath("..") @QueryParameter String endPointUrl, @RelativePath("../..") @QueryParameter("endPointUrl") String e,
            @RelativePath("..") @QueryParameter String identity, @RelativePath("../..") @QueryParameter("identity") String i,
            @RelativePath("..") @QueryParameter String credential, @RelativePath("../..") @QueryParameter("credential") String c,
            @RelativePath("..") @QueryParameter String zone, @RelativePath("../..") @QueryParameter("zone") String z
    ) {

        ListBoxModel m = new ListBoxModel();
        m.add("None specified", "");

        try {
            final Openstack openstack = JCloudsCloud.getOpenstack(endPointUrl, identity, credential, zone);
            for (Image image : openstack.getSortedImages()) {
                m.add(String.format("%s (%s)", image.getName(), image.getId()), image.getId());
            }
            return m;
        } catch (AuthenticationException | FormValidation _) {
            // Incorrect credentials - noop
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, ex.getMessage(), ex);
        }

        if (Util.fixEmpty(imageId) != null) {
            m.add(imageId);
        }

        return m;
    }

    @Restricted(DoNotUse.class)
    public ListBoxModel doFillNetworkIdItems(
            @QueryParameter String networkId,
            @RelativePath("..") @QueryParameter String endPointUrl, @RelativePath("../..") @QueryParameter("endPointUrl") String e,
            @RelativePath("..") @QueryParameter String identity, @RelativePath("../..") @QueryParameter("identity") String i,
            @RelativePath("..") @QueryParameter String credential, @RelativePath("../..") @QueryParameter("credential") String c,
            @RelativePath("..") @QueryParameter String zone, @RelativePath("../..") @QueryParameter("zone") String z
    ) {

        ListBoxModel m = new ListBoxModel();
        m.add("None specified", "");

        try {
            Openstack openstack = JCloudsCloud.getOpenstack(endPointUrl, identity, credential, zone);
            for (org.openstack4j.model.network.Network network : openstack.getSortedNetworks()) {
                m.add(String.format("%s (%s)", network.getName(), network.getId()), network.getId());
            }
            return m;
        } catch (AuthenticationException | FormValidation _) {
            // Incorrect credentials - noop
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, ex.getMessage(), ex);
        }

        if (Util.fixEmpty(networkId) != null) {
            m.add(networkId);
        }

        return m;
    }

    @Restricted(DoNotUse.class)
    public ListBoxModel doFillSlaveTypeItems() {
        ListBoxModel items = new ListBoxModel();
        items.add("Inherited", null);
        items.add("SSH", "SSH");
        items.add("JNLP", "JNLP");

        return items;
    }

    @Restricted(DoNotUse.class)
    public ListBoxModel doFillCredentialsIdItems(@AncestorInPath ItemGroup context) {
        if (!(context instanceof AccessControlled ? (AccessControlled) context : Jenkins.getInstance()).hasPermission(Computer.CONFIGURE)) {
            return new ListBoxModel();
        }
        return new StandardUsernameListBoxModel().withMatching(SSHAuthenticator.matcher(Connection.class),
                CredentialsProvider.lookupCredentials(StandardUsernameCredentials.class, context,
                        ACL.SYSTEM, SSHLauncher.SSH_SCHEME));
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

    private ConfigProvider getConfigProvider() {
        ExtensionList<ConfigProvider> providers = ConfigProvider.all();
        return providers.get(UserDataConfig.UserDataConfigProvider.class);
    }

    /**
     * Get Default value label.
     */
    @Restricted(DoNotUse.class) // For view
    public @Nonnull String def(@CheckForNull Object val) {
        return val == null ? "" : ("Inherited vlaue: " + val);
    }

}
