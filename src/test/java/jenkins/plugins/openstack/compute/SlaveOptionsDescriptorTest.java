package jenkins.plugins.openstack.compute;

import hudson.util.ComboBoxModel;
import hudson.util.FormValidation;
import static hudson.util.FormValidation.Kind.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import jenkins.plugins.openstack.PluginTestRule;
import jenkins.plugins.openstack.compute.auth.OpenstackCredential;
import jenkins.plugins.openstack.compute.auth.OpenstackCredentials;
import jenkins.plugins.openstack.compute.internal.Openstack;
import org.hamcrest.Description;
import org.hamcrest.TypeSafeMatcher;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.openstack4j.api.exceptions.AuthenticationException;
import org.openstack4j.model.compute.ext.AvailabilityZone;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;

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
    public void doCheckInstanceCap() {
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
    public void doCheckStartTimeout() {
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
    public void doCheckNumExecutors() {
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
    public void doCheckRetentionTime() {
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

    public static TypeSafeMatcher<FormValidation> hasState(final FormValidation.Kind kind, final String msg) {
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

    public static TypeSafeMatcher<FormValidation> hasState(final FormValidation expected) {
        return new TypeSafeMatcher<FormValidation>() {
            @Override
            public void describeTo(Description description) {
                description.appendText(expected.kind.toString() + ": " + expected.getMessage());
            }

            @Override
            protected void describeMismatchSafely(FormValidation item, Description mismatchDescription) {
                mismatchDescription.appendText(item.kind + ": " + item.getMessage());
            }

            @Override
            protected boolean matchesSafely(FormValidation item) {
                return expected.kind.equals(item.kind) && Objects.equals(item.getMessage(), expected.getMessage());
            }
        };
    }

    @Test
    public void doFillAvailabilityZoneItemsGivenAZsThenPopulatesList() {
        final AvailabilityZone az1 = mock(AvailabilityZone.class, "az1");
        final AvailabilityZone az2 = mock(AvailabilityZone.class, "az2");
        final String openstackAuth = j.dummyCredential();
        when(az1.getZoneName()).thenReturn("az1Name");
        when(az2.getZoneName()).thenReturn("az2Name");
        final List<AvailabilityZone> azs = Arrays.asList(az1, az2);
        final Openstack os = j.fakeOpenstackFactory();
        doReturn(azs).when(os).getAvailabilityZones();

        final ComboBoxModel actual = d.doFillAvailabilityZoneItems("az2Name", "OSurl", false, openstackAuth, "OSzone");

        assertEquals(2, actual.size());
        final String az1Option = actual.get(0);
        assertThat(az1Option, equalTo("az1Name"));
        final String az2Option = actual.get(1);
        assertThat(az2Option, equalTo("az2Name"));
    }

    @Test
    public void doFillAvailabilityZoneItemsGivenNoSupportForAZsThenGivesEmptyList() {
        final Openstack os = j.fakeOpenstackFactory();
        final String openstackAuth = j.dummyCredential();
        doThrow(new RuntimeException("OpenStack said no")).when(os).getAvailabilityZones();

        final ComboBoxModel actual = d.doFillAvailabilityZoneItems("az2Name", "OSurl", false, openstackAuth, "OSzone");

        assertEquals(0, actual.size());
    }

    @Test
    public void doCheckAvailabilityZoneGivenAZThenReturnsOK() throws Exception {
        final String value = "chosenAZ";
        final String def = "";
        final Openstack os = j.fakeOpenstackFactory();
        final FormValidation expected = FormValidation.ok();
        final String openstackAuth = j.dummyCredential();

        final FormValidation actual = d.doCheckAvailabilityZone(value, def, "OSurl", false,"OSurl", openstackAuth,openstackAuth,"OSzone", "OSzone");

        assertThat(actual, hasState(expected));
        verifyNoMoreInteractions(os);
    }

    @Test
    public void doCheckAvailabilityZoneGivenDefaultAZThenReturnsOKWithDefault() throws Exception {
        final String value = "";
        final String def = "defaultAZ";
        final Openstack os = j.fakeOpenstackFactory();
        final String openstackAuth = j.dummyCredential();

        final FormValidation actual = d.doCheckAvailabilityZone(value, def,  "OSurl", false,"OSurl", openstackAuth,openstackAuth,"OSzone", "OSzone");

        assertThat(actual, hasState(OK, "Inherited value: " + def));
        verifyNoMoreInteractions(os);
    }

    @Test
    public void doCheckAvailabilityZoneGivenNoAZAndOnlyOneZoneToChooseFromThenReturnsOK() throws Exception {
        final AvailabilityZone az1 = mock(AvailabilityZone.class, "az1");
        when(az1.getZoneName()).thenReturn("az1Name");
        final List<AvailabilityZone> azs = Collections.singletonList(az1);
        final Openstack os = j.fakeOpenstackFactory();
        doReturn(azs).when(os).getAvailabilityZones();
        final String value = "";
        final String def = "";
        final FormValidation expected = FormValidation.ok();
        final String openstackAuth = j.dummyCredential();

        final FormValidation actual = d.doCheckAvailabilityZone(value, def, "OSurl", false,"OSurl", openstackAuth,openstackAuth, "OSzone", "OSzone");

        assertThat(actual, hasState(expected));
    }

    @Test
    public void doCheckAvailabilityZoneGivenNoAZAndNoSupportForAZsThenReturnsOK() throws Exception {
        final Openstack os = j.fakeOpenstackFactory();
        doThrow(new RuntimeException("OpenStack said no")).when(os).getAvailabilityZones();
        final String value = "";
        final String def = "";
        final FormValidation expected = FormValidation.ok();
        final String openstackAuth = j.dummyCredential();

        final FormValidation actual = d.doCheckAvailabilityZone(value, def, "OSurl", false,"OSurl", openstackAuth, openstackAuth, "OSzone", "OSzone");

        assertThat(actual, hasState(expected));
    }

    @Test
    public void doCheckAvailabilityZoneGivenNoAZAndMultipleZoneToChooseFromThenReturnsWarning() throws Exception {
        final AvailabilityZone az1 = mock(AvailabilityZone.class, "az1");
        final AvailabilityZone az2 = mock(AvailabilityZone.class, "az2");
        when(az1.getZoneName()).thenReturn("az1Name");
        when(az2.getZoneName()).thenReturn("az2Name");
        final List<AvailabilityZone> azs = Arrays.asList(az1, az2);
        final Openstack os = j.fakeOpenstackFactory();
        doReturn(azs).when(os).getAvailabilityZones();
        final String value = "";
        final String def = "";
        final FormValidation expected = FormValidation.warning("Ambiguity warning: Multiple zones found.");
        final String openstackAuth = j.dummyCredential();
        final FormValidation actual = d.doCheckAvailabilityZone(value, def, "OSurl", false, "OSurl",openstackAuth, openstackAuth, "OSzone", "OSzone");

        assertThat(actual, hasState(expected));
    }

    @Test
    public void fillDependencies() throws Exception {
        List<String> expected = Arrays.asList(
                "../endPointUrl", "../../endPointUrl",
                "../ignoreSsl", "../../ignoreSsl",
                "../credentialId", "../../credentialId",
                "../zone", "../../zone"
        );

        assertThat(getFillDependencies("keyPairName"), equalTo(expected));
        assertThat(getFillDependencies("floatingIpPool"), equalTo(expected));
        assertThat(getFillDependencies("hardwareId"), equalTo(expected));

        assertFillWorks("floatingIpPool");
        assertFillWorks("hardwareId");
        assertFillWorks("keyPairName");
    }

    private void assertFillWorks(String attribute) throws Exception {
        final String END_POINT = "END_POINT-" + attribute;
        final Boolean IGNORE_SSL = false;
        final String CREDENTIALID = j.dummyCredential();
        final String REGION = "REGION";
        final String QUERY_STRING = String.format(
                "?endPointUrl=%s&ignoreSsl=%s&credentialId=%s&zone=%s",
                END_POINT, IGNORE_SSL, CREDENTIALID, REGION
        );

        String contextPath = j.getURL().getFile();
        String fillUrl = getFillUrl(attribute);
        assertThat(fillUrl, startsWith(contextPath));
        fillUrl = fillUrl.substring(contextPath.length());

        Openstack.FactoryEP factory = j.mockOpenstackFactory();
        when(
                factory.getOpenstack(anyString(), anyBoolean(), any(OpenstackCredential.class), anyString())
        ).thenThrow(
                new AuthenticationException("No one cares as we are testing if correct credentials are passed in", 42)
        );

        j.createWebClient().goTo(fillUrl + QUERY_STRING, "application/json");

        verify(factory).getOpenstack(eq(END_POINT), eq(IGNORE_SSL), eq(OpenstackCredentials.getCredential(CREDENTIALID)), eq(REGION));
        verifyNoMoreInteractions(factory);
    }

    private List<String> getFillDependencies(final String field) throws Exception {
        final HashMap<String, Object> map = getFillData(field);

        List<String> out = new ArrayList<>(Arrays.asList(((String) map.get("fillDependsOn")).split(" ")));
        assertTrue(out.contains(field));
        out.remove(field);
        return out;
    }

    private String getFillUrl(final String field) throws Exception {
        final HashMap<String, Object> map = getFillData(field);

        return (String) map.get("fillUrl");
    }

    private HashMap<String, Object> getFillData(final String field) throws Exception {
        final HashMap<String, Object> map = new HashMap<>();
        // StaplerRequest required
        j.executeOnServer(new Callable<Void>() {
            @Override public Void call() {
                SlaveOptionsDescriptor d = (SlaveOptionsDescriptor) j.jenkins.getDescriptorOrDie(SlaveOptions.class);
                d.calcFillSettings(field, map);
                return null;
            }
        });
        return map;
    }
}
