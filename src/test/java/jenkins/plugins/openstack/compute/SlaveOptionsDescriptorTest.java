package jenkins.plugins.openstack.compute;

import static org.junit.Assert.*;

import hudson.util.FormValidation;
import static hudson.util.FormValidation.Kind.*;
import jenkins.plugins.openstack.PluginTestRule;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * @author ogondza.
 */
public class SlaveOptionsDescriptorTest {

    public @Rule PluginTestRule j = new PluginTestRule();

    private SlaveOptionsDescriptor d;

    @Before
    public void before() {
        d = (SlaveOptionsDescriptor) j.jenkins.getDescriptorOrDie(SlaveOptions.class);
    }

    @Test
    public void doCheckInstanceCap() throws Exception {
        assertEquals(OK, d.doCheckInstanceCap(null, null).kind);
        assertEquals(OK, d.doCheckInstanceCap(null, "10").kind);
        assertEquals(OK, d.doCheckInstanceCap(null, "err").kind); // It is ok, error should be reported for the default
        assertEquals(OK, d.doCheckInstanceCap("1", "1").kind);
        // assertEquals(OK, d.doCheckInstanceCap("0", "1").kind); TODO this can be a handy way to disable the cloud/template temporarily

        assertEquals(ERROR, d.doCheckInstanceCap("err", null).kind);
        assertEquals(ERROR, d.doCheckInstanceCap("err", "1").kind);
        assertEquals(ERROR, d.doCheckInstanceCap("-1", null).kind);
        assertEquals(ERROR, d.doCheckInstanceCap("-1", "1").kind);
    }

    @Test
    public void doCheckStartTimeout() throws Exception {
        assertEquals(OK, d.doCheckStartTimeout(null, null).kind);
        assertEquals(OK, d.doCheckStartTimeout(null, "10").kind);
        assertEquals(OK, d.doCheckStartTimeout(null, "err").kind); // It is ok, error should be reported for the default
        assertEquals(OK, d.doCheckStartTimeout("1", "1").kind);

        assertEquals(ERROR, d.doCheckStartTimeout("0", "1").kind);
        assertEquals(ERROR, d.doCheckStartTimeout("err", null).kind);
        assertEquals(ERROR, d.doCheckStartTimeout("err", "1").kind);
        assertEquals(ERROR, d.doCheckStartTimeout("-1", null).kind);
        assertEquals(ERROR, d.doCheckStartTimeout("-1", "1").kind);
    }

    @Test
    public void doCheckNumExecutors() throws Exception {
        assertEquals(OK, d.doCheckNumExecutors(null, null).kind);
        assertEquals(OK, d.doCheckNumExecutors(null, "10").kind);
        assertEquals(OK, d.doCheckNumExecutors(null, "err").kind); // It is ok, error should be reported for the default
        assertEquals(OK, d.doCheckNumExecutors("1", "1").kind);

        assertEquals(ERROR, d.doCheckNumExecutors("0", "1").kind);
        assertEquals(ERROR, d.doCheckNumExecutors("err", null).kind);
        assertEquals(ERROR, d.doCheckNumExecutors("err", "1").kind);
        assertEquals(ERROR, d.doCheckNumExecutors("-1", null).kind);
        assertEquals(ERROR, d.doCheckNumExecutors("-1", "1").kind);
    }

    @Test
    public void doCheckRetentionTime() throws Exception {
        assertEquals(OK, d.doCheckRetentionTime(null, null).kind);
        assertEquals(OK, d.doCheckRetentionTime(null, "10").kind);
        assertEquals(OK, d.doCheckRetentionTime(null, "err").kind); // It is ok, error should be reported for the default
        assertEquals(OK, d.doCheckRetentionTime("1", "1").kind);
        assertEquals(OK, d.doCheckRetentionTime("0", "1").kind);
        assertEquals(OK, d.doCheckRetentionTime("-1", null).kind);
        assertEquals(OK, d.doCheckRetentionTime("-1", "1").kind);

        assertEquals(ERROR, d.doCheckRetentionTime("err", null).kind);
        assertEquals(ERROR, d.doCheckRetentionTime("err", "1").kind);
    }
}
