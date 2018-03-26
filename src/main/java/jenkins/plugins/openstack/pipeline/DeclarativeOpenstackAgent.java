package jenkins.plugins.openstack.pipeline;

import hudson.Extension;
import jenkins.plugins.openstack.compute.slaveopts.BootSource;
import jenkins.plugins.openstack.compute.slaveopts.LauncherFactory;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.pipeline.modeldefinition.agent.DeclarativeAgent;
import org.jenkinsci.plugins.pipeline.modeldefinition.agent.DeclarativeAgentDescriptor;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.Map;
import java.util.TreeMap;

/**
 * OpenStack agent for Declarative Pipeline feature.
 *
 * @author drichtar@redhat.com
 */
public class DeclarativeOpenstackAgent extends DeclarativeAgent<DeclarativeOpenstackAgent> {

    private String cloud;

    private BootSource bootSource;
    private String hardwareId;
    private String networkId;
    private String userDataId;
    private String floatingIpPool;
    private String securityGroups;
    private String availabilityZone;
    private Integer startTimeout;
    private final String keyPairName;
    private final String jvmOptions;
    private final String fsRoot;
    private final LauncherFactory launcherFactory;

    /**
     * Constructor with required parameters for new OpenStack machine
     * @param cloud name of predefined cloud
     * @param bootSource The source media (Image, VolumeSnapshot etc) that the Instance is booted from.
     * @param hardwareId Specifies Machine size
     * @param networkId Network(s)
     * @param userDataId User Data
     * @param floatingIpPool Floating IP pool
     * @param securityGroups Specify security groups to determine whether network ports are opened or blocked on your instances.
     * @param availabilityZone An availability zone groups network nodes that run services like DHCP, L3, FW, and others.
     *                         It is defined as an agent’s attribute on the network node.
     *                         This allows users to associate an availability zone with their resources so that the resources get high availability.
     * @param startTimeout Startup Timeout
     * @param keyPairName Specify the name of the key pair that you created
     * @param jvmOptions Custom JVM Options
     * @param fsRoot Remote FS Root
     * @param launcherFactory Launcher Factory - JNLP or SSH
     */
    @DataBoundConstructor
    public DeclarativeOpenstackAgent(String cloud, BootSource bootSource, String hardwareId, String networkId, String userDataId, String floatingIpPool, String securityGroups, String availabilityZone, Integer startTimeout, String keyPairName, String jvmOptions, String fsRoot, LauncherFactory launcherFactory) {
        this.cloud = cloud;
        this.bootSource = bootSource;
        this.hardwareId = hardwareId;
        this.networkId = networkId;
        this.userDataId = userDataId;
        this.floatingIpPool = floatingIpPool;
        this.securityGroups = securityGroups;
        this.availabilityZone = availabilityZone;
        this.startTimeout = startTimeout;
        this.keyPairName = keyPairName;
        this.jvmOptions = jvmOptions;
        this.fsRoot = fsRoot;
        this.launcherFactory = launcherFactory;
    }

    /**
     * Maps {@link jenkins.plugins.openstack.compute.SlaveOptions} parameters into a TreeMap to pass to <tt>jenkins.plugins.openstack.pipeline.DeclarativeOpenstackAgentScript</tt>
     *
     * @return argMap
     */
    public Map<String,Object> getAsArgs() {
        Map<String,Object> argMap = new TreeMap<>();

        argMap.put("cloud", cloud);
        argMap.put("bootSource", bootSource);
        argMap.put("hardwareId", hardwareId);
        argMap.put("networkId", networkId);
        argMap.put("userDataId", userDataId);
        argMap.put("floatingIpPool", floatingIpPool);
        argMap.put("securityGroups", securityGroups);
        argMap.put("availabilityZone", availabilityZone);
        argMap.put("startTimeout", startTimeout);
        argMap.put("keyPairName", keyPairName);
        argMap.put("jvmOptions", jvmOptions);
        argMap.put("fsRoot", fsRoot);
        argMap.put("launcherFactory", launcherFactory);

        return argMap;
    }

    /**
     * Defines the name openstack for the new type of agent.
     */
    @Extension
    @Symbol("openstack")
    public static class DescriptorImpl extends DeclarativeAgentDescriptor<DeclarativeOpenstackAgent> {
    }
}
