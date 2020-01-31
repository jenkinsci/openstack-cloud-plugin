package jenkins.plugins.openstack.compute;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

import com.gargoylesoftware.htmlunit.html.HtmlForm;
import hudson.remoting.Base64;
import hudson.util.FormValidation;
import jenkins.plugins.openstack.PluginTestRule;

import jenkins.plugins.openstack.compute.internal.Openstack;
import jenkins.plugins.openstack.compute.slaveopts.BootSource;
import jenkins.plugins.openstack.compute.slaveopts.BootSource.VolumeSnapshot;
import jenkins.plugins.openstack.compute.slaveopts.LauncherFactory;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.internal.util.reflection.Whitebox;
import org.openstack4j.model.compute.BDMDestType;
import org.openstack4j.model.compute.BDMSourceType;
import org.openstack4j.model.compute.BlockDeviceMappingCreate;
import org.openstack4j.model.compute.NetworkCreate;
import org.openstack4j.model.compute.Server;
import org.openstack4j.model.compute.builder.ServerCreateBuilder;
import org.openstack4j.model.network.Network;
import org.openstack4j.openstack.compute.domain.NovaBlockDeviceMappingCreate;

import static java.util.Collections.singletonList;
import static jenkins.model.Jenkins.get;
import static jenkins.plugins.openstack.PluginTestRule.dummySlaveOptions;

import static jenkins.plugins.openstack.compute.JCloudsSlaveTemplate.parseSecurityGroups;
import static jenkins.plugins.openstack.compute.JCloudsSlaveTemplate.selectNetworkIds;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.*;

public class JCloudsSlaveTemplateTest {

    @Rule
    public PluginTestRule j = new PluginTestRule();

    private static final String TEMPLATE_PROPERTIES = "name,labels";

    @Test
    public void doCheckTemplateName() {
        JCloudsSlaveTemplate.DescriptorImpl d = get().getDescriptorByType(JCloudsSlaveTemplate.DescriptorImpl.class);
        assertEquals(FormValidation.Kind.OK, d.doCheckName("foo").kind);
        assertEquals(FormValidation.Kind.ERROR, d.doCheckName("#1").kind);
    }

    @Test
    public void configRoundtrip() throws Exception {

        JCloudsSlaveTemplate jnlpTemplate = new JCloudsSlaveTemplate(
                "jnlp-template", "openstack-slave-type1 openstack-type2", dummySlaveOptions().getBuilder().launcherFactory(LauncherFactory.JNLP.JNLP).build()
        );

        LauncherFactory.SSH slaveType = new LauncherFactory.SSH(j.dummySshCredentials("sshid"), "mypath");
        JCloudsSlaveTemplate sshTemplate = new JCloudsSlaveTemplate(
                "ssh-template", "openstack-slave-type1 openstack-type2", dummySlaveOptions().getBuilder().launcherFactory(slaveType).build()
        );

        JCloudsCloud originalCloud = new JCloudsCloud(
                "my-openstack", "endPointUrl", false, "zone",
                SlaveOptions.empty(),
                Arrays.asList(jnlpTemplate, sshTemplate),
                j.dummyCredentials()
        );

        j.jenkins.clouds.add(originalCloud);

        HtmlForm form = j.createWebClient().goTo("configure").getFormByName("config");

        j.submit(form);

        final JCloudsCloud actualCloud = JCloudsCloud.getByName("my-openstack");
        j.assertEqualBeans(originalCloud, actualCloud, "name,credentialsId,zone");
        assertThat(actualCloud.getEffectiveSlaveOptions(), equalTo(originalCloud.getEffectiveSlaveOptions()));
        assertThat(actualCloud.getRawSlaveOptions(), equalTo(originalCloud.getRawSlaveOptions()));

        JCloudsSlaveTemplate actualJnlp = actualCloud.getTemplate("jnlp-template");
        j.assertEqualBeans(jnlpTemplate, actualJnlp, TEMPLATE_PROPERTIES);
        assertThat(actualJnlp.getEffectiveSlaveOptions(), equalTo(jnlpTemplate.getEffectiveSlaveOptions()));
        assertThat(actualJnlp.getRawSlaveOptions(), equalTo(jnlpTemplate.getRawSlaveOptions()));

        JCloudsSlaveTemplate actualSsh = actualCloud.getTemplate("ssh-template");
        j.assertEqualBeans(sshTemplate, actualSsh, TEMPLATE_PROPERTIES);
        assertThat(actualSsh.getEffectiveSlaveOptions(), equalTo(sshTemplate.getEffectiveSlaveOptions()));
        assertThat(actualSsh.getRawSlaveOptions(), equalTo(sshTemplate.getRawSlaveOptions()));
    }

    @Test
    public void eraseDefaults() {
        SlaveOptions cloudOpts = dummySlaveOptions(); // Make sure nothing collides with defaults
        SlaveOptions templateOpts = cloudOpts.getBuilder().bootSource(new BootSource.Image("id")).availabilityZone("other").build();
        assertEquals(cloudOpts.getHardwareId(), templateOpts.getHardwareId());

        JCloudsSlaveTemplate template = new JCloudsSlaveTemplate(
                "test-templateOpts", "openstack-slave-type1 openstack-type2", templateOpts
        );

        JCloudsCloud cloud = new JCloudsCloud(
                "my-openstack", "credential", false, "zone",
                cloudOpts,
                singletonList(template),
                j.dummyCredentials()
        );

        assertEquals(cloudOpts, cloud.getRawSlaveOptions());
        assertEquals(SlaveOptions.builder().bootSource(new BootSource.Image("id")).availabilityZone("other").build(), template.getRawSlaveOptions());
    }

    @Test
    public void replaceUserData() throws Exception {
        SlaveOptions opts = j.defaultSlaveOptions();
        JCloudsSlaveTemplate template = j.dummySlaveTemplate(opts,"a");
        JCloudsCloud cloud = j.configureSlaveProvisioningWithFloatingIP(j.dummyCloud(template));
        Openstack os = cloud.getOpenstack();

        template.provisionServer(null, null);

        ArgumentCaptor<ServerCreateBuilder> captor = ArgumentCaptor.forClass(ServerCreateBuilder.class);
        verify(os).bootAndWaitActive(captor.capture(), anyInt());

        Properties actual = new Properties();
        actual.load(new ByteArrayInputStream(Base64.decode(captor.getValue().build().getUserData())));
        assertEquals(opts.getFsRoot(), actual.getProperty("SLAVE_JENKINS_HOME"));
        assertEquals(opts.getJvmOptions(), actual.getProperty("SLAVE_JVM_OPTIONS"));
        assertEquals(j.getURL().toExternalForm(), actual.getProperty("JENKINS_URL"));
        assertEquals("a", actual.getProperty("SLAVE_LABELS"));
        assertEquals("${unknown} ${VARIABLE}", actual.getProperty("DO_NOT_REPLACE_THIS"));
    }

    @Test
    public void noFloatingPoolId() {
        SlaveOptions opts = j.defaultSlaveOptions().getBuilder().floatingIpPool(null).build();
        JCloudsSlaveTemplate template = j.dummySlaveTemplate(opts,"a");
        JCloudsCloud cloud = j.configureSlaveProvisioningWithFloatingIP(j.dummyCloud(template));
        Openstack os = cloud.getOpenstack();

        template.provisionServer(null, null);

        verify(os).bootAndWaitActive(any(ServerCreateBuilder.class), anyInt());
        verify(os, never()).assignFloatingIp(any(Server.class), any(String.class));
    }

    @Test
    public void bootWithMultipleNetworks() {
        final SlaveOptions opts = dummySlaveOptions().getBuilder().networkId("foo,BAR").build();
        final JCloudsSlaveTemplate instance = j.dummySlaveTemplate(opts, "a");
        final JCloudsCloud cloud = j.configureSlaveProvisioningWithFloatingIP(j.dummyCloud(instance));
        final Openstack mockOs = cloud.getOpenstack();

        Network n1 = mock(Network.class); when(n1.getName()).thenReturn("FOO"); when(n1.getId()).thenReturn("foo");
        Network n2 = mock(Network.class); when(n2.getName()).thenReturn("BAR"); when(n2.getId()).thenReturn("bar");
        Map<String, Network> nets = new HashMap<>();
        nets.put(n1.getId(), n1);
        nets.put(n2.getId(), n2);
        doReturn(nets).when(mockOs).getNetworks(any());

        instance.provisionServer(null, null);

        ArgumentCaptor<ServerCreateBuilder> captor = ArgumentCaptor.forClass(ServerCreateBuilder.class);
        verify(mockOs, times(1)).bootAndWaitActive(captor.capture(), any(Integer.class));
        List<? extends NetworkCreate> networks = captor.getValue().build().getNetworks();
        assertEquals("foo", networks.get(0).getId());
        assertEquals("bar", networks.get(1).getId());
        assertThat(networks.size(), equalTo(2));
    }

    @Test
    public void bootFromVolumeSnapshot() {
        final String volumeSnapshotName = "MyVolumeSnapshot";
        final String volumeSnapshotId = "vs-123-id";
        final SlaveOptions opts = dummySlaveOptions().getBuilder().bootSource(new VolumeSnapshot(volumeSnapshotName)).build();
        final JCloudsSlaveTemplate instance = j.dummySlaveTemplate(opts, "a");
        final JCloudsCloud cloud = j.configureSlaveProvisioningWithFloatingIP(j.dummyCloud(instance));
        final Openstack mockOs = cloud.getOpenstack();
        testBootFromVolumeSnapshot(volumeSnapshotName, volumeSnapshotId, instance, mockOs);
    }

    @Test
    public void bootFromVolumeSnapshotDoesNotNPEIfCantFindVolumeSnapshot() {
        /*
         * Openstack.getVolumeSnapshotDescription will throw NullPointerException if
         * there is no volume snapshot matching the given id. We need our code to
         * survive the NPE so that the user (later) gets a much better
         * "there's no volume snapshot with that id" error rather than a NPE.
         */
        final String volumeSnapshotName = "MyNonexistentVolumeSnapshot";
        final String volumeSnapshotId = volumeSnapshotName;
        final SlaveOptions opts = dummySlaveOptions().getBuilder().bootSource(new VolumeSnapshot(volumeSnapshotName)).build();
        final JCloudsSlaveTemplate instance = j.dummySlaveTemplate(opts, "b");
        final JCloudsCloud cloud = j.configureSlaveProvisioningWithFloatingIP(j.dummyCloud(instance));
        final Openstack mockOs = cloud.getOpenstack();
        doThrow(new NullPointerException("Mock NPE getting description of volumesnapshot")).when(mockOs).getVolumeSnapshotDescription(volumeSnapshotId);
        testBootFromVolumeSnapshot(volumeSnapshotName, volumeSnapshotId, instance, mockOs);
        // Note: In reality, OpenStack should then reject the request for the new
        // instance with a nice informative "there's no volume snapshot of that id"
        // error, which makes a lot more sense than a NPE.
    }

    @Test
    public void bootFromVolumeSnapshotStillPossibleEvenIfCantSetVolumeNameAndDescription() {
        /*
         * openstack4j can throw fail with the error:
         * "ActionResponse{success=false, fault=Invalid input for field/attribute volume.
         * Value: {u'description': u'...', u'display_name': u'...', u'name': u'...',
         * u'os-vol-mig-status-attr:migstat': u'none', u'display_description': u'...'}.
         * Additional properties are not allowed (u'os-vol-mig-status-attr:migstat' was
         * unexpected), code=400}".
         * We need our code to survive this and not treat it as a fatal error.
         */
        final String volumeSnapshotName = "MyOtherVolumeSnapshot";
        final String volumeSnapshotId = "vs-345-id";
        final SlaveOptions opts = dummySlaveOptions().getBuilder().bootSource(new VolumeSnapshot(volumeSnapshotName)).build();
        final JCloudsSlaveTemplate instance = j.dummySlaveTemplate(opts, "b");
        final JCloudsCloud cloud = j.configureSlaveProvisioningWithFloatingIP(j.dummyCloud(instance));
        final Openstack mockOs = cloud.getOpenstack();
        doThrow(new Openstack.ActionFailed("Mock OpenStack error setting volume name and description")).when(mockOs).setVolumeNameAndDescription(anyString(), anyString(), anyString());
        testBootFromVolumeSnapshot(volumeSnapshotName, volumeSnapshotId, instance, mockOs);
    }

    private void testBootFromVolumeSnapshot(final String volumeSnapshotName, final String volumeSnapshotId,
            final JCloudsSlaveTemplate instance, final Openstack mockOs) {
        when(mockOs.getVolumeSnapshotIdsFor(volumeSnapshotName)).thenReturn(singletonList(volumeSnapshotId));

        final ArgumentCaptor<ServerCreateBuilder> scbCaptor = ArgumentCaptor.forClass(ServerCreateBuilder.class);
        final ArgumentCaptor<String> vnCaptor = ArgumentCaptor.forClass(String.class);
        final ArgumentCaptor<String> vdCaptor = ArgumentCaptor.forClass(String.class);

        final Server actual = instance.provisionServer(null, null);

        final String actualServerName = actual.getName();
        verify(mockOs, times(1)).bootAndWaitActive(scbCaptor.capture(), anyInt());
        verify(mockOs, times(1)).setVolumeNameAndDescription(anyString(), vnCaptor.capture(), vdCaptor.capture());

        final NovaBlockDeviceMappingCreate bdmcActual = getBlockDeviceMapping(scbCaptor.getValue());
        assertThat(bdmcActual.boot_index, equalTo(0));
        assertThat(bdmcActual.delete_on_termination, equalTo(true));
        assertThat(bdmcActual.uuid, equalTo(volumeSnapshotId));
        assertThat(bdmcActual.source_type, equalTo(BDMSourceType.SNAPSHOT));
        assertThat(bdmcActual.destination_type, equalTo(BDMDestType.VOLUME));
        assertThat(vnCaptor.getValue(), equalTo(actualServerName+"[0]"));

        final String actualVolumeDescription = vdCaptor.getValue();
        assertThat(actualVolumeDescription, containsString(actualServerName));
        assertThat(actualVolumeDescription, containsString(actual.getId()));
        assertThat(actualVolumeDescription, containsString(volumeSnapshotName));
    }

    @SuppressWarnings("unchecked")
    private NovaBlockDeviceMappingCreate getBlockDeviceMapping(ServerCreateBuilder scbActual) {
        assertNotNull(scbActual);
        List<BlockDeviceMappingCreate> blockDeviceMapping = (List<BlockDeviceMappingCreate>) Whitebox.getInternalState(
                scbActual.build(), "blockDeviceMapping"
        );
        assertThat(blockDeviceMapping, hasSize(1));
        return (NovaBlockDeviceMappingCreate) blockDeviceMapping.get(0);
    }

    @Test
    public void bootFromImageVolume() {
        final SlaveOptions opts = dummySlaveOptions().getBuilder().bootSource(new BootSource.VolumeFromImage("src_img_id", 42)).build();
        final JCloudsSlaveTemplate template = j.dummySlaveTemplate(opts, "label");
        final JCloudsCloud cloud = j.configureSlaveProvisioningWithFloatingIP(j.dummyCloud(template));
        final Openstack os = cloud.getOpenstack();

        template.provisionServer(null, null);

        ArgumentCaptor<ServerCreateBuilder> captor = ArgumentCaptor.forClass(ServerCreateBuilder.class);
        verify(os, times(1)).bootAndWaitActive(captor.capture(), any(Integer.class));
        NovaBlockDeviceMappingCreate blockDeviceMapping = getBlockDeviceMapping(captor.getValue());

        assertThat(blockDeviceMapping.boot_index, equalTo(0));
        assertThat(blockDeviceMapping.delete_on_termination, equalTo(true));
        assertThat(blockDeviceMapping.uuid, equalTo("src_img_id"));
        assertThat(blockDeviceMapping.source_type, equalTo(BDMSourceType.IMAGE));
        assertThat(blockDeviceMapping.destination_type, equalTo(BDMDestType.VOLUME));
        assertThat(blockDeviceMapping.volume_size, equalTo(42));
    }

    @Test
    public void allowToUseImageNameAsWellAsId() throws Exception {
        SlaveOptions opts = j.defaultSlaveOptions().getBuilder().bootSource(new BootSource.Image("image-id")).build();
        JCloudsCloud cloud = j.configureSlaveLaunchingWithFloatingIP(j.dummyCloud(j.dummySlaveTemplate(opts, "label")));

        Openstack os = cloud.getOpenstack();
        // simulate same image resolved to different ids
        when(os.getImageIdsFor(eq("image-id"))).thenReturn(singletonList("image-id")).thenReturn(singletonList("something-else"));

        j.provision(cloud, "label"); j.provision(cloud, "label");

        ArgumentCaptor<ServerCreateBuilder> captor = ArgumentCaptor.forClass(ServerCreateBuilder.class);
        verify(os, times(2)).bootAndWaitActive(captor.capture(), any(Integer.class));

        List<ServerCreateBuilder> builders = captor.getAllValues();
        assertEquals(2, builders.size());
        assertEquals("image-id", builders.get(0).build().getImageRef());
        assertEquals("something-else", builders.get(1).build().getImageRef());
    }

    @Test
    public void allowToUseVolumeSnapshotNameAsWellAsId() throws Exception {
        SlaveOptions opts = j.defaultSlaveOptions().getBuilder().bootSource(new VolumeSnapshot("vs-id")).build();
        JCloudsCloud cloud = j.configureSlaveLaunchingWithFloatingIP(j.dummyCloud(j.dummySlaveTemplate(opts, "label")));

        Openstack os = cloud.getOpenstack();
        // simulate same snapshot resolved to different ids
        when(os.getVolumeSnapshotIdsFor(eq("vs-id"))).thenReturn(singletonList("vs-id")).thenReturn(singletonList("something-else"));

        j.provision(cloud, "label"); j.provision(cloud, "label");

        ArgumentCaptor<ServerCreateBuilder> captor = ArgumentCaptor.forClass(ServerCreateBuilder.class);
        verify(os, times(2)).bootAndWaitActive(captor.capture(), any(Integer.class));

        List<ServerCreateBuilder> builders = captor.getAllValues();
        assertEquals(2, builders.size());
        assertEquals("vs-id", getVolumeSnapshotId(builders.get(0)));
        assertEquals("something-else", getVolumeSnapshotId(builders.get(1)));
    }

    @SuppressWarnings("unchecked")
    private String getVolumeSnapshotId(ServerCreateBuilder builder) {
        List<BlockDeviceMappingCreate> mapping = (List<BlockDeviceMappingCreate>) Whitebox.getInternalState(
                builder.build(),
                "blockDeviceMapping"
        );

        assertEquals(1, mapping.size());
        NovaBlockDeviceMappingCreate device = (NovaBlockDeviceMappingCreate) mapping.get(0);
        assertEquals(BDMSourceType.SNAPSHOT, device.source_type);
        assertEquals(BDMDestType.VOLUME, device.destination_type);
        return device.uuid;
    }

    @Test
    public void securityGroups() {
        assertEquals(singletonList("foo"), parseSecurityGroups("foo"));
        assertEquals(Arrays.asList("foo", "bar"), parseSecurityGroups("foo,bar"));
        try {
            parseSecurityGroups("foo,,bar");
            fail();
        } catch (IllegalArgumentException expected) {}

        try {
            parseSecurityGroups(",");
            fail();
        } catch (IllegalArgumentException expected) {}

        try {
            parseSecurityGroups("");
            fail();
        } catch (IllegalArgumentException expected) {}

        try {
            //noinspection ConstantConditions
            parseSecurityGroups(null);
            fail();
        } catch (IllegalArgumentException expected) {}
    }

    @Test
    public void selectNetworks() {
        JCloudsSlaveTemplate t = j.dummySlaveTemplate("foo");
        JCloudsCloud c = j.dummyCloud(t);
        j.configureSlaveProvisioning(c, Collections.emptyList());

        Network fullNet = mockNetwork("full");
        Network emptyNet = mockNetwork("empty");
        Network loadedNet = mockNetwork("loaded");
        Map<String, Network> networkMap = new HashMap<>();
        Stream.of(fullNet, emptyNet, loadedNet).forEach(n -> {
            networkMap.put(n.getId(), n );
            networkMap.put(n.getName(), n);
        });

        Openstack os = c.getOpenstack();
        doAnswer(i -> {
            Map<String, Network> ret = new HashMap<>();

            for (String netSpec : ((List<String>) i.getArguments()[0])) {
                Network n = networkMap.get(netSpec);
                ret.put(n.getId(), n);
            }
            return ret;
        }).when(os).getNetworks(any());
        doAnswer(i -> {
            Map<String, Network> requestedNets = (Map<String, Network>) i.getArguments()[0];

            Map<Network, Integer> ret = new HashMap<>();
            if (requestedNets.containsKey(fullNet.getId())) {
                ret.put(fullNet, 0);
            }
            if (requestedNets.containsKey(emptyNet.getId())) {
                ret.put(emptyNet, 10);
            }
            if (requestedNets.containsKey(loadedNet.getId())) {
                ret.put(loadedNet, 5);
            }
            return ret;
        }).when(os).getNetworksCapacity(any());

        try {
            selectNetworkIds(os, "foo,,bar");
            fail();
        } catch (IllegalArgumentException expected) {}

        try {
            selectNetworkIds(os, "foo||bar");
            fail();
        } catch (IllegalArgumentException expected) {}

        try {
            selectNetworkIds(os, ",");
            fail();
        } catch (IllegalArgumentException expected) {}

        try {
            selectNetworkIds(os, "|");
            fail();
        } catch (IllegalArgumentException expected) {}

        try {
            selectNetworkIds(os, ",|");
            fail();
        } catch (IllegalArgumentException expected) {}

        try {
            selectNetworkIds(os, "");
            fail();
        } catch (IllegalArgumentException expected) {}

        try {
            selectNetworkIds(os, null);
            fail();
        } catch (IllegalArgumentException expected) {}

        assertEquals(singletonList("uuid-empty"), selectNetworkIds(os, "empty"));
        assertEquals(singletonList("uuid-loaded"), selectNetworkIds(os, "loaded"));
        assertEquals(singletonList("uuid-full"), selectNetworkIds(os, "full"));
        assertEquals(singletonList("uuid-empty"), selectNetworkIds(os, "uuid-empty"));
        assertEquals(singletonList("uuid-loaded"), selectNetworkIds(os, "uuid-loaded"));
        assertEquals(singletonList("uuid-full"), selectNetworkIds(os, "uuid-full"));
        assertEquals(Arrays.asList("uuid-empty", "uuid-full", "uuid-loaded"), selectNetworkIds(os, "empty,uuid-full,loaded"));
        assertEquals(Arrays.asList("uuid-empty", "uuid-empty", "uuid-empty"), selectNetworkIds(os, "empty,uuid-empty,empty"));

        assertEquals(singletonList("uuid-empty"), selectNetworkIds(os, "empty|full"));
        assertEquals(singletonList("uuid-empty"), selectNetworkIds(os, "empty|loaded"));
        assertEquals(singletonList("uuid-loaded"), selectNetworkIds(os, "loaded|full"));
        assertEquals(singletonList("uuid-empty"), selectNetworkIds(os, "empty|loaded|full"));
        assertEquals(singletonList("uuid-empty"), selectNetworkIds(os, "full|loaded|empty"));

        assertEquals(Arrays.asList("uuid-empty", "uuid-full", "uuid-empty", "uuid-empty"), selectNetworkIds(os, "empty|full,full,loaded|empty,empty"));
        assertEquals(Arrays.asList("uuid-empty", "uuid-loaded", "uuid-empty"), selectNetworkIds(os, "empty|empty,loaded|loaded,empty|empty"));
    }

    private Network mockNetwork(String name) {
        Network n = mock(Network.class);
        when(n.getId()).thenReturn("uuid-" + name);
        when(n.getName()).thenReturn(name);
        return n;
    }
}
