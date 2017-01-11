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
import com.cloudbees.plugins.credentials.CredentialsNameProvider;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernameListBoxModel;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
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
import hudson.util.ReflectionUtils;
import jenkins.model.Jenkins;
import jenkins.plugins.openstack.compute.internal.Openstack;
import org.jenkinsci.lib.configprovider.ConfigProvider;
import org.jenkinsci.lib.configprovider.model.Config;
import org.jenkinsci.plugins.configfiles.ConfigFiles;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.QueryParameter;
import org.openstack4j.api.exceptions.AuthenticationException;
import org.openstack4j.api.exceptions.ConnectionException;
import org.openstack4j.model.compute.Flavor;
import org.openstack4j.model.image.Image;
import org.springframework.util.StringUtils;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * @author ogondza.
 */
@Extension @Restricted(NoExternalUse.class)
public final class SlaveOptionsDescriptor extends hudson.model.Descriptor<SlaveOptions> {
    private static final Logger LOGGER = Logger.getLogger(SlaveOptionsDescriptor.class.getName());
    private static final FormValidation OK = FormValidation.ok();
    private static final FormValidation REQUIRED = FormValidation.error(hudson.util.Messages.FormValidation_ValidateRequired());

    public SlaveOptionsDescriptor() {
        super(SlaveOptions.class);
    }

    @Override
    public String getDisplayName() {
        return "Slave Options";
    }

    private SlaveOptions opts() {
        return ((JCloudsCloud.DescriptorImpl) Jenkins.getActiveInstance().getDescriptorOrDie(JCloudsCloud.class)).getDefaultOptions();
    }

    private String getDefault(String d1, Object d2) {
        d1 = Util.fixEmpty(d1);
        if (d1 != null) return d1;
        if (d2 != null) return Util.fixEmpty(String.valueOf(d2));
        return null;
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
            @QueryParameter String endPointUrl, @QueryParameter String identity, @QueryParameter String credential, @QueryParameter String zone
    ) {
        ListBoxModel m = new ListBoxModel();
        m.add("None specified", "");

        try {
            final Openstack openstack = Openstack.Factory.get(endPointUrl, identity, credential, zone);
            for (String p : openstack.getSortedIpPools()) {
                m.add(p);
            }
            return m;
        } catch (AuthenticationException | FormValidation | ConnectionException ex) {
            LOGGER.log(Level.FINEST, "Openstack call failed", ex);
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, ex.getMessage(), ex);
        }

        if (Util.fixEmpty(floatingIpPool) != null) {
            m.add(floatingIpPool);
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
            @QueryParameter String hardwareId,
            @QueryParameter String endPointUrl, @QueryParameter String identity, @QueryParameter String credential, @QueryParameter String zone
    ) {

        ListBoxModel m = new ListBoxModel();
        m.add("None specified", "");

        try {
            final Openstack openstack = Openstack.Factory.get(endPointUrl, identity, credential, zone);
            for (Flavor flavor : openstack.getSortedFlavors()) {
                m.add(String.format("%s (%s)", flavor.getName(), flavor.getId()), flavor.getId());
            }
            return m;
        } catch (AuthenticationException | FormValidation | ConnectionException ex) {
            LOGGER.log(Level.FINEST, "Openstack call failed", ex);
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, ex.getMessage(), ex);
        }

        if (Util.fixEmpty(hardwareId) != null) {
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
    @InjectOsAuth
    public ListBoxModel doFillImageIdItems(
            @QueryParameter String imageId,
            @QueryParameter String endPointUrl, @QueryParameter String identity, @QueryParameter String credential, @QueryParameter String zone
    ) {

        ListBoxModel m = new ListBoxModel();
        m.add("None specified", "");

        try {
            final Openstack openstack = Openstack.Factory.get(endPointUrl, identity, credential, zone);
            for (Image image : openstack.getSortedImages()) {
                String name = image.getName();
                  if (Util.fixEmpty(name) == null) {
                    name = image.getId();
                }
                m.add(name);
            }
            return m;
        } catch (AuthenticationException | FormValidation | ConnectionException ex) {
            LOGGER.log(Level.FINEST, "Openstack call failed", ex);
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, ex.getMessage(), ex);
        }

        if (Util.fixEmpty(imageId) != null) {
            m.add(imageId);
        }

        return m;
    }

    @Restricted(DoNotUse.class)
    public FormValidation doCheckImageId(
            @QueryParameter String value,
            @RelativePath("../../slaveOptions") @QueryParameter("imageId") String def
    ) {
        if (Util.fixEmpty(value) == null) {
            String d = getDefault(def, opts().getImageId());
            if (d != null) return FormValidation.ok(def(d));
            return REQUIRED;
        }
        return OK;
    }

    @Restricted(DoNotUse.class)
    @InjectOsAuth
    public ListBoxModel doFillNetworkIdItems(
            @QueryParameter String networkId,
            @QueryParameter String endPointUrl, @QueryParameter String identity, @QueryParameter String credential, @QueryParameter String zone
    ) {

        ListBoxModel m = new ListBoxModel();
        m.add("None specified", "");

        try {
            Openstack openstack = Openstack.Factory.get(endPointUrl, identity, credential, zone);
            for (org.openstack4j.model.network.Network network : openstack.getSortedNetworks()) {
                m.add(String.format("%s (%s)", network.getName(), network.getId()), network.getId());
            }
            return m;
        } catch (AuthenticationException | FormValidation | ConnectionException ex) {
            LOGGER.log(Level.FINEST, "Openstack call failed", ex);
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, ex.getMessage(), ex);
        }

        if (Util.fixEmpty(networkId) != null) {
            m.add(networkId);
        }

        return m;
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
    public ListBoxModel doFillSlaveTypeItems() {
        ListBoxModel items = new ListBoxModel();
        items.add("None specified", null);
        items.add("SSH", "SSH");
        items.add("JNLP", "JNLP");

        return items;
    }

    @Restricted(DoNotUse.class)
    public FormValidation doCheckSlaveType(
            @QueryParameter String value,
            @RelativePath("../../slaveOptions") @QueryParameter("slaveType") String def
    ) {
        if (Util.fixEmpty(value) == null) {
            String d = getDefault(def, opts().getSlaveType());
            if (d != null) return FormValidation.ok(def(d));
            return OK;
        }
        return OK;
    }

    @Restricted(DoNotUse.class)
    public ListBoxModel doFillCredentialsIdItems(@AncestorInPath ItemGroup context) {
        if (!(context instanceof AccessControlled ? (AccessControlled) context : Jenkins.getActiveInstance()).hasPermission(Computer.CONFIGURE)) {
            return new ListBoxModel();
        }
        List<StandardUsernameCredentials> credentials = CredentialsProvider.lookupCredentials(
                StandardUsernameCredentials.class, context, ACL.SYSTEM, SSHLauncher.SSH_SCHEME
        );
        return new StandardUsernameListBoxModel()
                .withMatching(SSHAuthenticator.matcher(Connection.class), credentials)
                .withEmptySelection()
        ;
    }

    @Restricted(DoNotUse.class)
    public FormValidation doCheckCredentialsId(
            @QueryParameter String value,
            @RelativePath("../../slaveOptions") @QueryParameter("credentialsId") String def
    ) {
        if (Util.fixEmpty(value) == null) {
            String d = getDefault(def, opts().getCredentialsId());
            if (d != null) {
                d = CredentialsNameProvider.name(SSHLauncher.lookupSystemCredentials(d)); // ID to name
                return FormValidation.ok(def(d));
            }
            return REQUIRED;
        }
        return OK;
    }

    @Restricted(DoNotUse.class)
    public ListBoxModel doFillUserDataIdItems(@AncestorInPath ItemGroup context) {

        ListBoxModel m = new ListBoxModel();
        m.add("None specified", "");

        List<Config> configsInContext = ConfigFiles.getConfigsInContext(context, UserDataConfig.UserDataConfigProvider.class);
        for (Config config : configsInContext) {
            m.add(config.name, config.id);
        }

        return m;
    }

    @Restricted(DoNotUse.class)
    public FormValidation doCheckUserDataId(
            @AncestorInPath ItemGroup context,
            @QueryParameter String value,
            @RelativePath("../../slaveOptions") @QueryParameter("userDataId") String def
    ) {
        if (Util.fixEmpty(value) == null) {
            String d = getDefault(def, opts().getUserDataId());
            if (d != null) {
                Config config = ConfigFiles.getByIdOrNull(context, d);
                if(config != null) {
                    d = config.name;
                    return FormValidation.ok(def(d));
                }
            }
            return OK;
        }
        return OK;
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
    public FormValidation doCheckAvailabilityZone(
            @QueryParameter String value,
            @RelativePath("../../slaveOptions") @QueryParameter("availabilityZone") String def
    ) {
        if (Util.fixEmpty(value) == null) {
            String d = getDefault(def, opts().getAvailabilityZone());
            if (d != null) return FormValidation.ok(def(d));
            return OK;
        }
        return OK;
    }

    @Restricted(DoNotUse.class)
    @InjectOsAuth
    public ListBoxModel doFillKeyPairNameItems(
            @QueryParameter String keyPairName,
            @QueryParameter String endPointUrl, @QueryParameter String identity, @QueryParameter String credential, @QueryParameter String zone
    ) {

        ListBoxModel m = new ListBoxModel();
        m.add("None specified", "");

        try {
            Openstack openstack = Openstack.Factory.get(endPointUrl, identity, credential, zone);
            for (String keyPair: openstack.getSortedKeyPairNames()) {
                m.add(keyPair);
            }
            return m;
        } catch (AuthenticationException | FormValidation | ConnectionException ex) {
            LOGGER.log(Level.FINEST, "Openstack call failed", ex);
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, ex.getMessage(), ex);
        }

        if (Util.fixEmpty(keyPairName) != null) {
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

    /**
     * Get Default value label.
     */
    @Restricted(DoNotUse.class) // For view
    public @Nonnull String def(@CheckForNull Object val) {
        return val == null ? "" : ("Inherited value: " + val);
    }

    /**
     * Add dependencies on credentials
     */
    @Override
    public void calcFillSettings(String field, Map<String, Object> attributes) {
        super.calcFillSettings(field, attributes);

        List<String> deps = new ArrayList<>();
        String fillDependsOn = (String) attributes.get("fillDependsOn");
        if (fillDependsOn != null) {
            deps.addAll(Arrays.asList(fillDependsOn.split(" ")));
        }

        String capitalizedFieldName = StringUtils.capitalize(field);
        String methodName = "doFill" + capitalizedFieldName + "Items";
        Method method = ReflectionUtils.getPublicMethodNamed(getClass(), methodName);

        // Replace direct reference to references to possible relative paths
        if (method.getAnnotation(InjectOsAuth.class) != null) {
            for (String attr: Arrays.asList("endPointUrl", "identity", "credential", "zone")) {
                deps.remove(attr);
                deps.add("../" + attr);
                deps.add("../../" + attr);
            }
        }

        attributes.put("fillDependsOn", Joiner.on(' ').join(deps));
    }

    @Retention(RUNTIME)
    @Target({METHOD})
    private @interface InjectOsAuth {}
}
