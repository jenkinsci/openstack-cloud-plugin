package jenkins.plugins.openstack.compute;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * @author ogondza.
 */
public class SlaveOptionsTest {

    public static final SlaveOptions ORIGINAL = new SlaveOptions(
            "img", "hw", "nw", "ud", 1, "pool", "sg", "az", 1, null, 1, "jvmo", "fsRoot", "cid", JCloudsCloud.SlaveType.JNLP, 1
    );

    @Test // instanceCap is a subject of different overriding rules
    public void defaultOverrides() {
        SlaveOptions overridden = ORIGINAL.override(SlaveOptions.empty());

        assertEquals("img", overridden.getImageId());
        assertEquals("hw", overridden.getHardwareId());
        assertEquals("nw", overridden.getNetworkId());
        assertEquals("ud", overridden.getUserDataId());
        assertEquals("pool", overridden.getFloatingIpPool());
        assertEquals("sg", overridden.getSecurityGroups());
        assertEquals("az", overridden.getAvailabilityZone());
        assertEquals(1, (int) overridden.getStartTimeout());
        assertEquals(1, (int) overridden.getNumExecutors());
        assertEquals("jvmo", overridden.getJvmOptions());
        assertEquals("fsRoot", overridden.getFsRoot());
        assertEquals(null, overridden.getKeyPairName());
        assertEquals("cid", overridden.getCredentialsId());
        assertEquals(JCloudsCloud.SlaveType.JNLP, overridden.getSlaveType());
        assertEquals(1, (int) overridden.getRetentionTime());

        SlaveOptions override = SlaveOptions.builder()
                .imageId("IMG")
                .hardwareId("HW")
                .networkId("NW")
                .userDataId("UD")
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
        overridden = ORIGINAL.override(override);

        assertEquals("IMG", overridden.getImageId());
        assertEquals("HW", overridden.getHardwareId());
        assertEquals("NW", overridden.getNetworkId());
        assertEquals("UD", overridden.getUserDataId());
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
                "", "", "", "", null, "", "", "", null, "", null, "", "", "", null, null
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
    public void instanceCap() {
        SlaveOptions none = SlaveOptions.empty();

        SlaveOptions ten = SlaveOptions.builder().instanceCap(10).build();
        SlaveOptions two = SlaveOptions.builder().instanceCap(2).build();

        assertEquals(10, (int) none.override(ten).getInstanceCap());
        assertEquals(10, (int) ten.override(none).getInstanceCap());
        assertEquals(2, (int) ten.override(two).getInstanceCap());
        assertEquals(2, (int) two.override(ten).getInstanceCap());
    }

    @Test
    public void modifyThroughBuilder() {
        assertEquals(ORIGINAL, ORIGINAL.getBuilder().build());
    }
}
