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

import hudson.model.AbstractBuild;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.queue.CauseOfBlockage;
import hudson.model.queue.QueueTaskDispatcher;
import hudson.model.queue.QueueTaskFuture;
import jenkins.plugins.openstack.PluginTestRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.TestExtension;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.emptyIterable;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SingleUseSlaveTest {

    @Rule
    public PluginTestRule j = new PluginTestRule();

    @Test
    public void doNotDeleteNewSlaveIfInstanceRequired() throws Exception {
        JCloudsCloud cloud = j.configureSlaveLaunchingWithFloatingIP(j.dummyCloud(j.dummySlaveTemplate(
                j.defaultSlaveOptions().getBuilder().retentionTime(0).instancesMin(1).build(),
                "label"
        )));
        JCloudsSlave slave = j.provision(cloud, "label");
        JCloudsComputer computer = slave.getComputer();
        computer.waitUntilOnline();

        computer.getRetentionStrategy().check(computer);

        assertFalse(computer.isPendingDelete());
    }

    @Test
    public void deleteUsedSlaveWhenOnlyNewInstancesAreRequired() throws Exception {
        JCloudsCloud cloud = j.configureSlaveLaunchingWithFloatingIP(j.dummyCloud(j.dummySlaveTemplate(
                j.defaultSlaveOptions().getBuilder().retentionTime(0).instancesMin(1).build(),
                "label"
        )));
        JCloudsSlave slave = j.provision(cloud, "label");
        JCloudsComputer computer = slave.getComputer();
        computer.waitUntilOnline();
        FreeStyleProject p = j.createFreeStyleProject();
        p.setAssignedNode(slave);
        FreeStyleBuild build = p.scheduleBuild2(0).waitForStart();
        j.waitForCompletion(build);
        j.waitUntilNoActivity();

        computer.getRetentionStrategy().check(computer);

        assertTrue(computer.isPendingDelete());
    }

    @Test
    public void discardSlaveImmediately() throws Exception {
        JCloudsCloud cloud = j.configureSlaveLaunchingWithFloatingIP(j.dummyCloud(j.dummySlaveTemplate(
                j.defaultSlaveOptions().getBuilder().retentionTime(0).instancesMin(0).build(),
                "label"
        )));
        JCloudsSlave slave = j.provision(cloud, "label");
        verifyOneOffContract(slave);
    }

    @Test
    public void discardSlaveImmediatelyDespiteOfInstanceMinRequirement() throws Exception {
        JCloudsCloud cloud = j.configureSlaveLaunchingWithFloatingIP(j.dummyCloud(j.dummySlaveTemplate(
                j.defaultSlaveOptions().getBuilder().retentionTime(0).instancesMin(1).build(),
                "label"
        )));
        JCloudsSlave slave = j.provision(cloud, "label");
        verifyOneOffContract(slave);
    }

    private void verifyOneOffContract(JCloudsSlave slave) throws Exception {
        // Should not get into pending delete state without job being executed
        JCloudsComputer computer = slave.getComputer();
        assertFalse(computer.isPendingDelete());
        slave.getRetentionStrategy().check(computer);
        assertFalse(computer.isPendingDelete());

        FreeStyleProject p = j.createFreeStyleProject();
        p.setAssignedNode(slave);
        j.buildAndAssertSuccess(p);

        j.waitUntilNoActivity();
        j.triggerOpenstackSlaveCleanup();

        assertThat(JCloudsComputer.getAll(), emptyIterable());
    }

    @Test
    public void sequenceOfJobsWillNotReuse() throws Exception {
        j.configureSlaveLaunchingWithFloatingIP(j.dummyCloud(j.dummySlaveTemplate(
                j.defaultSlaveOptions().getBuilder().retentionTime(0).instanceCap(1).build(),
                "label"
        )));

        Thread thread = new Thread(() -> {
            while (true) {
                j.triggerOpenstackSlaveCleanup();
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    break;
                }
            }
        });

        thread.start();
        try {
            Stream<FreeStyleProject> projects = Stream.of(
                    tiedProject(), tiedProject(), tiedProject(), tiedProject(), tiedProject()
            );

            Stream<QueueTaskFuture<FreeStyleBuild>> scheduled = projects.map(p -> p.scheduleBuild2(0)).collect(Collectors.toList()).stream();
            Stream<FreeStyleBuild> built = scheduled
                    .map(t -> {
                        try {
                            return t.get();
                        } catch (InterruptedException | ExecutionException e) {
                            throw new Error(e);
                        }
                    })
                    .collect(Collectors.toList()).stream();

            List<Node> nodes = built.map(AbstractBuild::getBuiltOn).collect(Collectors.toList());

            assertThat(nodes.size(), equalTo(5));
        } finally {
            thread.interrupt();
        }
    }

    private FreeStyleProject tiedProject() throws IOException {
        FreeStyleProject p = j.createFreeStyleProject();
        p.setAssignedLabel(Label.get("label"));
        return p;
    }

    @Test
    public void doNotPendingDeleteBeforeItIsUsedIfRetentionTimeZeroAndMinInstancesZero() throws Exception {
        JCloudsCloud cloud = j.configureSlaveLaunchingWithFloatingIP(j.dummyCloud(j.dummySlaveTemplate(
                j.defaultSlaveOptions().getBuilder().retentionTime(0).instancesMin(0).build(),
                "label"
        )));
        JCloudsSlave slave = j.provision(cloud, "label");
        JCloudsComputer computer = slave.getComputer();

        FreeStyleProject p = j.createFreeStyleProject();
        p.setAssignedNode(slave);

        //block item to simulate state, that item is prepared to be executed (BuildableItem), but executor has still not taken it.
        QueueTaskDispatcher.all().get(QueueTaskDispatcherTest.class).waiting(true);
        QueueTaskFuture future = p.scheduleBuild2(0);

        //wait transferring a task from waiting items into buildable items can take some time
        Thread.sleep(500);

        //call check computer whether it should be marked pending delete.
        computer.getRetentionStrategy().check(computer);
        assertFalse(computer.isPendingDelete());

        //allow execute the task in the queue
        QueueTaskDispatcher.all().get(QueueTaskDispatcherTest.class).waiting(false);

        future.get();
        j.waitUntilNoActivity();

        assertTrue(computer.isPendingDelete());
    }

    @TestExtension
    public static class QueueTaskDispatcherTest extends QueueTaskDispatcher {

        private boolean wait = false;

        public CauseOfBlockage canTake(Node node, Queue.BuildableItem item) {
            if(wait) {
                return new CauseOfBlockage() {
                    @Override
                    public String getShortDescription() {
                        return "block";
                    }
                };
            }
            return null;
        }

        public void waiting(boolean wait){
            this.wait = wait;
        }
    }
}
