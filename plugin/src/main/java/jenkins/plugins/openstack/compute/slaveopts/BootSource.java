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
package jenkins.plugins.openstack.compute.slaveopts;

import static jenkins.plugins.openstack.compute.SlaveOptionsDescriptor.REQUIRED;

import hudson.Extension;
import hudson.RelativePath;
import hudson.Util;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nonnull;
import jenkins.plugins.openstack.compute.JCloudsCloud;
import jenkins.plugins.openstack.compute.OsAuthDescriptor;
import jenkins.plugins.openstack.compute.auth.OpenstackCredential;
import jenkins.plugins.openstack.compute.auth.OpenstackCredentials;
import jenkins.plugins.openstack.compute.internal.Openstack;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.interceptor.RequirePOST;
import org.openstack4j.api.Builders;
import org.openstack4j.api.exceptions.AuthenticationException;
import org.openstack4j.api.exceptions.ConnectionException;
import org.openstack4j.model.compute.BDMDestType;
import org.openstack4j.model.compute.BDMSourceType;
import org.openstack4j.model.compute.Server;
import org.openstack4j.model.compute.builder.BlockDeviceMappingBuilder;
import org.openstack4j.model.compute.builder.ServerCreateBuilder;

/**
 * The source media (Image, VolumeSnapshot etc) that the Instance is booted from.
 */
@Restricted(NoExternalUse.class)
public abstract class BootSource extends AbstractDescribableImpl<BootSource> implements Serializable {
    private static final long serialVersionUID = -838838433829383008L;
    private static final Logger LOGGER = Logger.getLogger(BootSource.class.getName());
    private static final String OPENSTACK_BOOTSOURCE_KEY = "jenkins-boot-source";

    /**
     * Configures the given {@link ServerCreateBuilder} to specify that the
     * newly provisioned server should boot from the specified ID.
     *
     * @param builder
     *            The server specification that is under construction. This will
     *            be amended.
     * @param os
     *            Openstack.
     * @throws JCloudsCloud.ProvisioningFailedException
     *             Unable to configure the request. Do not provision.
     */
    public void setServerBootSource(@Nonnull ServerCreateBuilder builder, @Nonnull Openstack os)
            throws JCloudsCloud.ProvisioningFailedException {
        builder.addMetadataItem(OPENSTACK_BOOTSOURCE_KEY, toString());
    }

    /**
     * Called after a server has been provisioned.
     *
     * @param server
     *            The newly-provisioned server.
     * @param openstack
     *            Means of communicating with the OpenStack service.
     * @throws JCloudsCloud.ProvisioningFailedException
     *             Unable to amend the server so it has to be rolled-back.
     */
    public void afterProvisioning(@Nonnull Server server, @Nonnull Openstack openstack)
            throws JCloudsCloud.ProvisioningFailedException {}

    @Override
    public BootSourceDescriptor getDescriptor() {
        return (BootSourceDescriptor) super.getDescriptor();
    }

    protected String selectIdFromListAndLogProblems(List<String> matchingIds, String name, String pluralOfNameType) {
        int size = matchingIds.size();
        final String id;
        if (size < 1) {
            LOGGER.info("NO " + pluralOfNameType + " match name '" + name + "'.");
            id = name;
        } else if (size == 1) {
            id = matchingIds.get(0);
        } else {
            id = matchingIds.get(size - 1);
            LOGGER.warning("Ambiguity: " + size + " " + pluralOfNameType + " match name '" + name
                    + "'. Using the most recent one: " + id);
        }
        return id;
    }

    public abstract static class BootSourceDescriptor extends OsAuthDescriptor<BootSource> {
        @Override
        public List<String> getAuthFieldsOffsets() {
            return Arrays.asList("../..", "../../..");
        }

        protected ListBoxModel makeListBoxModelOfAllNames(
                String existingValue,
                String endPointUrl,
                boolean ignoreSsl,
                String credentialsId,
                String zone,
                long cleanfreq) {
            ListBoxModel m = new ListBoxModel();
            final String valueOrEmpty = Util.fixNull(existingValue);
            OpenstackCredential openstackCredential = OpenstackCredentials.getCredential(credentialsId);
            m.add(new ListBoxModel.Option("None specified", "", valueOrEmpty.isEmpty()));
            try {
                if (haveAuthDetails(endPointUrl, openstackCredential, zone)) {
                    final Openstack openstack =
                            Openstack.Factory.get(endPointUrl, ignoreSsl, openstackCredential, zone, cleanfreq);
                    final List<String> values = listAllNames(openstack);
                    for (String value : values) {
                        final String displayText = value;
                        m.add(displayText, value);
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

        protected FormValidation checkNameMatchesOnlyOnce(
                String value,
                String endPointUrl1,
                String endPointUrl2,
                boolean ignoreSsl1,
                boolean ignoreSsl2,
                String credentialsId1,
                String credentialsId2,
                String zone1,
                String zone2,
                long cleanfreq1,
                long cleanfreq2) {
            if (Util.fixEmpty(value) == null) return REQUIRED;
            final String endPointUrl = getDefault(endPointUrl1, endPointUrl2);
            final String credentialsId = getDefault(credentialsId1, credentialsId2);
            final boolean ignoreSsl = ignoreSsl1 || ignoreSsl2;
            OpenstackCredential openstackCredential = OpenstackCredentials.getCredential(credentialsId);
            final String zone = getDefault(zone1, zone2);
            final long cleanfreq = cleanfreq1 + cleanfreq2;
            if (!haveAuthDetails(endPointUrl, openstackCredential, zone)) return FormValidation.ok();

            final List<String> matches;
            try {
                final Openstack openstack =
                        Openstack.Factory.get(endPointUrl, ignoreSsl, openstackCredential, zone, cleanfreq);
                matches = findMatchingIds(openstack, value);
            } catch (AuthenticationException | FormValidation | ConnectionException ex) {
                LOGGER.log(Level.FINEST, "Openstack call failed", ex);
                return FormValidation.warning(ex, "Unable to validate");
            } catch (Exception ex) {
                LOGGER.log(Level.SEVERE, ex.getMessage(), ex);
                return FormValidation.warning(ex, "Unable to validate");
            }

            final int numberOfMatches = matches.size();
            if (numberOfMatches < 1) return FormValidation.error("Not found");
            if (numberOfMatches > 1) return FormValidation.warning("Multiple matching results");
            return FormValidation.ok();
        }

        /**
         * Lists all the IDs (of this kind of {@link BootSource}) matching the
         * given nameOrId.
         *
         * @param openstack
         *            Means of communicating with the OpenStack service.
         * @param nameOrId
         *            The user's selected name (or ID).
         * @return A list of all the IDs matching the specified name.
         */
        public abstract @Nonnull List<String> findMatchingIds(Openstack openstack, String nameOrId);

        /**
         * Lists all the names (of this kind of {@link BootSource}) that the
         * user could choose between.
         *
         * @param openstack
         *            Means of communicating with the OpenStack service.
         * @return A list of all the names the user could choose from.
         */
        public abstract List<String> listAllNames(Openstack openstack);
    }

    public static class Image extends BootSource {
        private static final long serialVersionUID = -8309975034351235331L;
        private static final String OPENSTACK_BOOTSOURCE_IMAGE_ID_KEY = "jenkins-boot-image-id";

        protected final @Nonnull String name;

        @DataBoundConstructor
        public Image(@Nonnull String name) {
            Objects.requireNonNull(name, "Image name missing");
            this.name = name;
        }

        @Nonnull
        public String getName() {
            return name;
        }

        @Override
        public void setServerBootSource(@Nonnull ServerCreateBuilder builder, @Nonnull Openstack os)
                throws JCloudsCloud.ProvisioningFailedException {
            super.setServerBootSource(builder, os);
            final List<String> matchingIds = getDescriptor().findMatchingIds(os, name);
            final String id = selectIdFromListAndLogProblems(matchingIds, name, "Images");

            builder.image(id);
            builder.addMetadataItem(OPENSTACK_BOOTSOURCE_IMAGE_ID_KEY, id);
        }

        @Override
        public String toString() {
            return "Image " + name;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            final Image image = (Image) o;
            return this.name.equals(image.name);
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }

        @Extension
        @Symbol("image")
        public static class Desc extends BootSourceDescriptor {
            @Override
            public @Nonnull String getDisplayName() {
                return "Image";
            }

            @Nonnull
            @Override
            public List<String> findMatchingIds(Openstack openstack, String nameOrId) {
                return openstack.getImageIdsFor(nameOrId);
            }

            @Nonnull
            @Override
            public List<String> listAllNames(Openstack openstack) {
                return new ArrayList<>(openstack.getImages().keySet());
            }

            @Restricted(DoNotUse.class)
            @InjectOsAuth
            @RequirePOST
            public ListBoxModel doFillNameItems(
                    @QueryParameter String name,
                    @QueryParameter String endPointUrl,
                    @QueryParameter boolean ignoreSsl,
                    @QueryParameter String credentialsId,
                    @QueryParameter String zone,
                    @QueryParameter long cleanfreq) {
                return makeListBoxModelOfAllNames(name, endPointUrl, ignoreSsl, credentialsId, zone, cleanfreq);
            }

            @Restricted(DoNotUse.class)
            @RequirePOST
            public FormValidation doCheckName(
                    @QueryParameter String value,
                    // authentication fields can be in two places relative to us.
                    @RelativePath("../..") @QueryParameter("endPointUrl") String endPointUrlCloud,
                    @RelativePath("../../..") @QueryParameter("endPointUrl") String endPointUrlTemplate,
                    @RelativePath("../..") @QueryParameter("ignoreSsl") boolean ignoreSslCloud,
                    @RelativePath("../../..") @QueryParameter("ignoreSsl") boolean ignoreSslTemplate,
                    @RelativePath("../..") @QueryParameter("credentialsId") String credentialsIdCloud,
                    @RelativePath("../../..") @QueryParameter("credentialsId") String credentialsIdTemplate,
                    @RelativePath("../..") @QueryParameter("zone") String zoneCloud,
                    @RelativePath("../../..") @QueryParameter("zone") String zoneTemplate,
                    @RelativePath("../..") @QueryParameter("cleanfreq") long cleanFreqCloud,
                    @RelativePath("../../..") @QueryParameter("cleanfreq") long cleanFreqTemplate) {
                return checkNameMatchesOnlyOnce(
                        value,
                        endPointUrlCloud,
                        endPointUrlTemplate,
                        ignoreSslCloud,
                        ignoreSslTemplate,
                        credentialsIdCloud,
                        credentialsIdTemplate,
                        zoneCloud,
                        zoneTemplate,
                        cleanFreqCloud,
                        cleanFreqTemplate);
            }
        }
    }

    public static final class VolumeFromImage extends Image {
        private static final long serialVersionUID = 3932407339481241514L;
        private static final String OPENSTACK_BOOTSOURCE_VOLUME_FROM_IMAGE_ID_KEY = "jenkins-boot-volumefromimage-id";

        private final int volumeSize;

        public int getVolumeSize() {
            return volumeSize;
        }

        @DataBoundConstructor
        public VolumeFromImage(@Nonnull String name, int volumeSize) {
            super(name);
            if (volumeSize <= 0) throw new IllegalArgumentException("Volume size must be positive, got " + volumeSize);
            this.volumeSize = volumeSize;
        }

        @Override
        public void setServerBootSource(@Nonnull ServerCreateBuilder builder, @Nonnull Openstack os)
                throws JCloudsCloud.ProvisioningFailedException {
            super.setServerBootSource(builder, os);
            final List<String> matchingIds = getDescriptor().findMatchingIds(os, name);
            final String id = selectIdFromListAndLogProblems(matchingIds, name, "Images");

            final BlockDeviceMappingBuilder volumeBuilder = Builders.blockDeviceMapping()
                    .sourceType(BDMSourceType.IMAGE)
                    .destinationType(BDMDestType.VOLUME)
                    .uuid(id)
                    .volumeSize(volumeSize)
                    .deleteOnTermination(true)
                    .bootIndex(0);
            builder.blockDevice(volumeBuilder.build());
            builder.addMetadataItem(OPENSTACK_BOOTSOURCE_VOLUME_FROM_IMAGE_ID_KEY, id);
        }

        @Override
        public String toString() {
            return "Volume from Image " + name + " (" + volumeSize + "GB)";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!super.equals(o)) return false;
            final VolumeFromImage that = (VolumeFromImage) o;
            return this.volumeSize == that.volumeSize;
        }

        @Override
        public int hashCode() {
            return Objects.hash(volumeSize, super.hashCode());
        }

        @Extension
        @Symbol("volumeFromImage")
        public static final class VFIDesc extends Desc {

            @Override
            public @Nonnull String getDisplayName() {
                return "Volume from Image";
            }
        }
    }

    public static final class VolumeSnapshot extends BootSource {
        private static final long serialVersionUID = 1629434277902240395L;
        private static final String OPENSTACK_BOOTSOURCE_VOLUMESNAPSHOT_ID_KEY = "jenkins-boot-volumesnapshot-id";
        private static final String OPENSTACK_BOOTSOURCE_VOLUMESNAPSHOT_DESC_KEY =
                "jenkins-boot-volumesnapshot-description";

        private final @Nonnull String name;

        @DataBoundConstructor
        public VolumeSnapshot(@Nonnull String name) {
            Objects.requireNonNull(name, "Volume snapshot name missing");
            this.name = name;
        }

        @Nonnull
        public String getName() {
            return name;
        }

        @Override
        public void setServerBootSource(@Nonnull ServerCreateBuilder builder, @Nonnull Openstack os) {
            super.setServerBootSource(builder, os);
            final List<String> matchingIds = getDescriptor().findMatchingIds(os, name);
            final String id = selectIdFromListAndLogProblems(matchingIds, name, "VolumeSnapshots");
            String volumeSnapshotDescriptionOrNull = null;
            try {
                volumeSnapshotDescriptionOrNull = os.getVolumeSnapshotDescription(id);
            } catch (RuntimeException ex) {
                /*
                 * This can throw, e.g. a NPE there is no VolumeSnapshot with that id. However,
                 * a failure to get the description is purely cosmetic and a failure to find the
                 * VolumeSnapshot would be better logged later on with a
                 * "there's no volume snapshot of that name" error, so we log any exception we
                 * get here and carry on, letting actual problems throw later.
                 */
                LOGGER.warning("Unable to get volume " + id + " description: " + ex.getMessage());
            }
            final BlockDeviceMappingBuilder volumeBuilder = Builders.blockDeviceMapping()
                    .sourceType(BDMSourceType.SNAPSHOT)
                    .destinationType(BDMDestType.VOLUME)
                    .uuid(id)
                    .deleteOnTermination(true)
                    .bootIndex(0);
            builder.blockDevice(volumeBuilder.build());
            builder.addMetadataItem(OPENSTACK_BOOTSOURCE_VOLUMESNAPSHOT_ID_KEY, id);
            if (volumeSnapshotDescriptionOrNull != null && !volumeSnapshotDescriptionOrNull.isEmpty()) {
                builder.addMetadataItem(OPENSTACK_BOOTSOURCE_VOLUMESNAPSHOT_DESC_KEY, volumeSnapshotDescriptionOrNull);
            }
        }

        @Override
        public void afterProvisioning(@Nonnull Server server, @Nonnull Openstack openstack) {
            /*
             * OpenStack creates a Volume for the Instance to boot from but it
             * does not give that Volume a name or description. We do this so
             * that humans can recognize those Volumes.
             */
            final List<String> volumeIds = server.getOsExtendedVolumesAttached();
            final String instanceId = server.getId();
            final String instanceName = server.getName();
            final Map<String, String> instanceMetaData = server.getMetadata();
            final String instanceVolumeSnapshotId =
                    instanceMetaData == null ? null : instanceMetaData.get(OPENSTACK_BOOTSOURCE_VOLUMESNAPSHOT_ID_KEY);
            int i = 0;
            final String newVolumeDescription = "For " + instanceName + " (" + instanceId + "), from VolumeSnapshot "
                    + name + (instanceVolumeSnapshotId == null ? "" : " (" + instanceVolumeSnapshotId + ")") + ".";
            for (final String volumeId : volumeIds) {
                final String newVolumeName = instanceName + '[' + (i++) + ']';
                try {
                    openstack.setVolumeNameAndDescription(volumeId, newVolumeName, newVolumeDescription);
                } catch (Openstack.ActionFailed ex) {
                    /*
                     * Some versions of OpenStack work better than others. Not all will accept this
                     * operation. However, a failure to set the name and description is purely
                     * cosmetic and does not affect our ability to use the instance, so we log the
                     * problem and carry on.
                     */
                    LOGGER.warning("Unable to set volume " + volumeId + " name and description: " + ex.getMessage());
                }
            }
        }

        @Override
        public String toString() {
            return "VolumeSnapshot " + name;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            final VolumeSnapshot that = (VolumeSnapshot) o;
            return name.equals(that.name);
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }

        @Extension
        @Symbol("volumeSnapshot")
        public static final class Desc extends BootSourceDescriptor {
            @Override
            public @Nonnull String getDisplayName() {
                return "Volume Snapshot";
            }

            @Nonnull
            @Override
            public List<String> findMatchingIds(Openstack openstack, String nameOrId) {
                return openstack.getVolumeSnapshotIdsFor(nameOrId);
            }

            @Nonnull
            @Override
            public List<String> listAllNames(Openstack openstack) {
                return new ArrayList<>(openstack.getVolumeSnapshots().keySet());
            }

            @Restricted(DoNotUse.class)
            @OsAuthDescriptor.InjectOsAuth
            @RequirePOST
            public ListBoxModel doFillNameItems(
                    @QueryParameter String name,
                    @QueryParameter String endPointUrl,
                    @QueryParameter boolean ignoreSsl,
                    @QueryParameter String credentialsId,
                    @QueryParameter String zone,
                    @QueryParameter long cleanfreq) {
                return makeListBoxModelOfAllNames(name, endPointUrl, ignoreSsl, credentialsId, zone, cleanfreq);
            }

            @Restricted(DoNotUse.class)
            @RequirePOST
            public FormValidation doCheckName(
                    @QueryParameter String value,
                    // authentication fields can be in two places relative to
                    // us.
                    @RelativePath("../..") @QueryParameter("endPointUrl") String endPointUrlCloud,
                    @RelativePath("../../..") @QueryParameter("endPointUrl") String endPointUrlTemplate,
                    @RelativePath("../..") @QueryParameter("ignoreSsl") boolean ignoreSslCloud,
                    @RelativePath("../../..") @QueryParameter("ignoreSsl") boolean ignoreSslTemplate,
                    @RelativePath("../..") @QueryParameter("credentialsId") String credentialsIdCloud,
                    @RelativePath("../../..") @QueryParameter("credentialsId") String credentialsIdTemplate,
                    @RelativePath("../..") @QueryParameter("zone") String zoneCloud,
                    @RelativePath("../../..") @QueryParameter("zone") String zoneTemplate,
                    @RelativePath("../..") @QueryParameter("cleanfreq") long cleanFreqCloud,
                    @RelativePath("../../..") @QueryParameter("cleanfreq") long cleanFreqTemplate) {
                return checkNameMatchesOnlyOnce(
                        value,
                        endPointUrlCloud,
                        endPointUrlTemplate,
                        ignoreSslCloud,
                        ignoreSslTemplate,
                        credentialsIdCloud,
                        credentialsIdTemplate,
                        zoneCloud,
                        zoneTemplate,
                        cleanFreqCloud,
                        cleanFreqTemplate);
            }
        }
    }

    /**
     * No boot source specified. This exists only as a field in UI dropdown to
     * be read by stapler and converted to plain old null.
     */
    // Therefore, no one refers to this as a symbol or tries to serialize it,
    // ever.
    @SuppressWarnings({"unused", "serial"})
    public static final class Unspecified extends BootSource {
        private static final long serialVersionUID = -2723193057734405815L;

        private Unspecified() {} // Never instantiate

        @Extension(ordinal = Double.MAX_VALUE) // Make it first and therefore default
        public static final class Desc extends Descriptor<BootSource> {
            @Override
            public @Nonnull String getDisplayName() {
                return "Inherit / Override later";
            }

            @Override
            public BootSource newInstance(StaplerRequest req, @Nonnull JSONObject formData) throws FormException {
                return null; // Make sure this is never instantiated and hence
                // will be treated as absent
            }
        }
    }
}
