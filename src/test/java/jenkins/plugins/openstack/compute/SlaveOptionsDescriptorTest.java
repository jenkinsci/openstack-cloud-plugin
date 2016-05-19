package jenkins.plugins.openstack.compute;

import hudson.util.FormValidation;
import static hudson.util.FormValidation.Kind.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import hudson.util.ListBoxModel;
import jenkins.plugins.openstack.PluginTestRule;
import jenkins.plugins.openstack.compute.internal.Openstack;
import org.hamcrest.Description;
import org.hamcrest.Matchers;
import org.hamcrest.TypeSafeMatcher;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.openstack4j.api.OSClient;
import org.openstack4j.api.image.ImageService;
import org.openstack4j.model.image.Image;
import org.openstack4j.openstack.image.domain.GlanceImage;

import java.util.Collections;
import java.util.Objects;

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
        assertThat(d.doCheckInstanceCap(null, null), hasState(OK, "Inherited value: 10"));
        assertThat(d.doCheckInstanceCap(null, "3"), hasState(OK, "Inherited value: 3"));
        assertThat(d.doCheckInstanceCap(null, "err"), hasState(OK, "Inherited value: err")); // It is ok, error should be reported for the default
        assertThat(d.doCheckInstanceCap("1", "1"), hasState(OK, null)); // TODO do we want to report def == value ?
        // assertEquals(OK, d.doCheckInstanceCap("0", "1").kind); TODO this can be a handy way to disable the cloud/template temporarily

        assertThat(d.doCheckInstanceCap("err", null), hasState(ERROR, "Not a number"));
        assertThat(d.doCheckInstanceCap("err", "1"), hasState(ERROR, "Not a number"));
        assertThat(d.doCheckInstanceCap("-1", null), hasState(ERROR, "Not a positive number"));
        assertThat(d.doCheckInstanceCap("-1", "1"), hasState(ERROR, "Not a positive number"));
    }

    @Test
    public void doCheckStartTimeout() throws Exception {
        assertThat(d.doCheckStartTimeout(null, null), hasState(OK, "Inherited value: 600000"));
        assertThat(d.doCheckStartTimeout(null, "10"), hasState(OK, "Inherited value: 10"));
        assertThat(d.doCheckStartTimeout(null, "err"), hasState(OK, "Inherited value: err")); // It is ok, error should be reported for the default
        assertThat(d.doCheckStartTimeout("1", "1"), hasState(OK, null)); //"Inherited value: 1"

        assertThat(d.doCheckStartTimeout("0", "1"), hasState(ERROR, "Not a positive number"));
        assertThat(d.doCheckStartTimeout("err", null), hasState(ERROR, "Not a number"));
        assertThat(d.doCheckStartTimeout("err", "1"), hasState(ERROR, "Not a number"));
        assertThat(d.doCheckStartTimeout("-1", null), hasState(ERROR, "Not a positive number"));
        assertThat(d.doCheckStartTimeout("-1", "1"), hasState(ERROR, "Not a positive number"));
    }

    @Test
    public void doCheckNumExecutors() throws Exception {
        assertThat(d.doCheckNumExecutors(null, null), hasState(OK, "Inherited value: 1"));
        assertThat(d.doCheckNumExecutors(null, "10"), hasState(OK, "Inherited value: 10"));
        assertThat(d.doCheckNumExecutors(null, "err"), hasState(OK, "Inherited value: err")); // It is ok, error should be reported for the default
        assertThat(d.doCheckNumExecutors("1", "1"), hasState(OK, null)); //"Inherited value: 1"

        assertThat(d.doCheckNumExecutors("0", "1"), hasState(ERROR, "Not a positive number"));
        assertThat(d.doCheckNumExecutors("err", null), hasState(ERROR, "Not a number"));
        assertThat(d.doCheckNumExecutors("err", "1"), hasState(ERROR, "Not a number"));
        assertThat(d.doCheckNumExecutors("-1", null), hasState(ERROR, "Not a positive number"));
        assertThat(d.doCheckNumExecutors("-1", "1"), hasState(ERROR, "Not a positive number"));
    }

    @Test
    public void doCheckRetentionTime() throws Exception {
        assertThat(d.doCheckRetentionTime(null, null), hasState(OK, "Inherited value: 30"));
        assertThat(d.doCheckRetentionTime(null, "10"), hasState(OK, "Inherited value: 10"));
        assertThat(d.doCheckRetentionTime(null, "err"), hasState(OK, "Inherited value: err")); // It is ok, error should be reported for the default
        assertThat(d.doCheckRetentionTime("1", "1"), hasState(OK, null)); //"Inherited value: 1"
        assertThat(d.doCheckRetentionTime("0", "1"), hasState(OK, null));
        assertThat(d.doCheckRetentionTime("-1", null), hasState(OK, "Keep forever"));
        assertThat(d.doCheckRetentionTime("-1", "1"), hasState(OK, "Keep forever"));

        assertThat(d.doCheckRetentionTime("err", null), hasState(ERROR, "Not a number"));
        assertThat(d.doCheckRetentionTime("err", "1"), hasState(ERROR, "Not a number"));
    }

    public TypeSafeMatcher<FormValidation> hasState(final FormValidation.Kind kind, final String msg) {
        return new TypeSafeMatcher<FormValidation>() {
            @Override
            public void describeTo(Description description) {
                description.appendText(kind.toString() + ": " + msg);
            }

            @Override
            protected void describeMismatchSafely(FormValidation item, Description mismatchDescription) {
                mismatchDescription.appendText(item.kind + ": " + item.getMessage());
            }

            @Override
            protected boolean matchesSafely(FormValidation item) {
                return kind.equals(item.kind) && Objects.equals(item.getMessage(), msg);
            }
        };
    }

    @Test
    public void populateImageNamesNotIds() {
        Image image = new GlanceImage();
        image.setId("image-id");
        image.setName("image-name");

        Openstack os = j.fakeOpenstackFactory();
        doReturn(Collections.singletonList(image)).when(os).getSortedImages();

        ListBoxModel list = d.doFillImageIdItems("not-needed", "", "", "", "", "", "", "", "");
        assertEquals(2, list.size());
        ListBoxModel.Option item = list.get(1);
        assertEquals("image-name", item.name);
        assertEquals("image-name", item.value);
    }

    @Test @Issue("JENKINS-29993")
    public void acceptNullAsImageName() {
        Image image = new GlanceImage();
        image.setId("image-id");
        image.setName(null);

        OSClient osClient = mock(OSClient.class);
        ImageService imageService = mock(ImageService.class);
        when(osClient.images()).thenReturn(imageService);
        doReturn(Collections.singletonList(image)).when(imageService).listAll();

        j.fakeOpenstackFactory(new Openstack(osClient));

        ListBoxModel list = d.doFillImageIdItems("not-needed", "", "", "", "", "", "", "", "");
        assertThat(list.get(0).name, list, Matchers.<ListBoxModel.Option>iterableWithSize(2));
        assertEquals(2, list.size());
        ListBoxModel.Option item = list.get(1);
        assertEquals("image-id", item.name);
        assertEquals("image-id", item.value);

        verify(imageService).listAll();
        verifyNoMoreInteractions(imageService);
    }
}
