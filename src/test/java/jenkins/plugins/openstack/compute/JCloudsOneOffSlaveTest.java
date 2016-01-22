package jenkins.plugins.openstack.compute;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Label;
import jenkins.plugins.openstack.PluginTestRule;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.*;

public class JCloudsOneOffSlaveTest {

    @Rule
    public PluginTestRule j = new PluginTestRule();

    @Test
    public void discardSlaveOnceUsed() throws Exception {
        j.configureDummySlaveToBeProvisioned();

        FreeStyleProject p = j.createFreeStyleProject();
        p.getBuildWrappersList().add(new JCloudsOneOffSlave());
        p.setAssignedLabel(Label.get("label"));

        FreeStyleBuild build = j.buildAndAssertSuccess(p);
        JCloudsComputer computer = (JCloudsComputer) build.getBuiltOn().toComputer();
        assertTrue("Slave should be discarded", computer.isPendingDelete());
    }
}