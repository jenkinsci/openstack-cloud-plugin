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
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

/**
 * Configured options for a slave to create.
 *
 * This object can be scoped to cloud or template (or perhaps some other things). Whenever details are needed to provision/connect
 * particular slave, the most specific SlaveOptions object should be used.
 *
 * @author ogondza.
 */
public class SlaveOptions {

    // Provisioning attributes
    private final @CheckForNull String imageId;
    private final @CheckForNull String hardwareId;
    private final @CheckForNull String networkId;
    private final @CheckForNull String userDataId;
    // Ask for a floating IP to be associated for every machine provisioned
    private final @CheckForNull Boolean floatingIps;
    private final @CheckForNull String securityGroups;
    private final @CheckForNull String availabilityZone;

    // Slave launch attributes
    private final @CheckForNull Integer numExecutors;
    private final @CheckForNull String jvmOptions;
    private final @CheckForNull String fsRoot;
    private final @CheckForNull String keyPairName;
    private final @CheckForNull String credentialsId;
    private final @CheckForNull JCloudsCloud.SlaveType slaveType;

    // Slave attributes
    private final @CheckForNull Integer retentionTime;
    private final @CheckForNull Integer startTimeout;

    public @CheckForNull String getFsRoot() {
        return fsRoot;
    }

    public @CheckForNull String getImageId() {
        return imageId;
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

    public @CheckForNull Boolean getFloatingIps() {
        return floatingIps;
    }

    public @CheckForNull String getSecurityGroups() {
        return securityGroups;
    }

    public @CheckForNull String getAvailabilityZone() {
        return availabilityZone;
    }

    public @CheckForNull Integer getNumExecutors() {
        return numExecutors;
    }

    public @CheckForNull String getJvmOptions() {
        return jvmOptions;
    }

    public @CheckForNull String getKeyPairName() {
        return keyPairName;
    }

    public @CheckForNull String getCredentialsId() {
        return credentialsId;
    }

    public @CheckForNull JCloudsCloud.SlaveType getSlaveType() {
        return slaveType;
    }

    public @CheckForNull Integer getRetentionTime() {
        return retentionTime;
    }

    public @CheckForNull Integer getStartTimeout() {
        return startTimeout;
    }

    public SlaveOptions(Builder b) {
        this(
                b.imageId,
                b.hardwareId,
                b.networkId,
                b.userDataId,
                b.floatingIps,
                b.securityGroups,
                b.availabilityZone,
                b.numExecutors,
                b.jvmOptions,
                b.fsRoot,
                b.keyPairName,
                b.credentialsId,
                b.slaveType,
                b.retentionTime,
                b.startTimeout
        );
    }

    //@DataBoundConstructor @Restricted(NoExternalUse.class)
    public SlaveOptions(
            String imageId,
            String hardwareId,
            String networkId,
            String userDataId,
            Boolean floatingIps,
            String securityGroups,
            String availabilityZone,
            Integer numExecutors,
            String jvmOptions,
            String fsRoot,
            String keyPairName,
            String credentialsId,
            JCloudsCloud.SlaveType slaveType,
            Integer retentionTime,
            Integer startTimeout
    ) {
        this.imageId = imageId;
        this.hardwareId = hardwareId;
        this.networkId = networkId;
        this.userDataId = userDataId;
        this.floatingIps = floatingIps;
        this.securityGroups = securityGroups;
        this.availabilityZone = availabilityZone;
        this.numExecutors = numExecutors;
        this.jvmOptions = jvmOptions;
        this.fsRoot = fsRoot;
        this.keyPairName = keyPairName;
        this.credentialsId = credentialsId;
        this.slaveType = slaveType;
        this.retentionTime = retentionTime;
        this.startTimeout = startTimeout;
    }

    public @Nonnull SlaveOptions override(@Nonnull SlaveOptions o) {
        return new Builder()
                .imageId(_override(this.imageId, o.imageId))
                .hardwareId(_override(this.hardwareId, o.hardwareId))
                .networkId(_override(this.networkId, o.networkId))
                .userDataId(_override(this.userDataId, o.userDataId))
                .floatingIps(_override(this.floatingIps, o.floatingIps))
                .securityGroups(_override(this.securityGroups, o.securityGroups))
                .availabilityZone(_override(this.availabilityZone, o.availabilityZone))
                .numExecutors(_override(this.numExecutors, o.numExecutors))
                .jvmOptions(_override(this.jvmOptions, o.jvmOptions))
                .fsRoot(_override(this.fsRoot, o.fsRoot))
                .keyPairName(_override(this.keyPairName, o.keyPairName))
                .credentialsId(_override(this.credentialsId, o.credentialsId))
                .slaveType(_override(this.slaveType, o.slaveType))
                .retentionTime(_override(this.retentionTime, o.retentionTime))
                .startTimeout(_override(this.startTimeout, o.startTimeout))
                .build()
        ;
    }

    private <T> T _override(@CheckForNull T base, @CheckForNull T override) {
        return override == null ? base : override;

    }

    public static @Nonnull Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private @CheckForNull String imageId;
        private @CheckForNull String hardwareId;
        private @CheckForNull String networkId;
        private @CheckForNull String userDataId;
        private @CheckForNull Boolean floatingIps;
        private @CheckForNull String securityGroups;
        private @CheckForNull String availabilityZone;
        private @CheckForNull Integer numExecutors;
        private @CheckForNull String jvmOptions;
        private @CheckForNull String fsRoot;
        private @CheckForNull String keyPairName;
        private @CheckForNull String credentialsId;
        private @CheckForNull JCloudsCloud.SlaveType slaveType;
        private @CheckForNull Integer retentionTime;
        private @CheckForNull Integer startTimeout;

        public Builder() {}

        public @Nonnull SlaveOptions build() {
            return new SlaveOptions(this);
        }

        public @Nonnull Builder imageId(String imageId) {
            this.imageId = Util.fixEmpty(imageId);
            return this;
        }

        public @Nonnull Builder hardwareId(String hardwareId) {
            this.hardwareId = Util.fixEmpty(hardwareId);
            return this;
        }

        public @Nonnull Builder networkId(String networkId) {
            this.networkId = Util.fixEmpty(networkId);
            return this;
        }

        public @Nonnull Builder userDataId(String userDataId) {
            this.userDataId = Util.fixEmpty(userDataId);
            return this;
        }

        public @Nonnull Builder floatingIps(Boolean floatingIps) {
            this.floatingIps = floatingIps;
            return this;
        }

        public @Nonnull Builder securityGroups(String securityGroups) {
            this.securityGroups = Util.fixEmpty(securityGroups);
            return this;
        }

        public @Nonnull Builder availabilityZone(String availabilityZone) {
            this.availabilityZone = Util.fixEmpty(availabilityZone);
            return this;
        }

        public @Nonnull Builder numExecutors(Integer numExecutors) {
            this.numExecutors = numExecutors;
            return this;
        }

        public @Nonnull Builder jvmOptions(String jvmOptions) {
            this.jvmOptions = Util.fixEmpty(jvmOptions);
            return this;
        }

        public @Nonnull Builder fsRoot(String fsRoot) {
            this.fsRoot = Util.fixEmpty(fsRoot);
            return this;
        }

        public @Nonnull Builder keyPairName(String keyPairName) {
            this.keyPairName = Util.fixEmpty(keyPairName);
            return this;
        }

        public @Nonnull Builder credentialsId(String credentialsId) {
            this.credentialsId = Util.fixEmpty(credentialsId);
            return this;
        }

        public @Nonnull Builder slaveType(JCloudsCloud.SlaveType slaveType) {
            this.slaveType = slaveType;
            return this;
        }

        public @Nonnull Builder retentionTime(Integer retentionTime) {
            this.retentionTime = retentionTime;
            return this;
        }

        public @Nonnull Builder startTimeout(Integer startTimeout) {
            this.startTimeout = startTimeout;
            return this;
        }
    }
}
