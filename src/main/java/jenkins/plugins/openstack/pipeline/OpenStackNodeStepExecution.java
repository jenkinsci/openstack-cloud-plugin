package jenkins.plugins.openstack.pipeline;

import jenkins.model.Jenkins;
import jenkins.plugins.openstack.compute.*;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;

import javax.annotation.Nonnull;

/**
 * Pipeline Step Execution class to run OpenStack with declared @{@link SlaveOptions}
 *
 * @author drichtar@redhat.com
 */
public class OpenStackNodeStepExecution extends SynchronousNonBlockingStepExecution<JCloudsSlave> {

    private final @Nonnull String cloudName;
    private final @Nonnull SlaveOptions slaveOptions;

    OpenStackNodeStepExecution(OpenStackNodeStep openStackNodeStep, StepContext context) {
        super(context);
        this.cloudName = openStackNodeStep.getCloud();
        this.slaveOptions = openStackNodeStep.getSlaveOptions();
    }

    @Override
    protected JCloudsSlave run() throws Exception {
        JCloudsCloud jcl = JCloudsCloud.getByName(cloudName);
        TemporaryServer temporaryServer = new TemporaryServer(slaveOptions);
        ProvisioningActivity.Id id = new ProvisioningActivity.Id(this.cloudName);
        JCloudsSlave newSlave = temporaryServer.provisionSlave(jcl, id, null);
        Jenkins.getInstance().addNode(newSlave);
        return newSlave;
    }

    @Override
    public void stop(Throwable cause) throws Exception {
        super.stop(cause);
    }
}
