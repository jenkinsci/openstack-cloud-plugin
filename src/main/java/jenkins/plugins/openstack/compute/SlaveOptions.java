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

import hudson.Util;
import hudson.model.Describable;
import jenkins.model.Jenkins;
import jenkins.plugins.openstack.compute.slaveopts.BootSource;
import jenkins.plugins.openstack.compute.slaveopts.LauncherFactory;
import org.apache.commons.lang.builder.ToStringBuilder;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.io.Serializable;

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
    private static final SlaveOptions EMPTY = new SlaveOptions(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

    // Provisioning attributes
    private /*final*/ @CheckForNull BootSource bootSource;
    private final @CheckForNull String hardwareId;
    private final @CheckForNull String networkId; // csv list of networkIds, in fact
    private final @CheckForNull String userDataId;
    private final Integer instanceCap;
    private final @CheckForNull String floatingIpPool;
    private final String securityGroups;
    private final @CheckForNull String availabilityZone;
    private final Integer startTimeout;
    private final @CheckForNull String keyPairName;

    // Slave launch attributes
    private final Integer numExecutors;
    private final @CheckForNull String jvmOptions;
    private final String fsRoot;
    private /*final*/ LauncherFactory launcherFactory;

    // Moved into LauncherFactory. Converted to string for the ease of conversion. Note that due to inheritance implemented,
    // the migration needs to be implemented by the holder so this is package protected.
    /*package*/ @Deprecated transient String slaveType;
    /*package*/ @Deprecated transient String credentialsId;

    // Slave attributes
    private final Integer retentionTime;

    // Replaced by BootSource
    @Deprecated private transient @CheckForNull String imageId;

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

    public Integer getRetentionTime() {
        return retentionTime;
    }

    public SlaveOptions(Builder b) {
        this(
                b.bootSource,
                b.hardwareId,
                b.networkId,
                b.userDataId,
                b.instanceCap,
                b.floatingIpPool,
                b.securityGroups,
                b.availabilityZone,
                b.startTimeout,
                b.keyPairName,
                b.numExecutors,
                b.jvmOptions,
                b.fsRoot,
                b.launcherFactory,
                b.retentionTime
        );
    }

    @DataBoundConstructor @Restricted(NoExternalUse.class)
    public SlaveOptions(
            BootSource bootSource,
            String hardwareId,
            String networkId,
            String userDataId,
            Integer instanceCap,
            String floatingIpPool,
            String securityGroups,
            String availabilityZone,
            Integer startTimeout,
            String keyPairName,
            Integer numExecutors,
            String jvmOptions,
            String fsRoot,
            LauncherFactory launcherFactory,
            Integer retentionTime
    ) {
        this.bootSource = bootSource;
        this.hardwareId = Util.fixEmpty(hardwareId);
        this.networkId = Util.fixEmpty(networkId);
        this.userDataId = Util.fixEmpty(userDataId);
        this.instanceCap = instanceCap;
        this.floatingIpPool = Util.fixEmpty(floatingIpPool);
        this.securityGroups = Util.fixEmpty(securityGroups);
        this.availabilityZone = Util.fixEmpty(availabilityZone);
        this.startTimeout = startTimeout;
        this.keyPairName = Util.fixEmpty(keyPairName);
        this.numExecutors = numExecutors;
        this.jvmOptions = Util.fixEmpty(jvmOptions);
        this.fsRoot = Util.fixEmpty(fsRoot);
        this.launcherFactory = launcherFactory;
        this.retentionTime = retentionTime;
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
                .floatingIpPool(_override(this.floatingIpPool, o.floatingIpPool))
                .securityGroups(_override(this.securityGroups, o.securityGroups))
                .availabilityZone(_override(this.availabilityZone, o.availabilityZone))
                .startTimeout(_override(this.startTimeout, o.startTimeout))
                .keyPairName(_override(this.keyPairName, o.keyPairName))
                .numExecutors(_override(this.numExecutors, o.numExecutors))
                .jvmOptions(_override(this.jvmOptions, o.jvmOptions))
                .fsRoot(_override(this.fsRoot, o.fsRoot))
                .launcherFactory(_override(this.launcherFactory, o.launcherFactory))
                .retentionTime(_override(this.retentionTime, o.retentionTime))
                .build()
        ;
    }

    private @CheckForNull <T> T _override(@CheckForNull T base, @CheckForNull T override) {
        return override == null ? base : override;
    }

    /**
     * Derive new options from current leaving <tt>null</tt> where same as default.
     */
    public @Nonnull SlaveOptions eraseDefaults(@Nonnull SlaveOptions defaults) {
        return new Builder()
                .bootSource(_erase(this.bootSource, defaults.bootSource))
                .hardwareId(_erase(this.hardwareId, defaults.hardwareId))
                .networkId(_erase(this.networkId, defaults.networkId))
                .userDataId(_erase(this.userDataId, defaults.userDataId))
                .instanceCap(_erase(this.instanceCap, defaults.instanceCap))
                .floatingIpPool(_erase(this.floatingIpPool, defaults.floatingIpPool))
                .securityGroups(_erase(this.securityGroups, defaults.securityGroups))
                .availabilityZone(_erase(this.availabilityZone, defaults.availabilityZone))
                .startTimeout(_erase(this.startTimeout, defaults.startTimeout))
                .keyPairName(_erase(this.keyPairName, defaults.keyPairName))
                .numExecutors(_erase(this.numExecutors, defaults.numExecutors))
                .jvmOptions(_erase(this.jvmOptions, defaults.jvmOptions))
                .fsRoot(_erase(this.fsRoot, defaults.fsRoot))
                .launcherFactory(_erase(this.launcherFactory, defaults.launcherFactory))
                .retentionTime(_erase(this.retentionTime, defaults.retentionTime))
                .build()
        ;
    }

    /** Returns null if our <tt>base</tt> value is the same as the <tt>def</tt>ault value. */
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
                .append("floatingIpPool", floatingIpPool)
                .append("securityGroups", securityGroups)
                .append("availabilityZone", availabilityZone)
                .append("startTimeout", startTimeout)
                .append("keyPairName", keyPairName)
                .append("numExecutors", numExecutors)
                .append("jvmOptions", jvmOptions)
                .append("fsRoot", fsRoot)
                .append("launcherFactory", launcherFactory)
                .append("retentionTime", retentionTime)
                .toString()
        ;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        SlaveOptions that = (SlaveOptions) o;

        if (bootSource != null ? !bootSource.equals(that.bootSource) : that.bootSource != null) return false;
        if (hardwareId != null ? !hardwareId.equals(that.hardwareId) : that.hardwareId != null) return false;
        if (networkId != null ? !networkId.equals(that.networkId) : that.networkId != null) return false;
        if (userDataId != null ? !userDataId.equals(that.userDataId) : that.userDataId != null) return false;
        if (instanceCap != null ? !instanceCap.equals(that.instanceCap) : that.instanceCap != null) return false;
        if (floatingIpPool != null ? !floatingIpPool.equals(that.floatingIpPool) : that.floatingIpPool != null) return false;
        if (securityGroups != null ? !securityGroups.equals(that.securityGroups) : that.securityGroups != null) return false;
        if (availabilityZone != null ? !availabilityZone.equals(that.availabilityZone) : that.availabilityZone != null) return false;
        if (startTimeout != null ? !startTimeout.equals(that.startTimeout) : that.startTimeout != null) return false;
        if (keyPairName != null ? !keyPairName.equals(that.keyPairName) : that.keyPairName != null) return false;
        if (numExecutors != null ? !numExecutors.equals(that.numExecutors) : that.numExecutors != null) return false;
        if (jvmOptions != null ? !jvmOptions.equals(that.jvmOptions) : that.jvmOptions != null) return false;
        if (fsRoot != null ? !fsRoot.equals(that.fsRoot) : that.fsRoot != null) return false;
        if (launcherFactory != null ? !launcherFactory.equals(that.launcherFactory) : that.launcherFactory != null) return false;
        return retentionTime != null ? retentionTime.equals(that.retentionTime) : that.retentionTime == null;

    }

    @Override
    public int hashCode() {
        int result = bootSource != null ? bootSource.hashCode() : 0;
        result = 31 * result + (hardwareId != null ? hardwareId.hashCode() : 0);
        result = 31 * result + (networkId != null ? networkId.hashCode() : 0);
        result = 31 * result + (userDataId != null ? userDataId.hashCode() : 0);
        result = 31 * result + (instanceCap != null ? instanceCap.hashCode() : 0);
        result = 31 * result + (floatingIpPool != null ? floatingIpPool.hashCode() : 0);
        result = 31 * result + (securityGroups != null ? securityGroups.hashCode() : 0);
        result = 31 * result + (availabilityZone != null ? availabilityZone.hashCode() : 0);
        result = 31 * result + (startTimeout != null ? startTimeout.hashCode() : 0);
        result = 31 * result + (keyPairName != null ? keyPairName.hashCode() : 0);
        result = 31 * result + (numExecutors != null ? numExecutors.hashCode() : 0);
        result = 31 * result + (jvmOptions != null ? jvmOptions.hashCode() : 0);
        result = 31 * result + (fsRoot != null ? fsRoot.hashCode() : 0);
        result = 31 * result + (launcherFactory != null ? launcherFactory.hashCode() : 0);
        result = 31 * result + (retentionTime != null ? retentionTime.hashCode() : 0);
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
                .floatingIpPool(floatingIpPool)
                .securityGroups(securityGroups)
                .availabilityZone(availabilityZone)
                .startTimeout(startTimeout)
                .keyPairName(keyPairName)
                .numExecutors(numExecutors)
                .jvmOptions(jvmOptions)
                .fsRoot(fsRoot)
                .launcherFactory(launcherFactory)
                .retentionTime(retentionTime)
        ;
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
        private @CheckForNull String floatingIpPool;
        private @CheckForNull String securityGroups;
        private @CheckForNull String availabilityZone;
        private @CheckForNull Integer startTimeout;
        private @CheckForNull String keyPairName;

        private @CheckForNull Integer numExecutors;
        private @CheckForNull String jvmOptions;
        private @CheckForNull String fsRoot;

        private @CheckForNull LauncherFactory launcherFactory;
        private @CheckForNull Integer retentionTime;

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

        public @Nonnull Builder retentionTime(Integer retentionTime) {
            this.retentionTime = retentionTime;
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
        @Nonnull SlaveOptions getEffectiveSlaveOptions();

        /**
         * Get configured options held by this object.
         *
         * This holds only the user configured diffs compared to parent.
         */
        @Nonnull SlaveOptions getRawSlaveOptions();
    }

    @Override
    public SlaveOptionsDescriptor getDescriptor() {
        return (SlaveOptionsDescriptor) Jenkins.getActiveInstance().getDescriptorOrDie(getClass());
    }
}
