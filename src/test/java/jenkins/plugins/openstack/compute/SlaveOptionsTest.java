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
        SlaveOptions overriden = ORIGINAL.override(SlaveOptions.empty());

        assertEquals("img", overriden.getImageId());
        assertEquals("hw", overriden.getHardwareId());
        assertEquals("nw", overriden.getNetworkId());
        assertEquals("ud", overriden.getUserDataId());
        assertEquals("pool", overriden.getFloatingIpPool());
        assertEquals("sg", overriden.getSecurityGroups());
        assertEquals("az", overriden.getAvailabilityZone());
        assertEquals(1, (int) overriden.getStartTimeout());
        assertEquals(1, (int) overriden.getNumExecutors());
        assertEquals("jvmo", overriden.getJvmOptions());
        assertEquals("fsRoot", overriden.getFsRoot());
        assertEquals(null, overriden.getKeyPairName());
        assertEquals("cid", overriden.getCredentialsId());
        assertEquals(JCloudsCloud.SlaveType.JNLP, overriden.getSlaveType());
        assertEquals(1, (int) overriden.getRetentionTime());

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
        overriden = ORIGINAL.override(override);

        assertEquals("IMG", overriden.getImageId());
        assertEquals("HW", overriden.getHardwareId());
        assertEquals("NW", overriden.getNetworkId());
        assertEquals("UD", overriden.getUserDataId());
        assertEquals("POOL", overriden.getFloatingIpPool());
        assertEquals("SG", overriden.getSecurityGroups());
        assertEquals("AZ", overriden.getAvailabilityZone());
        assertEquals(4, (int) overriden.getStartTimeout());
        assertEquals(2, (int) overriden.getNumExecutors());
        assertEquals("JVMO", overriden.getJvmOptions());
        assertEquals("FSROOT", overriden.getFsRoot());
        assertEquals("KPN", overriden.getKeyPairName());
        assertEquals("cid", overriden.getCredentialsId());
        assertEquals(JCloudsCloud.SlaveType.SSH, overriden.getSlaveType());
        assertEquals(3, (int) overriden.getRetentionTime());
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
    public void modifyThrougBuilder() {
        assertEquals(ORIGINAL, ORIGINAL.getBuilder().build());
    }
}
