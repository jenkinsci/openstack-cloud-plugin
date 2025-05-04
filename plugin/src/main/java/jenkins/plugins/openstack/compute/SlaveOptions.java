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

import com.google.common.collect.Lists;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Util;
import hudson.model.Describable;
import hudson.slaves.NodeProperty;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import jenkins.model.Jenkins;
import jenkins.plugins.openstack.compute.slaveopts.BootSource;
import jenkins.plugins.openstack.compute.slaveopts.LauncherFactory;
import org.apache.commons.lang.builder.ToStringBuilder;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Configured options for a slave to create.
 *
 * This object can be scoped to cloud or template (or perhaps some other things). Whenever details are needed to provision/connect
 * particular slave, the most specific SlaveOptions object should be used.
 *
 * @author ogondza.
 */
public class SlaveOptions implements Describable<SlaveOptions>, Serializable {
    private static final long serialVersionUID = -1L;
    private static final SlaveOptions EMPTY = new SlaveOptions(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

    // Provisioning attributes
    private /*final*/ @CheckForNull BootSource bootSource;
    private final @CheckForNull String hardwareId;
    private final @CheckForNull String networkId; // csv list of networkIds, in fact
    private final @CheckForNull String userDataId;
    private final Integer instanceCap;
    private final Integer instancesMin;
    private final @CheckForNull String floatingIpPool;
    private final String securityGroups;
    private final @CheckForNull String availabilityZone;
    private final Integer startTimeout;
    private final @CheckForNull String keyPairName;

    // Slave launch attributes
    private final Integer numExecutors;
    private final @CheckForNull String jvmOptions;
    private final String fsRoot;
    private final LauncherFactory launcherFactory;

    @SuppressFBWarnings("SE_BAD_FIELD")
    private final @CheckForNull ArrayList<NodeProperty<?>> nodeProperties;

    // Moved into LauncherFactory. Converted to string for the ease of conversion. Note that due to inheritance
    // implemented,
    // the migration needs to be implemented by the holder so this is package protected.
    /*package*/ @Deprecated
    @SuppressWarnings("DeprecatedIsStillUsed")
    transient String slaveType;
    /*package*/ @Deprecated
    transient String credentialsId;

    // Slave attributes
    private final Integer retentionTime;

    private final @CheckForNull Boolean configDrive;

    // Replaced by BootSource
    @Deprecated
    @SuppressWarnings("DeprecatedIsStillUsed")
    private transient @CheckForNull String imageId;

    public @CheckForNull String getFsRoot() {
        return fsRoot;
    }

    public @CheckForNull BootSource getBootSource() {
        return bootSource;
    }

    public @CheckForNull String getHardwareId() {
        return hardwareId;
    }

    public @CheckForNull String getNetworkId() {
        return networkId;
    }

    public @CheckForNull String getUserDataId() {
        return userDataId;
    }

    public Integer getInstanceCap() {
        return instanceCap;
    }

    public Integer getInstancesMin() {
        return instancesMin;
    }

    public @CheckForNull String getFloatingIpPool() {
        return floatingIpPool;
    }

    public String getSecurityGroups() {
        return securityGroups;
    }

    public @CheckForNull String getAvailabilityZone() {
        return availabilityZone;
    }

    public Integer getStartTimeout() {
        return startTimeout;
    }

    public @CheckForNull String getKeyPairName() {
        return keyPairName;
    }

    public Integer getNumExecutors() {
        return numExecutors;
    }

    public @CheckForNull String getJvmOptions() {
        return jvmOptions;
    }

    public LauncherFactory getLauncherFactory() {
        return launcherFactory;
    }

    public @CheckForNull List<NodeProperty<?>> getNodeProperties() {
        return nodeProperties;
    }

    public Integer getRetentionTime() {
        return retentionTime;
    }

    public @CheckForNull Boolean getConfigDrive() {
        return configDrive;
    }

    public SlaveOptions(Builder b) {
        this(
                b.bootSource,
                b.hardwareId,
                b.networkId,
                b.userDataId,
                b.instanceCap,
                b.instancesMin,
                b.floatingIpPool,
                b.securityGroups,
                b.availabilityZone,
                b.startTimeout,
                b.keyPairName,
                b.numExecutors,
                b.jvmOptions,
                b.fsRoot,
                b.launcherFactory,
                b.nodeProperties,
                b.retentionTime,
                b.configDrive);
    }

    @DataBoundConstructor
    @Restricted(NoExternalUse.class)
    public SlaveOptions(
            @CheckForNull BootSource bootSource,
            String hardwareId,
            String networkId,
            String userDataId,
            Integer instanceCap,
            Integer instancesMin,
            String floatingIpPool,
            String securityGroups,
            String availabilityZone,
            Integer startTimeout,
            String keyPairName,
            Integer numExecutors,
            String jvmOptions,
            String fsRoot,
            LauncherFactory launcherFactory,
            @CheckForNull List<? extends NodeProperty<?>> nodeProperties,
            Integer retentionTime,
            @CheckForNull Boolean configDrive) {
        this.bootSource = bootSource;
        this.hardwareId = Util.fixEmpty(hardwareId);
        this.networkId = Util.fixEmpty(networkId);
        this.userDataId = Util.fixEmpty(userDataId);
        this.instanceCap = instanceCap;
        this.instancesMin = instancesMin;
        this.floatingIpPool = Util.fixEmpty(floatingIpPool);
        this.securityGroups = Util.fixEmpty(securityGroups);
        this.availabilityZone = Util.fixEmpty(availabilityZone);
        this.startTimeout = startTimeout;
        this.keyPairName = Util.fixEmpty(keyPairName);
        this.numExecutors = numExecutors;
        this.jvmOptions = Util.fixEmpty(jvmOptions);
        this.fsRoot = Util.fixEmpty(fsRoot);
        this.launcherFactory = launcherFactory;
        if (nodeProperties != null) {
            this.nodeProperties = Lists.newArrayList(nodeProperties);
        } else {
            this.nodeProperties = null;
        }
        this.retentionTime = retentionTime;
        this.configDrive = configDrive;
    }

    private Object readResolve() {
        if (bootSource == null && imageId != null) {
            bootSource = new BootSource.Image(imageId);
        }
        imageId = null;
        return this;
    }

    /**
     * Derive SlaveOptions taking this instance as baseline and overriding with argument.
     */
    public @Nonnull SlaveOptions override(@Nonnull SlaveOptions o) {
        return new Builder()
                .bootSource(_override(this.bootSource, o.bootSource))
                .hardwareId(_override(this.hardwareId, o.hardwareId))
                .networkId(_override(this.networkId, o.networkId))
                .userDataId(_override(this.userDataId, o.userDataId))
                .instanceCap(_override(this.instanceCap, o.instanceCap))
                .instancesMin(_override(this.instancesMin, o.instancesMin))
                .floatingIpPool(_override(this.floatingIpPool, o.floatingIpPool))
                .securityGroups(_override(this.securityGroups, o.securityGroups))
                .availabilityZone(_override(this.availabilityZone, o.availabilityZone))
                .startTimeout(_override(this.startTimeout, o.startTimeout))
                .keyPairName(_override(this.keyPairName, o.keyPairName))
                .numExecutors(_override(this.numExecutors, o.numExecutors))
                .jvmOptions(_override(this.jvmOptions, o.jvmOptions))
                .fsRoot(_override(this.fsRoot, o.fsRoot))
                .launcherFactory(_override(this.launcherFactory, o.launcherFactory))
                .nodeProperties(_override(this.nodeProperties, o.nodeProperties))
                .retentionTime(_override(this.retentionTime, o.retentionTime))
                .configDrive(_override(this.configDrive, o.configDrive))
                .build();
    }

    private @CheckForNull <T> T _override(@CheckForNull T base, @CheckForNull T override) {
        return override == null ? base : override;
    }

    /**
     * Derive new options from current leaving <code>null</code> where same as default.
     */
    public @Nonnull SlaveOptions eraseDefaults(@Nonnull SlaveOptions defaults) {
        return new Builder()
                .bootSource(_erase(this.bootSource, defaults.bootSource))
                .hardwareId(_erase(this.hardwareId, defaults.hardwareId))
                .networkId(_erase(this.networkId, defaults.networkId))
                .userDataId(_erase(this.userDataId, defaults.userDataId))
                .instanceCap(_erase(this.instanceCap, defaults.instanceCap))
                .instancesMin(_erase(this.instancesMin, defaults.instancesMin))
                .floatingIpPool(_erase(this.floatingIpPool, defaults.floatingIpPool))
                .securityGroups(_erase(this.securityGroups, defaults.securityGroups))
                .availabilityZone(_erase(this.availabilityZone, defaults.availabilityZone))
                .startTimeout(_erase(this.startTimeout, defaults.startTimeout))
                .keyPairName(_erase(this.keyPairName, defaults.keyPairName))
                .numExecutors(_erase(this.numExecutors, defaults.numExecutors))
                .jvmOptions(_erase(this.jvmOptions, defaults.jvmOptions))
                .fsRoot(_erase(this.fsRoot, defaults.fsRoot))
                .launcherFactory(_erase(this.launcherFactory, defaults.launcherFactory))
                .nodeProperties(_erase(this.nodeProperties, defaults.nodeProperties))
                .retentionTime(_erase(this.retentionTime, defaults.retentionTime))
                .configDrive(_erase(this.configDrive, defaults.configDrive))
                .build();
    }

    /** Returns null if our <code>base</code> value is the same as the <code>def</code>ault value. */
    private @CheckForNull <T> T _erase(@CheckForNull T base, @CheckForNull T def) {
        if (def == null) return base;
        if (def.equals(base)) return null;
        return base;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this)
                .append("bootSource", bootSource)
                .append("hardwareId", hardwareId)
                .append("networkId", networkId)
                .append("userDataId", userDataId)
                .append("instanceCap", instanceCap)
                .append("instancesMin", instancesMin)
                .append("floatingIpPool", floatingIpPool)
                .append("securityGroups", securityGroups)
                .append("availabilityZone", availabilityZone)
                .append("startTimeout", startTimeout)
                .append("keyPairName", keyPairName)
                .append("numExecutors", numExecutors)
                .append("jvmOptions", jvmOptions)
                .append("fsRoot", fsRoot)
                .append("launcherFactory", launcherFactory)
                .append("nodeProperties", nodeProperties)
                .append("retentionTime", retentionTime)
                .append("configDrive", configDrive)
                .toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        SlaveOptions that = (SlaveOptions) o;

        if (!Objects.equals(bootSource, that.bootSource)) return false;
        if (!Objects.equals(hardwareId, that.hardwareId)) return false;
        if (!Objects.equals(networkId, that.networkId)) return false;
        if (!Objects.equals(userDataId, that.userDataId)) return false;
        if (!Objects.equals(instanceCap, that.instanceCap)) return false;
        if (!Objects.equals(instancesMin, that.instancesMin)) return false;
        if (!Objects.equals(floatingIpPool, that.floatingIpPool)) return false;
        if (!Objects.equals(securityGroups, that.securityGroups)) return false;
        if (!Objects.equals(availabilityZone, that.availabilityZone)) return false;
        if (!Objects.equals(startTimeout, that.startTimeout)) return false;
        if (!Objects.equals(keyPairName, that.keyPairName)) return false;
        if (!Objects.equals(numExecutors, that.numExecutors)) return false;
        if (!Objects.equals(jvmOptions, that.jvmOptions)) return false;
        if (!Objects.equals(fsRoot, that.fsRoot)) return false;
        if (!Objects.equals(launcherFactory, that.launcherFactory)) return false;
        if (!Objects.equals(nodeProperties, that.nodeProperties)) return false;
        if (!Objects.equals(retentionTime, that.retentionTime)) return false;
        return Objects.equals(configDrive, that.configDrive);
    }

    @Override
    public int hashCode() {
        int result = bootSource != null ? bootSource.hashCode() : 0;
        result = 31 * result + (hardwareId != null ? hardwareId.hashCode() : 0);
        result = 31 * result + (networkId != null ? networkId.hashCode() : 0);
        result = 31 * result + (userDataId != null ? userDataId.hashCode() : 0);
        result = 31 * result + (instanceCap != null ? instanceCap.hashCode() : 0);
        result = 31 * result + (instancesMin != null ? instancesMin.hashCode() : 0);
        result = 31 * result + (floatingIpPool != null ? floatingIpPool.hashCode() : 0);
        result = 31 * result + (securityGroups != null ? securityGroups.hashCode() : 0);
        result = 31 * result + (availabilityZone != null ? availabilityZone.hashCode() : 0);
        result = 31 * result + (startTimeout != null ? startTimeout.hashCode() : 0);
        result = 31 * result + (keyPairName != null ? keyPairName.hashCode() : 0);
        result = 31 * result + (numExecutors != null ? numExecutors.hashCode() : 0);
        result = 31 * result + (jvmOptions != null ? jvmOptions.hashCode() : 0);
        result = 31 * result + (fsRoot != null ? fsRoot.hashCode() : 0);
        result = 31 * result + (launcherFactory != null ? launcherFactory.hashCode() : 0);
        result = 31 * result + (nodeProperties != null ? nodeProperties.hashCode() : 0);
        result = 31 * result + (retentionTime != null ? retentionTime.hashCode() : 0);
        result = 31 * result + (configDrive != null ? configDrive.hashCode() : 0);
        return result;
    }

    /**
     * Get builder to create modified version of current instance.
     */
    public Builder getBuilder() {
        return new Builder()
                .bootSource(bootSource)
                .hardwareId(hardwareId)
                .networkId(networkId)
                .userDataId(userDataId)
                .instanceCap(instanceCap)
                .instancesMin(instancesMin)
                .floatingIpPool(floatingIpPool)
                .securityGroups(securityGroups)
                .availabilityZone(availabilityZone)
                .startTimeout(startTimeout)
                .keyPairName(keyPairName)
                .numExecutors(numExecutors)
                .jvmOptions(jvmOptions)
                .fsRoot(fsRoot)
                .launcherFactory(launcherFactory)
                .nodeProperties(nodeProperties)
                .retentionTime(retentionTime)
                .configDrive(configDrive);
    }

    public static @Nonnull SlaveOptions empty() {
        return EMPTY;
    }

    /**
     * Get empty builder.
     */
    public static @Nonnull Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private @CheckForNull BootSource bootSource;
        private @CheckForNull String hardwareId;
        private @CheckForNull String networkId;
        private @CheckForNull String userDataId;
        private @CheckForNull Integer instanceCap;
        private @CheckForNull Integer instancesMin;
        private @CheckForNull String floatingIpPool;
        private @CheckForNull String securityGroups;
        private @CheckForNull String availabilityZone;
        private @CheckForNull Integer startTimeout;
        private @CheckForNull String keyPairName;

        private @CheckForNull Integer numExecutors;
        private @CheckForNull String jvmOptions;
        private @CheckForNull String fsRoot;

        private @CheckForNull LauncherFactory launcherFactory;
        private @CheckForNull List<? extends NodeProperty<?>> nodeProperties;
        private @CheckForNull Integer retentionTime;
        private @CheckForNull Boolean configDrive;

        public Builder() {}

        public @Nonnull SlaveOptions build() {
            return new SlaveOptions(this);
        }

        public @Nonnull Builder bootSource(BootSource bootSource) {
            this.bootSource = bootSource;
            return this;
        }

        public @Nonnull Builder hardwareId(String hardwareId) {
            this.hardwareId = hardwareId;
            return this;
        }

        public @Nonnull Builder networkId(String networkId) {
            this.networkId = networkId;
            return this;
        }

        public @Nonnull Builder userDataId(String userDataId) {
            this.userDataId = userDataId;
            return this;
        }

        public @Nonnull Builder instanceCap(Integer instanceCap) {
            this.instanceCap = instanceCap;
            return this;
        }

        public @Nonnull Builder instancesMin(Integer instancesMin) {
            this.instancesMin = instancesMin;
            return this;
        }

        public @Nonnull Builder floatingIpPool(String floatingIpPool) {
            this.floatingIpPool = floatingIpPool;
            return this;
        }

        public @Nonnull Builder securityGroups(String securityGroups) {
            this.securityGroups = securityGroups;
            return this;
        }

        public @Nonnull Builder availabilityZone(String availabilityZone) {
            this.availabilityZone = availabilityZone;
            return this;
        }

        public @Nonnull Builder startTimeout(Integer startTimeout) {
            this.startTimeout = startTimeout;
            return this;
        }

        public @Nonnull Builder keyPairName(String keyPairName) {
            this.keyPairName = keyPairName;
            return this;
        }

        public @Nonnull Builder numExecutors(Integer numExecutors) {
            this.numExecutors = numExecutors;
            return this;
        }

        public @Nonnull Builder jvmOptions(String jvmOptions) {
            this.jvmOptions = jvmOptions;
            return this;
        }

        public @Nonnull Builder fsRoot(String fsRoot) {
            this.fsRoot = fsRoot;
            return this;
        }

        public @Nonnull Builder launcherFactory(LauncherFactory launcherFactory) {
            this.launcherFactory = launcherFactory;
            return this;
        }

        public @Nonnull Builder nodeProperties(List<? extends NodeProperty<?>> nodeProperties) {
            this.nodeProperties = nodeProperties;
            return this;
        }

        public @Nonnull Builder retentionTime(Integer retentionTime) {
            this.retentionTime = retentionTime;
            return this;
        }

        public @Nonnull Builder configDrive(Boolean configDrive) {
            this.configDrive = configDrive;
            return this;
        }
    }

    /**
     * Interface to be implemented by configurable entity that contains options for provisioned slave.
     *
     * By default, this is implemented by cloud and template where templates inherit from cloud.
     */
    public interface Holder {
        /**
         * Get effective options declared by this object.
         *
         * This is supposed to correctly evaluate all the overriding.
         */
        @Nonnull
        SlaveOptions getEffectiveSlaveOptions();

        /**
         * Get configured options held by this object.
         *
         * This holds only the user configured diffs compared to parent.
         */
        @Nonnull
        SlaveOptions getRawSlaveOptions();
    }

    @Override
    public SlaveOptionsDescriptor getDescriptor() {
        return (SlaveOptionsDescriptor) Jenkins.get().getDescriptorOrDie(getClass());
    }
}
