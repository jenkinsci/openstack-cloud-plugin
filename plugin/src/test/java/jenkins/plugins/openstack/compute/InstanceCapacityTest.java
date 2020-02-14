/*
 * The MIT License
 *
 * Copyright (c) Red Hat, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package jenkins.plugins.openstack.compute;

import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.labels.LabelAtom;
import hudson.slaves.NodeProvisioner;
import jenkins.plugins.openstack.PluginTestRule;
import org.hamcrest.CoreMatchers;
import org.hamcrest.Matchers;
import org.jenkinsci.plugins.cloudstats.CloudStatistics;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.LoggerRule;
import org.openstack4j.model.compute.Server;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class InstanceCapacityTest {

    @Rule
    public PluginTestRule j = new PluginTestRule();

    @Rule
    public LoggerRule lr = new LoggerRule();

    @Test
    public void useSeveralTemplatesToProvisionInOneBatchWhenTemplateInstanceCapExceeded() throws Exception {
        SlaveOptions opts = j.defaultSlaveOptions().getBuilder().instanceCap(1).build();
        JCloudsCloud cloud = j.configureSlaveLaunchingWithFloatingIP(j.dummyCloud(
                j.dummySlaveTemplate(opts, "label 1"),
                j.dummySlaveTemplate(opts, "label 2"),
                j.dummySlaveTemplate(opts, "label 3")
        ));

        Collection<NodeProvisioner.PlannedNode> plan = cloud.provision(Label.get("label"), 4);
        assertEquals(3, plan.size());

        int cntr = 1;
        for (NodeProvisioner.PlannedNode pn: plan) {
            LabelAtom expectedLabel = LabelAtom.get(String.valueOf(cntr));

            Set<LabelAtom> assignedLabels = pn.future.get().getAssignedLabels();
            assertTrue(assignedLabels.toString(), assignedLabels.contains(expectedLabel));
            cntr++;
        }
    }

    @Test
    public void reportInstanceCapBasedOnSlaves() throws IOException, Descriptor.FormException {
        SlaveOptions init = j.defaultSlaveOptions();
        JCloudsSlaveTemplate restrictedTmplt = j.dummySlaveTemplate(init.getBuilder().instanceCap(1).build(), "restricted common");
        JCloudsSlaveTemplate openTmplt = j.dummySlaveTemplate(init.getBuilder().instanceCap(null).build(), "open common");
        JCloudsCloud cloud = j.dummyCloud(init.getBuilder().instanceCap(2).build(), restrictedTmplt, openTmplt);
        j.configureSlaveLaunchingWithFloatingIP(cloud);

        Server server = j.mockServer().name("foo0").withFixedIPv4("0.0.0.0").get();
        ProvisioningActivity.Id id = new ProvisioningActivity.Id(cloud.name, restrictedTmplt.getName());
        j.jenkins.addNode(new JCloudsSlave(id, server, "restricted common", restrictedTmplt.getEffectiveSlaveOptions()));

        lr.capture(5);
        lr.record(JCloudsCloud.class, Level.INFO);

        assertEquals(0, cloud.provision(Label.get("restricted"), 1).size());
        List<String> restrictedMessages = lr.getMessages();
        assertThat(restrictedMessages, hasItem("Instance cap exceeded for cloud openstack while provisioning for label restricted"));
        assertThat(restrictedMessages, hasSize(1));


        assertEquals(1, cloud.provision(Label.get("open||open"), 3).size());
        List<String> openMessages = new ArrayList<>(lr.getMessages());
        openMessages.removeAll(restrictedMessages);
        assertThat(openMessages, hasItem("Instance cap exceeded for cloud openstack while provisioning for label open||open"));
        assertThat(openMessages, hasSize(1));
    }

    @Test
    public void doNotProvisionOnceInstanceCapReached() throws Exception {
        SlaveOptions init = j.defaultSlaveOptions();
        JCloudsSlaveTemplate restrictedTmplt = j.dummySlaveTemplate(init.getBuilder().instanceCap(1).build(), "restricted common");
        JCloudsSlaveTemplate openTmplt = j.dummySlaveTemplate(init.getBuilder().instanceCap(null).build(), "open common");
        JCloudsCloud cloud = j.dummyCloud(init.getBuilder().instanceCap(4).build(), restrictedTmplt, openTmplt);
        j.configureSlaveLaunchingWithFloatingIP(cloud);

        Label restricted = Label.get("restricted");
        Label open = Label.get("open");

        // Template quota exceeded
        assertProvisioned(1, cloud.provision(restricted, 2));
        assertEquals(1, runningServersCount(cloud));
        assertEquals(1, runningServersCount(restrictedTmplt));

        assertProvisioned(0, cloud.provision(restricted, 1));
        assertEquals(1, runningServersCount(cloud));
        assertEquals(1, runningServersCount(restrictedTmplt));

        // Cloud quota exceeded
        assertProvisioned(2, cloud.provision(open, 2));
        assertEquals(3, runningServersCount(cloud));
        assertEquals(2, runningServersCount(openTmplt));

        assertProvisioned(1, cloud.provision(open, 2));
        assertEquals(4, runningServersCount(cloud));
        assertEquals(3, runningServersCount(openTmplt));

        // Both exceeded
        assertProvisioned(0, cloud.provision(restricted, 1));
        assertProvisioned(0, cloud.provision(open, 1));
        assertEquals(4, runningServersCount(cloud));
        assertEquals(1, runningServersCount(restrictedTmplt));
        assertEquals(3, runningServersCount(openTmplt));

        // When one node gets deleted
        Server server = openTmplt.getRunningNodes().get(0);
        cloud.getOpenstack().destroyServer(server);
        j.jenkins.removeNode(j.jenkins.getNode(server.getName()));
        assertEquals(3, runningServersCount(cloud));

        // Choose the available one when multiple options
        assertProvisioned(1, cloud.provision(Label.get("common"), 1));
        assertEquals(4, runningServersCount(cloud));
        assertEquals(1, runningServersCount(restrictedTmplt));
        assertEquals(3, runningServersCount(openTmplt));
    }

    public int runningServersCount(JCloudsSlaveTemplate restrictedTmplt) {
        return restrictedTmplt.getRunningNodes().size();
    }

    public int runningServersCount(JCloudsCloud cloud) {
        return cloud.getOpenstack().getRunningNodes().size();
    }

    private void assertProvisioned(int expectedCount, Collection<NodeProvisioner.PlannedNode> nodes) throws Exception {
        assertEquals(expectedCount, nodes.size());
        for (NodeProvisioner.PlannedNode node : nodes) {
            node.future.get();
        }
    }
}
