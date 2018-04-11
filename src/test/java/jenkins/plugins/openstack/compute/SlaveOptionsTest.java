package jenkins.plugins.openstack.compute;

import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.*;

import jenkins.plugins.openstack.PluginTestRule;
import jenkins.plugins.openstack.compute.slaveopts.BootSource;
import jenkins.plugins.openstack.compute.slaveopts.LauncherFactory;
import org.junit.Test;

/**
 * @author ogondza.
 */
public class SlaveOptionsTest {

    @Test // instanceCap is a subject of different overriding rules
    public void defaultOverrides() {
        SlaveOptions unmodified = PluginTestRule.dummySlaveOptions().override(SlaveOptions.empty());

        assertEquals(new BootSource.VolumeSnapshot("id"), unmodified.getBootSource());
        assertEquals("hw", unmodified.getHardwareId());
        assertEquals("nw1,mw2", unmodified.getNetworkId());
        assertEquals("dummyUserDataId", unmodified.getUserDataId());
        assertEquals(1, (int) unmodified.getInstanceCap());
        assertEquals("pool", unmodified.getFloatingIpPool());
        assertEquals("sg", unmodified.getSecurityGroups());
        assertEquals("az", unmodified.getAvailabilityZone());
        assertEquals(1, (int) unmodified.getStartTimeout());
        assertEquals(10, (int) unmodified.getNumExecutors());
        assertEquals("jvmo", unmodified.getJvmOptions());
        assertEquals("fsRoot", unmodified.getFsRoot());
        assertEquals(null, unmodified.getKeyPairName());
        assertEquals(LauncherFactory.JNLP.JNLP, unmodified.getLauncherFactory());
        assertEquals(1, (int) unmodified.getRetentionTime());

        SlaveOptions override = SlaveOptions.builder()
                .bootSource(new BootSource.Image("iid"))
                .hardwareId("HW")
                .networkId("NW")
                .userDataId("UD")
                .instanceCap(42)
                .floatingIpPool("POOL")
                .securityGroups("SG")
                .availabilityZone("AZ")
                .startTimeout(4)
                .numExecutors(2)
                .jvmOptions("JVMO")
                .fsRoot("FSROOT")
                .keyPairName("KPN")
                .launcherFactory(new LauncherFactory.SSH(""))
                .retentionTime(3)
                .build()
        ;
        SlaveOptions overridden = PluginTestRule.dummySlaveOptions().override(override);

        assertEquals(new BootSource.Image("iid"), overridden.getBootSource());
        assertEquals("HW", overridden.getHardwareId());
        assertEquals("NW", overridden.getNetworkId());
        assertEquals("UD", overridden.getUserDataId());
        assertEquals(42, (int) overridden.getInstanceCap());
        assertEquals("POOL", overridden.getFloatingIpPool());
        assertEquals("SG", overridden.getSecurityGroups());
        assertEquals("AZ", overridden.getAvailabilityZone());
        assertEquals(4, (int) overridden.getStartTimeout());
        assertEquals(2, (int) overridden.getNumExecutors());
        assertEquals("JVMO", overridden.getJvmOptions());
        assertEquals("FSROOT", overridden.getFsRoot());
        assertEquals("KPN", overridden.getKeyPairName());
        assertThat(overridden.getLauncherFactory(), instanceOf(LauncherFactory.SSH.class));
        assertEquals(3, (int) overridden.getRetentionTime());
    }

    @Test
    public void eraseDefaults() {
        SlaveOptions defaults = SlaveOptions.builder().bootSource(new BootSource.Image("ID")).hardwareId("hw").networkId(null).floatingIpPool("a").build();
        SlaveOptions configured = SlaveOptions.builder().bootSource(new BootSource.Image("ID")).hardwareId("hw").networkId("MW").floatingIpPool("A").build();

        SlaveOptions actual = configured.eraseDefaults(defaults);

        SlaveOptions expected = SlaveOptions.builder().bootSource(null).hardwareId(null).networkId("MW").floatingIpPool("A").build();
        assertEquals(expected, actual);
        assertEquals(configured, defaults.override(actual));
    }

    @Test
    public void emptyStrings() {
        SlaveOptions nulls = SlaveOptions.empty();
        SlaveOptions emptyStrings = new SlaveOptions(
                null, "", "", "", null, "", "", "", null, "", null, "", "", null, null
        );
        SlaveOptions emptyBuilt = SlaveOptions.builder()
                .hardwareId("")
                .networkId("")
                .userDataId("")
                .floatingIpPool("")
                .securityGroups("")
                .availabilityZone("")
                .jvmOptions("")
                .fsRoot("")
                .keyPairName("")
                .build()
        ;
        assertEquals(nulls, emptyStrings);
        assertEquals(nulls, emptyBuilt);

        assertEquals(null, emptyStrings.getHardwareId());
        assertEquals(null, emptyStrings.getNetworkId());
        assertEquals(null, emptyStrings.getUserDataId());
        assertEquals(null, emptyStrings.getSecurityGroups());
        assertEquals(null, emptyStrings.getAvailabilityZone());
        assertEquals(null, emptyStrings.getJvmOptions());
        assertEquals(null, emptyStrings.getFsRoot());
        assertEquals(null, emptyStrings.getKeyPairName());
    }

    @Test
    public void modifyThroughBuilder() {
        assertEquals(PluginTestRule.dummySlaveOptions(), PluginTestRule.dummySlaveOptions().getBuilder().build());
    }
}
