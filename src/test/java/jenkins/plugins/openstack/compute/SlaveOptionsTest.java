package jenkins.plugins.openstack.compute;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * @author ogondza.
 */
public class SlaveOptionsTest {

    @Test
    public void override() {
        SlaveOptions original = new SlaveOptions(
                "img", "hw", "nw", "ud", 1, "pool", "sg", "az", 1, null, 1, "jvmo", "fsRoot", "cid", JCloudsCloud.SlaveType.JNLP, 1
        );

        SlaveOptions overriden = original.override(SlaveOptions.builder().build());

        assertEquals("img", overriden.getImageId());
        assertEquals("hw", overriden.getHardwareId());
        assertEquals("nw", overriden.getNetworkId());
        assertEquals("ud", overriden.getUserDataId());
        assertEquals(1, (int) overriden.getInstanceCap());
        assertEquals("pool", overriden.getFloatingPool());
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
                .instanceCap(5)
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
        overriden = original.override(override);

        assertEquals("IMG", overriden.getImageId());
        assertEquals("HW", overriden.getHardwareId());
        assertEquals("NW", overriden.getNetworkId());
        assertEquals("UD", overriden.getUserDataId());
        assertEquals(5, (int) overriden.getInstanceCap());
        assertEquals("POOL", overriden.getFloatingPool());
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
        SlaveOptions nulls = SlaveOptions.builder().build();
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
}
