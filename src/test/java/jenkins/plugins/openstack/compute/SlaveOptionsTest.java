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
                "img", "hw", "nw", "ud", true, "sg", "az", 1, "jvmo", "fsRoot", null, "cid", JCloudsCloud.SlaveType.JNLP, 1, 1
        );

        SlaveOptions overriden = original.override(SlaveOptions.builder().build());

        assertEquals("img", overriden.getImageId());
        assertEquals("hw", overriden.getHardwareId());
        assertEquals("nw", overriden.getNetworkId());
        assertEquals("ud", overriden.getUserDataId());
        assertEquals(true, overriden.getFloatingIps());
        assertEquals("sg", overriden.getSecurityGroups());
        assertEquals("az", overriden.getAvailabilityZone());
        assertEquals(1, (int) overriden.getNumExecutors());
        assertEquals("jvmo", overriden.getJvmOptions());
        assertEquals("fsRoot", overriden.getFsRoot());
        assertEquals(null, overriden.getKeyPairName());
        assertEquals("cid", overriden.getCredentialsId());
        assertEquals(JCloudsCloud.SlaveType.JNLP, overriden.getSlaveType());
        assertEquals(1, (int) overriden.getRetentionTime());
        assertEquals(1, (int) overriden.getStartTimeout());

        SlaveOptions override = SlaveOptions.builder()
                .imageId("IMG")
                .hardwareId("HW")
                .networkId("NW")
                .userDataId("UD")
                .floatingIps(false)
                .securityGroups("SG")
                .availabilityZone("AZ")
                .numExecutors(2)
                .jvmOptions("JVMO")
                .fsRoot("FSROOT")
                .keyPairName("KPN")
                .credentialsId(null)
                .slaveType(JCloudsCloud.SlaveType.SSH)
                .retentionTime(3)
                .startTimeout(4)
                .build()
        ;
        overriden = original.override(override);

        assertEquals("IMG", overriden.getImageId());
        assertEquals("HW", overriden.getHardwareId());
        assertEquals("NW", overriden.getNetworkId());
        assertEquals("UD", overriden.getUserDataId());
        assertEquals(false, overriden.getFloatingIps());
        assertEquals("SG", overriden.getSecurityGroups());
        assertEquals("AZ", overriden.getAvailabilityZone());
        assertEquals(2, (int) overriden.getNumExecutors());
        assertEquals("JVMO", overriden.getJvmOptions());
        assertEquals("FSROOT", overriden.getFsRoot());
        assertEquals("KPN", overriden.getKeyPairName());
        assertEquals("cid", overriden.getCredentialsId());
        assertEquals(JCloudsCloud.SlaveType.SSH, overriden.getSlaveType());
        assertEquals(3, (int) overriden.getRetentionTime());
        assertEquals(4, (int) overriden.getStartTimeout());
    }

    @Test
    public void emptyStrings() {
        SlaveOptions opts = SlaveOptions.builder().imageId("").build();
        assertNull(opts.getImageId());
    }
}
