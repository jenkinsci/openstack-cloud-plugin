package jenkins.plugins.openstack.pipeline

import jenkins.model.Jenkins
import org.jenkinsci.plugins.workflow.cps.CpsScript
import org.jenkinsci.plugins.pipeline.modeldefinition.Utils
import org.jenkinsci.plugins.pipeline.modeldefinition.agent.DeclarativeAgentScript

/**
 * Receives Map argument from @DeclarativeOpenstackAgent class and instantiates @OpenStackNodeStep
 *
 * @author drichtar@redhat.com
 */
public class DeclarativeOpenstackAgentScript extends DeclarativeAgentScript<DeclarativeOpenstackAgent> {

    DeclarativeOpenstackAgentScript(org.jenkinsci.plugins.workflow.cps.CpsScript s, DeclarativeOpenstackAgent declarativeOpenstackAgent) {
        super(s, declarativeOpenstackAgent)
    }

    @Override
    Closure run(Closure closure) {
        return {
            try {
                String labelName = script.openStackNodeStep(describable.asArgs).getSelfLabel().getName()
                script.node(labelName) {
                    closure.call()
                }
            } catch (Exception e) {
                script.getProperty("currentBuild").result = Utils.getResultFromException(e)
                throw new RuntimeException("Can't create node with proposed arguments", e)
            } finally {
                println(Jenkins.getInstance().clouds)
            }
        }
    }
}