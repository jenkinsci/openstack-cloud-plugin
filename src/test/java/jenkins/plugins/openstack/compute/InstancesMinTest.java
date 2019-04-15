/*
 *
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

import jenkins.plugins.openstack.PluginTestRule;
import org.jenkinsci.plugins.cloudstats.CloudStatistics;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.junit.Rule;
import org.junit.Test;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.iterableWithSize;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;

public class InstancesMinTest {

    @Rule
    public PluginTestRule j = new PluginTestRule();

    @Test
    public void createSlavesUpToLimit() throws Exception {
        JCloudsCloud cloud = j.configureSlaveLaunchingWithFloatingIP(j.dummyCloud(j.dummySlaveTemplate(
                j.defaultSlaveOptions().getBuilder().instancesMin(2).build(),
                "label"
        )));
        j.provision(cloud, "label");

        balanceRetentionAndPrecreation(); // Should add one more

        assertThat(provisioningActivities(), iterableWithSize(2));
        List<JCloudsComputer> computers = JCloudsComputer.getAll();
        assertThat(computers, iterableWithSize(2));
        for (JCloudsComputer c : computers) {
            assertFalse(c.isPendingDelete());
        }
    }

    @Test
    public void doNotCreateNorDeleteWhenSatisfied() throws Exception {
        JCloudsCloud cloud = j.configureSlaveLaunchingWithFloatingIP(j.dummyCloud(j.dummySlaveTemplate(
                j.defaultSlaveOptions().getBuilder().instancesMin(1).build(),
                "label"
        )));
        j.provision(cloud, "label");

        balanceRetentionAndPrecreation(); // Should do nothing as we have exactly one

        assertThat(provisioningActivities(), iterableWithSize(1));
        List<JCloudsComputer> computers = JCloudsComputer.getAll();
        assertThat(computers, iterableWithSize(1));
        for (JCloudsComputer c : computers) {
            assertFalse(c.isPendingDelete());
        }

        j.provision(cloud, "label");

        balanceRetentionAndPrecreation(); // Should do nothing as retention time of machines over limit is not due
        assertThat(provisioningActivities(), iterableWithSize(2));
        computers = JCloudsComputer.getAll();
        assertThat(computers, iterableWithSize(2));
        for (JCloudsComputer c : computers) {
            assertFalse(c.isPendingDelete());
        }
    }

    @Test
    public void removeSlavesOverLimit() throws Exception {
        JCloudsCloud cloud = j.configureSlaveLaunchingWithFloatingIP(j.dummyCloud(j.dummySlaveTemplate(
                j.defaultSlaveOptions().getBuilder().retentionTime(1).instancesMin(1).build(),
                "label"
        )));
        JCloudsSlave s1 = j.provision(cloud, "label");
        JCloudsSlave s2 = j.provision(cloud, "label");

        balanceRetentionAndPrecreation();

        assertThat(provisioningActivities(), iterableWithSize(2));
        assertThat(JCloudsComputer.getAll(), iterableWithSize(2));

        Thread.sleep(60 * 1000); // Wait for slaves are overdue

        enforceRetention();

        assertThat(provisioningActivities(), iterableWithSize(2));
        assertThat(JCloudsComputer.getAll(), iterableWithSize(2));
        assertNotEquals(
                "One slave should be scheduled for deletion",
                s1.getComputer().isPendingDelete(), s2.getComputer().isPendingDelete()
        );

        balanceRetentionAndPrecreation();

        assertThat(provisioningActivities(), iterableWithSize(2));
        assertThat(JCloudsComputer.getAll(), iterableWithSize(2));
        assertNotEquals(
                "One slave should be scheduled for deletion",
                s1.getComputer().isPendingDelete(), s2.getComputer().isPendingDelete()
        );
    }

    @Test
    public void doNotOverprovision() {
        j.configureSlaveLaunchingWithFloatingIP(j.dummyCloud(j.dummySlaveTemplate(
                j.defaultSlaveOptions().getBuilder().retentionTime(1).instancesMin(1).build(),
                "label"
        )));

        j.triggerSlavePreCreation();
        j.triggerSlavePreCreation();
        j.triggerSlavePreCreation();
        j.triggerSlavePreCreation();

        assertThat(provisioningActivities(), iterableWithSize(1));
    }

    private List<ProvisioningActivity> provisioningActivities() {
        return CloudStatistics.get().getActivities();
    }

    private void balanceRetentionAndPrecreation() {
        j.triggerSlavePreCreation();
        enforceRetention();
        j.triggerSlavePreCreation();
        enforceRetention();
    }

    private void enforceRetention() {
        JCloudsComputer.getAll().stream().map(JCloudsComputer::getNode).forEach(n -> n.getRetentionStrategy().check(n.getComputer()));
    }
}
