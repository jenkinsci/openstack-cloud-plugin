package jenkins.plugins.openstack.compute;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.Properties;

import com.gargoylesoftware.htmlunit.html.HtmlForm;
import hudson.remoting.Base64;
import jenkins.plugins.openstack.PluginTestRule;

import jenkins.plugins.openstack.compute.internal.Openstack;
import jenkins.plugins.openstack.compute.slaveopts.LauncherFactory;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.openstack4j.model.compute.Server;
import org.openstack4j.model.compute.builder.ServerCreateBuilder;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

public class JCloudsSlaveTemplateTest {

    @Rule
    public PluginTestRule j = new PluginTestRule();

    final String TEMPLATE_PROPERTIES = "name,labelString";
    final String CLOUD_PROPERTIES = "name,identity,credential,endPointUrl,zone";

    @Test
    public void configRoundtrip() throws Exception {
        JCloudsSlaveTemplate jnlpTemplate = new JCloudsSlaveTemplate(
                "jnlp-template", "openstack-slave-type1 openstack-type2", PluginTestRule.dummySlaveOptions().getBuilder().launcherFactory(LauncherFactory.JNLP.JNLP).build()
        );

        LauncherFactory.SSH slaveType = new LauncherFactory.SSH(j.dummySshCredential("sshid"), "mypath");
        JCloudsSlaveTemplate sshTemplate = new JCloudsSlaveTemplate(
                "ssh-template", "openstack-slave-type1 openstack-type2", PluginTestRule.dummySlaveOptions().getBuilder().launcherFactory(slaveType).build()
        );

        JCloudsCloud originalCloud = new JCloudsCloud(
                "my-openstack", "identity", "credential", "endPointUrl", "zone",
                SlaveOptions.empty(),
                Arrays.asList(jnlpTemplate, sshTemplate)
        );

        j.jenkins.clouds.add(originalCloud);

        HtmlForm form = j.createWebClient().goTo("configure").getFormByName("config");

        j.submit(form);

        final JCloudsCloud actualCloud = JCloudsCloud.getByName("my-openstack");
        j.assertEqualBeans(originalCloud, actualCloud, CLOUD_PROPERTIES);
        assertThat(actualCloud.getEffectiveSlaveOptions(), equalTo(originalCloud.getEffectiveSlaveOptions()));
        assertThat(actualCloud.getRawSlaveOptions(), equalTo(originalCloud.getRawSlaveOptions()));

        JCloudsSlaveTemplate actualJnlp = actualCloud.getTemplate("jnlp-template");
        j.assertEqualBeans(jnlpTemplate, actualJnlp, TEMPLATE_PROPERTIES);
        assertThat(actualJnlp.getEffectiveSlaveOptions(), equalTo(jnlpTemplate.getEffectiveSlaveOptions()));
        assertThat(actualJnlp.getRawSlaveOptions(), equalTo(jnlpTemplate.getRawSlaveOptions()));

        JCloudsSlaveTemplate actualSsh = actualCloud.getTemplate("ssh-template");
        j.assertEqualBeans(sshTemplate, actualSsh, TEMPLATE_PROPERTIES);
        assertThat(actualSsh.getEffectiveSlaveOptions(), equalTo(sshTemplate.getEffectiveSlaveOptions()));
        assertThat(actualSsh.getRawSlaveOptions(), equalTo(sshTemplate.getRawSlaveOptions()));
    }

    @Test
    public void eraseDefaults() throws Exception {
        SlaveOptions cloudOpts = PluginTestRule.dummySlaveOptions(); // Make sure nothing collides with defaults
        SlaveOptions templateOpts = cloudOpts.getBuilder().imageId("42").availabilityZone("other").build();
        assertEquals(cloudOpts.getHardwareId(), templateOpts.getHardwareId());

        JCloudsSlaveTemplate template = new JCloudsSlaveTemplate(
                "test-templateOpts", "openstack-slave-type1 openstack-type2", templateOpts
        );

        JCloudsCloud cloud = new JCloudsCloud(
                "my-openstack", "identity", "credential", "endPointUrl", "zone",
                cloudOpts,
                Collections.singletonList(template)
        );

        assertEquals(cloudOpts, cloud.getRawSlaveOptions());
        assertEquals(SlaveOptions.builder().imageId("42").availabilityZone("other").build(), template.getRawSlaveOptions());
    }

    @Test
    public void replaceUserData() throws Exception {
        SlaveOptions opts = j.defaultSlaveOptions();
        JCloudsSlaveTemplate template = j.dummySlaveTemplate(opts,"a");
        JCloudsCloud cloud = j.configureSlaveProvisioning(j.dummyCloud(template));
        Openstack os = cloud.getOpenstack();

        template.provision(cloud);

        ArgumentCaptor<ServerCreateBuilder> captor = ArgumentCaptor.forClass(ServerCreateBuilder.class);
        verify(os).bootAndWaitActive(captor.capture(), anyInt());

        Properties actual = new Properties();
        actual.load(new ByteArrayInputStream(Base64.decode(captor.getValue().build().getUserData())));
        assertEquals(opts.getFsRoot(), actual.getProperty("SLAVE_JENKINS_HOME"));
        assertEquals(opts.getJvmOptions(), actual.getProperty("SLAVE_JVM_OPTIONS"));
        assertEquals(j.getURL().toExternalForm(), actual.getProperty("JENKINS_URL"));
        assertEquals("a", actual.getProperty("SLAVE_LABELS"));
        assertEquals("${unknown} ${VARIABLE}", actual.getProperty("DO_NOT_REPLACE_THIS"));
    }

    @Test
    public void noFloatingPoolId() throws Exception {
        SlaveOptions opts = j.defaultSlaveOptions().getBuilder().floatingIpPool(null).build();
        JCloudsSlaveTemplate template = j.dummySlaveTemplate(opts,"a");
        JCloudsCloud cloud = j.configureSlaveProvisioning(j.dummyCloud(template));
        Openstack os = cloud.getOpenstack();

        template.provision(cloud);

        verify(os).bootAndWaitActive(any(ServerCreateBuilder.class), anyInt());
        verify(os, never()).assignFloatingIp(any(Server.class), any(String.class));
    }
}
