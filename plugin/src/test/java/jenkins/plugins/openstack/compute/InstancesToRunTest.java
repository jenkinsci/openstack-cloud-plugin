package jenkins.plugins.openstack.compute;

import static org.hamcrest.Matchers.emptyIterable;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.*;

import hudson.util.FormValidation;
import jenkins.plugins.openstack.PluginTestRule;
import org.junit.Rule;
import org.junit.Test;

public class InstancesToRunTest {

    @Rule public PluginTestRule j = new PluginTestRule();

    @Test
    public void descriptorUiMethods() {
        InstancesToRun.DescriptorImpl desc = (InstancesToRun.DescriptorImpl) j.jenkins.getDescriptorOrDie(InstancesToRun.class);
        assertEquals(FormValidation.Kind.ERROR, desc.doCheckCount("0").kind);
        assertEquals(FormValidation.Kind.OK, desc.doCheckCount("1").kind);

        assertThat(desc.doFillCloudNameItems(), emptyIterable());
        assertThat(desc.doFillTemplateNameItems(""), emptyIterable());
        assertThat(desc.doFillTemplateNameItems(null), emptyIterable());

        j.configureSlaveLaunchingWithFloatingIP("l");

        assertThat(desc.doFillCloudNameItems().size(), equalTo(1));
        assertThat(desc.doFillCloudNameItems().get(0).value, equalTo("openstack"));

        assertThat(desc.doFillTemplateNameItems("openstack").size(), equalTo(1));
        assertThat(desc.doFillTemplateNameItems("openstack").get(0).value, equalTo("template0"));
    }
}
