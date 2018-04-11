package jenkins.plugins.openstack.compute;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import com.gargoylesoftware.htmlunit.html.HtmlForm;
import hudson.remoting.Base64;
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

import static jenkins.plugins.openstack.PluginTestRule.dummySlaveOptions;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.*;

public class JCloudsSlaveTemplateTest {

    @Rule
    public PluginTestRule j = new PluginTestRule();

    private final String TEMPLATE_PROPERTIES = "name,labelString";
    private final String CLOUD_PROPERTIES = "name,credentialId,zone";

    @Test
    public void configRoundtrip() throws Exception {

        JCloudsSlaveTemplate jnlpTemplate = new JCloudsSlaveTemplate(
                "jnlp-template", "openstack-slave-type1 openstack-type2", dummySlaveOptions().getBuilder().launcherFactory(LauncherFactory.JNLP.JNLP).build()
        );

        LauncherFactory.SSH slaveType = new LauncherFactory.SSH(j.dummySshCredential("sshid"), "mypath");
        JCloudsSlaveTemplate sshTemplate = new JCloudsSlaveTemplate(
                "ssh-template", "openstack-slave-type1 openstack-type2", dummySlaveOptions().getBuilder().launcherFactory(slaveType).build()
        );

        JCloudsCloud originalCloud = new JCloudsCloud(
                "my-openstack", "endPointUrl", false,"zone",
                SlaveOptions.empty(),
                Arrays.asList(jnlpTemplate, sshTemplate),
                j.dummyCredential()
        );

        j.jenkins.clouds.add(originalCloud);

        HtmlForm form = j.createWebClient().goTo("configure").getFormByName("config");

        j.submit(form);

        final JCloudsCloud actualCloud = JCloudsCloud.getByName("my-openstack");
        j.assertEqualBeans(originalCloud, actualCloud, CLOUD_PROPERTIES);
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
                "my-openstack", "credential", false,"zone",
                cloudOpts,
                Collections.singletonList(template),
                j.dummyCredential()
        );

        assertEquals(cloudOpts, cloud.getRawSlaveOptions());
        assertEquals(SlaveOptions.builder().bootSource(new BootSource.Image("id")).availabilityZone("other").build(), template.getRawSlaveOptions());
    }

    @Test
    public void replaceUserData() throws Exception {
        SlaveOptions opts = j.defaultSlaveOptions();
        JCloudsSlaveTemplate template = j.dummySlaveTemplate(opts,"a");
        JCloudsCloud cloud = j.configureSlaveProvisioning(j.dummyCloud(template));
        Openstack os = cloud.getOpenstack();

        template.provision(cloud);

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
        JCloudsCloud cloud = j.configureSlaveProvisioning(j.dummyCloud(template));
        Openstack os = cloud.getOpenstack();

        template.provision(cloud);

        verify(os).bootAndWaitActive(any(ServerCreateBuilder.class), anyInt());
        verify(os, never()).assignFloatingIp(any(Server.class), any(String.class));
    }

    @Test
    public void bootWithMultipleNetworks() {
        final SlaveOptions opts = dummySlaveOptions().getBuilder().networkId("foo,BAR").build();
        final JCloudsSlaveTemplate instance = j.dummySlaveTemplate(opts, "a");
        final JCloudsCloud cloud = j.configureSlaveProvisioning(j.dummyCloud(instance));
        final Openstack mockOs = cloud.getOpenstack();
        when(mockOs.getNetworkIds(any())).thenCallRealMethod();

        Network n1 = mock(Network.class); when(n1.getName()).thenReturn("FOO"); when(n1.getId()).thenReturn("foo");
        Network n2 = mock(Network.class); when(n2.getName()).thenReturn("BAR"); when(n2.getId()).thenReturn("bar");
        doReturn(Arrays.asList(n1, n2)).when(mockOs)._listNetworks();

        instance.provision(cloud);

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
        final JCloudsCloud cloud = j.configureSlaveProvisioning(j.dummyCloud(instance));
        final Openstack mockOs = cloud.getOpenstack();
        when(mockOs.getVolumeSnapshotIdsFor(volumeSnapshotName)).thenReturn(Collections.singletonList(volumeSnapshotId));

        final ArgumentCaptor<ServerCreateBuilder> scbCaptor = ArgumentCaptor.forClass(ServerCreateBuilder.class);
        final ArgumentCaptor<String> vnCaptor = ArgumentCaptor.forClass(String.class);
        final ArgumentCaptor<String> vdCaptor = ArgumentCaptor.forClass(String.class);

        final Server actual = instance.provision(cloud);

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
        final JCloudsCloud cloud = j.configureSlaveProvisioning(j.dummyCloud(template));
        final Openstack os = cloud.getOpenstack();

        template.provision(cloud);

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
        JCloudsCloud cloud = j.configureSlaveLaunching(j.dummyCloud(j.dummySlaveTemplate(opts, "label")));

        Openstack os = cloud.getOpenstack();
        // simulate same image resolved to different ids
        when(os.getImageIdsFor(eq("image-id"))).thenReturn(Collections.singletonList("image-id")).thenReturn(Collections.singletonList("something-else"));

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
        JCloudsCloud cloud = j.configureSlaveLaunching(j.dummyCloud(j.dummySlaveTemplate(opts, "label")));

        Openstack os = cloud.getOpenstack();
        // simulate same snapshot resolved to different ids
        when(os.getVolumeSnapshotIdsFor(eq("vs-id"))).thenReturn(Collections.singletonList("vs-id")).thenReturn(Collections.singletonList("something-else"));

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
}
