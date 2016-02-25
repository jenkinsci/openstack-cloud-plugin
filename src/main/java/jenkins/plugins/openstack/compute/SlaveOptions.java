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
import hudson.Util;
import hudson.model.Describable;
import hudson.util.FormValidation;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.stapler.QueryParameter;

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
public class SlaveOptions implements Describable<SlaveOptions> {

    // Provisioning attributes
    private final @CheckForNull String imageId;
    private final @CheckForNull String hardwareId;
    private final @CheckForNull String networkId;
    private final @CheckForNull String userDataId;
    private final @CheckForNull Integer instanceCap;
    private final @CheckForNull Boolean floatingIps;
    private final @CheckForNull String securityGroups;
    private final @CheckForNull String availabilityZone;
    private final @CheckForNull Integer startTimeout;

    // Slave launch attributes
    private final @CheckForNull Integer numExecutors;
    private final @CheckForNull String jvmOptions;
    private final @CheckForNull String fsRoot;
    private final @CheckForNull String keyPairName;
    private final @CheckForNull String credentialsId;
    private final @CheckForNull JCloudsCloud.SlaveType slaveType;

    // Slave attributes
    private final @CheckForNull Integer retentionTime;

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

    public @CheckForNull Integer getInstanceCap() {
        return instanceCap;
    }

    public @CheckForNull Boolean isFloatingIps() {
        return floatingIps;
    }

    public @CheckForNull String getSecurityGroups() {
        return securityGroups;
    }

    public @CheckForNull String getAvailabilityZone() {
        return availabilityZone;
    }

    public @CheckForNull Integer getStartTimeout() {
        return startTimeout;
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

    public SlaveOptions(Builder b) {
        this(
                b.imageId,
                b.hardwareId,
                b.networkId,
                b.userDataId,
                b.instanceCap,
                b.floatingIps,
                b.securityGroups,
                b.availabilityZone,
                b.startTimeout,
                b.numExecutors,
                b.jvmOptions,
                b.fsRoot,
                b.keyPairName,
                b.credentialsId,
                b.slaveType,
                b.retentionTime
        );
    }

    //@DataBoundConstructor @Restricted(NoExternalUse.class)
    public SlaveOptions(
            String imageId,
            String hardwareId,
            String networkId,
            String userDataId,
            Integer instanceCap,
            Boolean floatingIps,
            String securityGroups,
            String availabilityZone,
            Integer startTimeout,
            Integer numExecutors,
            String jvmOptions,
            String fsRoot,
            String keyPairName,
            String credentialsId,
            JCloudsCloud.SlaveType slaveType,
            Integer retentionTime
    ) {
        this.imageId = imageId;
        this.hardwareId = hardwareId;
        this.networkId = networkId;
        this.userDataId = userDataId;
        this.instanceCap = instanceCap;
        this.floatingIps = floatingIps;
        this.securityGroups = securityGroups;
        this.availabilityZone = availabilityZone;
        this.startTimeout = startTimeout;
        this.numExecutors = numExecutors;
        this.jvmOptions = jvmOptions;
        this.fsRoot = fsRoot;
        this.keyPairName = keyPairName;
        this.credentialsId = credentialsId;
        this.slaveType = slaveType;
        this.retentionTime = retentionTime;
    }

    public @Nonnull SlaveOptions override(@Nonnull SlaveOptions o) {
        return new Builder()
                .imageId(_override(this.imageId, o.imageId))
                .hardwareId(_override(this.hardwareId, o.hardwareId))
                .networkId(_override(this.networkId, o.networkId))
                .userDataId(_override(this.userDataId, o.userDataId))
                .instanceCap(_override(this.instanceCap, o.instanceCap)) // TODO: this is not right for instance cap
                .floatingIps(_override(this.floatingIps, o.floatingIps))
                .securityGroups(_override(this.securityGroups, o.securityGroups))
                .availabilityZone(_override(this.availabilityZone, o.availabilityZone))
                .startTimeout(_override(this.startTimeout, o.startTimeout))
                .numExecutors(_override(this.numExecutors, o.numExecutors))
                .jvmOptions(_override(this.jvmOptions, o.jvmOptions))
                .fsRoot(_override(this.fsRoot, o.fsRoot))
                .keyPairName(_override(this.keyPairName, o.keyPairName))
                .credentialsId(_override(this.credentialsId, o.credentialsId))
                .slaveType(_override(this.slaveType, o.slaveType))
                .retentionTime(_override(this.retentionTime, o.retentionTime))
                .build()
        ;
    }

    private <T> T _override(@CheckForNull T base, @CheckForNull T override) {
        return override == null ? base : override;
    }

    @Override
    public String toString() {
        return "SlaveOptions{" +
                "imageId='" + imageId + '\'' +
                ", hardwareId='" + hardwareId + '\'' +
                ", networkId='" + networkId + '\'' +
                ", userDataId='" + userDataId + '\'' +
                ", floatingIps=" + floatingIps +
                ", securityGroups='" + securityGroups + '\'' +
                ", availabilityZone='" + availabilityZone + '\'' +
                ", startTimeout=" + startTimeout +
                ", numExecutors=" + numExecutors +
                ", jvmOptions='" + jvmOptions + '\'' +
                ", fsRoot='" + fsRoot + '\'' +
                ", keyPairName='" + keyPairName + '\'' +
                ", credentialsId='" + credentialsId + '\'' +
                ", slaveType=" + slaveType +
                ", retentionTime=" + retentionTime +
                '}';
    }

    public static @Nonnull Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private @CheckForNull String imageId;
        private @CheckForNull String hardwareId;
        private @CheckForNull String networkId;
        private @CheckForNull String userDataId;
        private @CheckForNull Integer instanceCap;
        private @CheckForNull Boolean floatingIps;
        private @CheckForNull String securityGroups;
        private @CheckForNull String availabilityZone;

        private @CheckForNull Integer startTimeout;
        private @CheckForNull Integer numExecutors;
        private @CheckForNull String jvmOptions;
        private @CheckForNull String fsRoot;
        private @CheckForNull String keyPairName;
        private @CheckForNull String credentialsId;

        private @CheckForNull JCloudsCloud.SlaveType slaveType;
        private @CheckForNull Integer retentionTime;

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

        public @Nonnull Builder instanceCap(Integer instanceCap) {
            this.instanceCap = instanceCap;
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

        public @Nonnull Builder startTimeout(Integer startTimeout) {
            this.startTimeout = startTimeout;
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
    }

    @Override
    public Descriptor getDescriptor() {
        return (Descriptor) Jenkins.getInstance().getDescriptorOrDie(getClass());
    }

    @Extension
    public static final class Descriptor extends hudson.model.Descriptor<SlaveOptions> {

        @Override
        public String getDisplayName() {
            return "Slave Options";
        }

        @Restricted(DoNotUse.class)
        public FormValidation doCheckStartTimeout(@QueryParameter String value) {
            return FormValidation.validatePositiveInteger(value);
        }

        @Restricted(DoNotUse.class)
        public FormValidation doCheckRetentionTime(@QueryParameter String value) {
            try {
                if (Integer.parseInt(value) == -1)
                    return FormValidation.ok();
            } catch (NumberFormatException e) {
            }
            return FormValidation.validateNonNegativeInteger(value);
        }
    }
}
