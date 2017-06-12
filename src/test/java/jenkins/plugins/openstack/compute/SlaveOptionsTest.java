package jenkins.plugins.openstack.compute;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * @author ogondza.
 */
public class SlaveOptionsTest {

    /**
     * Reusable options instance guaranteed not to collide with defaults
     */
    public static final SlaveOptions CUSTOM = new SlaveOptions(
            "img", "hw", "nw", "ud", 1, "pool", "sg", "az", 1, null, 10, "jvmo", "fsRoot", "cid", JCloudsCloud.SlaveType.JNLP, 1, "t=1"
    );

    @Test // instanceCap is a subject of different overriding rules
    public void defaultOverrides() {
        SlaveOptions unmodified = CUSTOM.override(SlaveOptions.empty());

        assertEquals("img", unmodified.getImageId());
        assertEquals("hw", unmodified.getHardwareId());
        assertEquals("nw", unmodified.getNetworkId());
        assertEquals("ud", unmodified.getUserDataId());
        assertEquals(1, (int) unmodified.getInstanceCap());
        assertEquals("pool", unmodified.getFloatingIpPool());
        assertEquals("sg", unmodified.getSecurityGroups());
        assertEquals("az", unmodified.getAvailabilityZone());
        assertEquals(1, (int) unmodified.getStartTimeout());
        assertEquals(10, (int) unmodified.getNumExecutors());
        assertEquals("jvmo", unmodified.getJvmOptions());
        assertEquals("fsRoot", unmodified.getFsRoot());
        assertEquals(null, unmodified.getKeyPairName());
        assertEquals("cid", unmodified.getCredentialsId());
        assertEquals(JCloudsCloud.SlaveType.JNLP, unmodified.getSlaveType());
        assertEquals(1, (int) unmodified.getRetentionTime());
        assertEquals("t=1", unmodified.getMetadata());

        SlaveOptions override = SlaveOptions.builder()
                .imageId("IMG")
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
                .credentialsId(null)
                .slaveType(JCloudsCloud.SlaveType.SSH)
                .retentionTime(3)
                .build()
        ;
        SlaveOptions overridden = CUSTOM.override(override);

        assertEquals("IMG", overridden.getImageId());
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
        assertEquals("cid", overridden.getCredentialsId());
        assertEquals(JCloudsCloud.SlaveType.SSH, overridden.getSlaveType());
        assertEquals(3, (int) overridden.getRetentionTime());
    }

    @Test
    public void eraseDefaults() {
        SlaveOptions defaults = SlaveOptions.builder().imageId("img").hardwareId("hw").networkId(null).floatingIpPool("a").build();
        SlaveOptions configured = SlaveOptions.builder().imageId("IMG").hardwareId("hw").networkId("MW").floatingIpPool("A").build();

        SlaveOptions actual = configured.eraseDefaults(defaults);

        SlaveOptions expected = SlaveOptions.builder().imageId("IMG").hardwareId(null).networkId("MW").floatingIpPool("A").build();
        assertEquals(expected, actual);
        assertEquals(configured, defaults.override(actual));
    }

    @Test
    public void emptyStrings() {
        SlaveOptions nulls = SlaveOptions.empty();
        SlaveOptions emptyStrings = new SlaveOptions(
                "", "", "", "", null, "", "", "", null, "", null, "", "", "", null, null, null
        );
        SlaveOptions emptyBuilt = SlaveOptions.builder()
                .imageId("")
                .hardwareId("")
                .networkId("")
                .userDataId("")
                .floatingIpPool("")
                .securityGroups("")
                .availabilityZone("")
                .jvmOptions("")
                .fsRoot("")
                .keyPairName("")
                .credentialsId("")
                .build()
        ;
        assertEquals(nulls, emptyStrings);
        assertEquals(nulls, emptyBuilt);

        assertEquals(null, emptyStrings.getImageId());
        assertEquals(null, emptyStrings.getHardwareId());
        assertEquals(null, emptyStrings.getNetworkId());
        assertEquals(null, emptyStrings.getUserDataId());
        assertEquals(null, emptyStrings.getSecurityGroups());
        assertEquals(null, emptyStrings.getAvailabilityZone());
        assertEquals(null, emptyStrings.getJvmOptions());
        assertEquals(null, emptyStrings.getFsRoot());
        assertEquals(null, emptyStrings.getKeyPairName());
        assertEquals(null, emptyStrings.getCredentialsId());
    }

    @Test
    public void modifyThroughBuilder() {
        assertEquals(CUSTOM, CUSTOM.getBuilder().build());
    }
}
