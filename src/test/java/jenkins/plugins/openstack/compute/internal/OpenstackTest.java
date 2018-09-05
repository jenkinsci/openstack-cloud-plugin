package jenkins.plugins.openstack.compute.internal;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyMapOf;
import static org.mockito.Matchers.argThat;
import static org.mockito.Mockito.*;

import org.hamcrest.Matchers;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.openstack4j.api.OSClient;
import org.openstack4j.api.compute.ComputeFloatingIPService;
import org.openstack4j.api.compute.ext.ZoneService;
import org.openstack4j.api.exceptions.ClientResponseException;
import org.openstack4j.api.image.ImageService;
import org.openstack4j.api.storage.BlockVolumeSnapshotService;
import org.openstack4j.model.common.ActionResponse;
import org.openstack4j.model.compute.Fault;
import org.openstack4j.model.compute.FloatingIP;
import org.openstack4j.model.compute.Server;
import org.openstack4j.model.compute.builder.ServerCreateBuilder;
import org.openstack4j.model.compute.ext.AvailabilityZone;
import org.openstack4j.model.image.Image;
import org.openstack4j.model.storage.block.Volume;
import org.openstack4j.model.storage.block.VolumeSnapshot;
import org.openstack4j.openstack.compute.domain.NovaFloatingIP;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

@SuppressWarnings({
        "rawtypes",
        "unchecked"
})
public class OpenstackTest {

    @Test
    public void getImagesReturnsImagesIndexedByNameSortedByAge() {
        final Image mockImageWithNullName = mock(Image.class);
        when(mockImageWithNullName.getId()).thenReturn("mockImageWithNullNameId");
        final Image mockImageNamedFoo = mock(Image.class);
        when(mockImageNamedFoo.getId()).thenReturn("mockImageNamedFooId");
        when(mockImageNamedFoo.getName()).thenReturn("Foo");
        final Image mockImageNamedBar1 = mock(Image.class);
        when(mockImageNamedBar1.getId()).thenReturn("mockImageNamedBar1Id");
        when(mockImageNamedBar1.getName()).thenReturn("Bar");
        when(mockImageNamedBar1.getUpdatedAt()).thenReturn(new Date(11111));
        when(mockImageNamedBar1.getCreatedAt()).thenReturn(new Date(1111));
        final Image mockImageNamedBar2 = mock(Image.class);
        when(mockImageNamedBar2.getId()).thenReturn("mockImageNamedBar2Id");
        when(mockImageNamedBar2.getName()).thenReturn("Bar");
        when(mockImageNamedBar2.getUpdatedAt()).thenReturn(new Date(10000));
        when(mockImageNamedBar2.getCreatedAt()).thenReturn(new Date(1111));
        final Image mockImageNamedBar3 = mock(Image.class);
        when(mockImageNamedBar3.getId()).thenReturn("mockImageNamedBar3Id");
        when(mockImageNamedBar3.getName()).thenReturn("Bar");
        when(mockImageNamedBar3.getUpdatedAt()).thenReturn(new Date(11111));
        when(mockImageNamedBar3.getCreatedAt()).thenReturn(new Date(1000));
        final List images = Arrays.asList(mockImageNamedBar1, mockImageWithNullName, mockImageNamedBar2,
                mockImageNamedFoo, mockImageNamedBar3);
        final OSClient mockClient = mock(OSClient.class, RETURNS_DEEP_STUBS);
        when(mockClient.images().listAll()).thenReturn(images);
        final Collection<Image> images0 = new ArrayList<>(
                Arrays.asList(mockImageNamedBar2, mockImageNamedBar3, mockImageNamedBar1));
        final Collection<Image> images1 = new ArrayList<>(Arrays.asList(mockImageNamedFoo));
        final Collection<Image> images2 = new ArrayList<>(Arrays.asList(mockImageWithNullName));

        final Openstack instance = new Openstack(mockClient);
        final Map<String, Collection<Image>> actual = instance.getImages();

        // Result keys should be in name order
        final Iterator<Map.Entry<String, Collection<Image>>> iterator = actual.entrySet().iterator();
        final Map.Entry<String, Collection<Image>> entry0 = iterator.next();
        assertThat(entry0.getKey(), equalTo("Bar"));
        assertThat(new ArrayList<>(entry0.getValue()), equalTo(images0));
        final Map.Entry<String, Collection<Image>> entry1 = iterator.next();
        assertThat(entry1.getKey(), equalTo("Foo"));
        assertThat(new ArrayList<>(entry1.getValue()), equalTo(images1));
        final Map.Entry<String, Collection<Image>> entry2 = iterator.next();
        assertThat(entry2.getKey(), equalTo("mockImageWithNullNameId"));
        assertThat(new ArrayList<>(entry2.getValue()), equalTo(images2));
        assertThat(iterator.hasNext(), equalTo(false));
    }

    @Test
    public void getVolumeSnapshotsReturnsVolumeSnapshotsIndexedByNameSortedByAge() {
        final VolumeSnapshot mockVolumeSnapshotWithNullName = mock(VolumeSnapshot.class);
        when(mockVolumeSnapshotWithNullName.getId()).thenReturn("mockVolumeSnapshotWithNullNameId");
        when(mockVolumeSnapshotWithNullName.getStatus()).thenReturn(Volume.Status.AVAILABLE);
        final VolumeSnapshot mockVolumeSnapshotNamedFoo = mock(VolumeSnapshot.class);
        when(mockVolumeSnapshotNamedFoo.getId()).thenReturn("mockVolumeSnapshotNamedFooId");
        when(mockVolumeSnapshotNamedFoo.getName()).thenReturn("Foo");
        when(mockVolumeSnapshotNamedFoo.getStatus()).thenReturn(Volume.Status.AVAILABLE);
        final VolumeSnapshot mockVolumeSnapshotNamedBar1 = mock(VolumeSnapshot.class);
        when(mockVolumeSnapshotNamedBar1.getId()).thenReturn("mockVolumeSnapshotNamedBar1Id");
        when(mockVolumeSnapshotNamedBar1.getName()).thenReturn("Bar");
        when(mockVolumeSnapshotNamedBar1.getCreated()).thenReturn(new Date(11111));
        when(mockVolumeSnapshotNamedBar1.getStatus()).thenReturn(Volume.Status.AVAILABLE);
        final VolumeSnapshot mockVolumeSnapshotNamedBar2 = mock(VolumeSnapshot.class);
        when(mockVolumeSnapshotNamedBar2.getId()).thenReturn("mockVolumeSnapshotNamedBar2Id");
        when(mockVolumeSnapshotNamedBar2.getName()).thenReturn("Bar");
        when(mockVolumeSnapshotNamedBar2.getCreated()).thenReturn(new Date(10000));
        when(mockVolumeSnapshotNamedBar2.getStatus()).thenReturn(Volume.Status.AVAILABLE);
        final VolumeSnapshot mockVolumeSnapshotNamedBar3 = mock(VolumeSnapshot.class);
        when(mockVolumeSnapshotNamedBar3.getId()).thenReturn("mockVolumeSnapshotNamedBar3Id");
        when(mockVolumeSnapshotNamedBar3.getName()).thenReturn("Bar");
        when(mockVolumeSnapshotNamedBar3.getCreated()).thenReturn(new Date(11110));
        when(mockVolumeSnapshotNamedBar3.getStatus()).thenReturn(Volume.Status.AVAILABLE);
        final VolumeSnapshot mockVolumeSnapshotUnavailable = mock(VolumeSnapshot.class);
        when(mockVolumeSnapshotUnavailable.getId()).thenReturn("mockVolumeSnapshotUnavailable");
        when(mockVolumeSnapshotUnavailable.getName()).thenReturn("ShouldNotBeInResult");
        when(mockVolumeSnapshotUnavailable.getCreated()).thenReturn(new Date(123));
        when(mockVolumeSnapshotUnavailable.getStatus()).thenReturn(Volume.Status.ATTACHING);
        final List volumeSnapshots = Arrays.asList(mockVolumeSnapshotNamedBar1, mockVolumeSnapshotWithNullName,
                mockVolumeSnapshotNamedBar2, mockVolumeSnapshotNamedFoo, mockVolumeSnapshotNamedBar3, mockVolumeSnapshotUnavailable);
        final OSClient mockClient = mock(OSClient.class, RETURNS_DEEP_STUBS);
        when(mockClient.blockStorage().snapshots().list()).thenReturn(volumeSnapshots);
        final Collection<VolumeSnapshot> volumeSnapshots0 = new ArrayList<>(
                Arrays.asList(mockVolumeSnapshotNamedBar2, mockVolumeSnapshotNamedBar3, mockVolumeSnapshotNamedBar1));
        final Collection<VolumeSnapshot> volumeSnapshots1 = new ArrayList<>(Arrays.asList(mockVolumeSnapshotNamedFoo));
        final Collection<VolumeSnapshot> volumeSnapshots2 = new ArrayList<>(
                Arrays.asList(mockVolumeSnapshotWithNullName));

        final Openstack instance = new Openstack(mockClient);
        final Map<String, Collection<VolumeSnapshot>> actual = instance.getVolumeSnapshots();

        // Result keys should be in name order
        final Iterator<Map.Entry<String, Collection<VolumeSnapshot>>> iterator = actual.entrySet().iterator();
        final Map.Entry<String, Collection<VolumeSnapshot>> entry0 = iterator.next();
        assertThat(entry0.getKey(), equalTo("Bar"));
        assertThat(new ArrayList<>(entry0.getValue()), equalTo(volumeSnapshots0));
        final Map.Entry<String, Collection<VolumeSnapshot>> entry1 = iterator.next();
        assertThat(entry1.getKey(), equalTo("Foo"));
        assertThat(new ArrayList<>(entry1.getValue()), equalTo(volumeSnapshots1));
        final Map.Entry<String, Collection<VolumeSnapshot>> entry2 = iterator.next();
        assertThat(entry2.getKey(), equalTo("mockVolumeSnapshotWithNullNameId"));
        assertThat(new ArrayList<>(entry2.getValue()), equalTo(volumeSnapshots2));
        assertThat(iterator.hasNext(), equalTo(false));
    }

    @Test
    public void getImageIdsForGivenNameThenReturnsMatchingImageIdsSortedByAge() {
        final Image mockImageNamedBar1 = mock(Image.class);
        when(mockImageNamedBar1.getId()).thenReturn("mockImageNamedBar1Id");
        when(mockImageNamedBar1.getName()).thenReturn("Bar");
        when(mockImageNamedBar1.getUpdatedAt()).thenReturn(new Date(11111));
        when(mockImageNamedBar1.getCreatedAt()).thenReturn(new Date(1111));
        final Image mockImageNamedBar2 = mock(Image.class);
        when(mockImageNamedBar2.getId()).thenReturn("mockImageNamedBar2Id");
        when(mockImageNamedBar2.getName()).thenReturn("Bar");
        when(mockImageNamedBar2.getUpdatedAt()).thenReturn(new Date(10000));
        when(mockImageNamedBar2.getCreatedAt()).thenReturn(new Date(1111));
        final Image mockImageNamedBar3 = mock(Image.class);
        when(mockImageNamedBar3.getId()).thenReturn("mockImageNamedBar3Id");
        when(mockImageNamedBar3.getName()).thenReturn("Bar");
        when(mockImageNamedBar3.getUpdatedAt()).thenReturn(new Date(11111));
        when(mockImageNamedBar3.getCreatedAt()).thenReturn(new Date(1000));
        final ImageService mockIS = mock(ImageService.class);
        final List images = Arrays.asList(mockImageNamedBar1, mockImageNamedBar2, mockImageNamedBar3);
        when(mockIS.listAll(anyMapOf(String.class, String.class))).thenReturn(images);
        final ArrayList<String> expected = new ArrayList<>(
                Arrays.asList("mockImageNamedBar2Id", "mockImageNamedBar3Id", "mockImageNamedBar1Id"));
        final OSClient mockClient = mock(OSClient.class);
        when(mockClient.images()).thenReturn(mockIS);

        final Openstack instance = new Openstack(mockClient);
        final List<String> actual = instance.getImageIdsFor("Bar");

        final Map<String, String> expectedFilteringParams = new HashMap<>(2);
        expectedFilteringParams.put("name", "Bar");
        expectedFilteringParams.put("status", "active");
        verify(mockIS).listAll(argThat(equalTo(expectedFilteringParams)));
        verifyNoMoreInteractions(mockIS);
        assertThat(new ArrayList<>(actual), equalTo(expected));
    }

    @Test
    public void getImageIdsForGivenUnknownThenReturnsEmpty() {
        final ImageService mockIS = mock(ImageService.class);
        when(mockIS.listAll(anyMapOf(String.class, String.class))).thenReturn(Collections.EMPTY_LIST);
        final OSClient mockClient = mock(OSClient.class);
        when(mockClient.images()).thenReturn(mockIS);
        final ArrayList<String> expected = new ArrayList<>();

        final Openstack instance = new Openstack(mockClient);
        final List<String> actual = instance.getImageIdsFor("NameNotFound");

        final Map<String, String> expectedFilteringParams = new HashMap<>(2);
        expectedFilteringParams.put("name", "NameNotFound");
        expectedFilteringParams.put("status", "active");
        verify(mockIS).listAll(argThat(equalTo(expectedFilteringParams)));
        verifyNoMoreInteractions(mockIS);
        assertThat(new ArrayList<>(actual), equalTo(expected));
    }

    @Test
    public void getImageIdsForGivenIdOfActiveImageThenReturnsId() {
        final Image mockImageNamedFoo = mock(Image.class);
        final String imageId = "cfd083b4-2422-4c5f-bf61-d975709375ab";
        when(mockImageNamedFoo.getId()).thenReturn(imageId);
        when(mockImageNamedFoo.getName()).thenReturn("Foo");
        when(mockImageNamedFoo.getStatus()).thenReturn(Image.Status.ACTIVE);
        final ImageService mockIS = mock(ImageService.class);
        when(mockIS.listAll(anyMapOf(String.class, String.class))).thenReturn(Collections.EMPTY_LIST);
        when(mockIS.get(imageId)).thenReturn(mockImageNamedFoo);
        final OSClient mockClient = mock(OSClient.class);
        when(mockClient.images()).thenReturn(mockIS);
        final ArrayList<String> expected = new ArrayList<>(Arrays.asList(mockImageNamedFoo.getId()));

        final Openstack instance = new Openstack(mockClient);
        final List<String> actual = instance.getImageIdsFor(imageId);

        verify(mockIS).listAll(anyMapOf(String.class, String.class));
        verify(mockIS).get(imageId);
        verifyNoMoreInteractions(mockIS);
        assertThat(new ArrayList<>(actual), equalTo(expected));
    }

    @Test
    public void getVolumeSnapshotIdsForGivenNameThenReturnsMatchingVolumeSnapshotIdsSortedByAge() {
        final VolumeSnapshot mockVolumeSnapshotNamedFoo = mock(VolumeSnapshot.class);
        when(mockVolumeSnapshotNamedFoo.getId()).thenReturn("mockVolumeSnapshotNamedFooId");
        when(mockVolumeSnapshotNamedFoo.getName()).thenReturn("Foo");
        when(mockVolumeSnapshotNamedFoo.getStatus()).thenReturn(Volume.Status.AVAILABLE);
        final VolumeSnapshot mockVolumeSnapshotNamedBar1 = mock(VolumeSnapshot.class);
        when(mockVolumeSnapshotNamedBar1.getId()).thenReturn("mockVolumeSnapshotNamedBar1Id");
        when(mockVolumeSnapshotNamedBar1.getName()).thenReturn("Bar");
        when(mockVolumeSnapshotNamedBar1.getCreated()).thenReturn(new Date(11111));
        when(mockVolumeSnapshotNamedBar1.getStatus()).thenReturn(Volume.Status.AVAILABLE);
        final VolumeSnapshot mockVolumeSnapshotNamedBar2 = mock(VolumeSnapshot.class);
        when(mockVolumeSnapshotNamedBar2.getId()).thenReturn("mockVolumeSnapshotNamedBar2Id");
        when(mockVolumeSnapshotNamedBar2.getName()).thenReturn("Bar");
        when(mockVolumeSnapshotNamedBar2.getCreated()).thenReturn(new Date(10000));
        when(mockVolumeSnapshotNamedBar2.getStatus()).thenReturn(Volume.Status.AVAILABLE);
        final VolumeSnapshot mockVolumeSnapshotNamedBar3 = mock(VolumeSnapshot.class);
        when(mockVolumeSnapshotNamedBar3.getId()).thenReturn("mockVolumeSnapshotNamedBar3Id");
        when(mockVolumeSnapshotNamedBar3.getName()).thenReturn("Bar");
        when(mockVolumeSnapshotNamedBar3.getCreated()).thenReturn(new Date(11110));
        when(mockVolumeSnapshotNamedBar3.getStatus()).thenReturn(Volume.Status.AVAILABLE);
        final BlockVolumeSnapshotService mockBVSS = mock(BlockVolumeSnapshotService.class);
        final List volumeSnapshots = Arrays.asList(mockVolumeSnapshotNamedFoo, mockVolumeSnapshotNamedBar1,
                mockVolumeSnapshotNamedBar2, mockVolumeSnapshotNamedBar3);
        when(mockBVSS.list()).thenReturn(volumeSnapshots);
        final OSClient mockClient = mock(OSClient.class, RETURNS_DEEP_STUBS);
        when(mockClient.blockStorage().snapshots()).thenReturn(mockBVSS);
        final ArrayList<String> expected = new ArrayList<>(Arrays.asList("mockVolumeSnapshotNamedBar2Id",
                "mockVolumeSnapshotNamedBar3Id", "mockVolumeSnapshotNamedBar1Id"));

        final Openstack instance = new Openstack(mockClient);
        final List<String> actual = instance.getVolumeSnapshotIdsFor("Bar");

        final Map<String, String> expectedFilteringParams = new HashMap<>(2);
        expectedFilteringParams.put("name", "Bar");
        expectedFilteringParams.put("status", "active");
        verify(mockBVSS).list();
        verifyNoMoreInteractions(mockBVSS);
        assertThat(new ArrayList<>(actual), equalTo(expected));
    }

    @Test
    public void getVolumeSnapshotIdsForGivenUnknownThenReturnsEmpty() {
        final VolumeSnapshot mockVolumeSnapshotNamedFoo = mock(VolumeSnapshot.class);
        when(mockVolumeSnapshotNamedFoo.getId()).thenReturn("mockVolumeSnapshotNamedFooId");
        when(mockVolumeSnapshotNamedFoo.getName()).thenReturn("Foo");
        when(mockVolumeSnapshotNamedFoo.getStatus()).thenReturn(Volume.Status.AVAILABLE);
        final VolumeSnapshot mockVolumeSnapshotNamedBar1 = mock(VolumeSnapshot.class);
        when(mockVolumeSnapshotNamedBar1.getId()).thenReturn("mockVolumeSnapshotNamedBar1Id");
        when(mockVolumeSnapshotNamedBar1.getName()).thenReturn("Bar");
        when(mockVolumeSnapshotNamedBar1.getCreated()).thenReturn(new Date(11111));
        when(mockVolumeSnapshotNamedBar1.getStatus()).thenReturn(Volume.Status.AVAILABLE);
        final VolumeSnapshot mockVolumeSnapshotNamedBar2 = mock(VolumeSnapshot.class);
        when(mockVolumeSnapshotNamedBar2.getId()).thenReturn("mockVolumeSnapshotNamedBar2Id");
        when(mockVolumeSnapshotNamedBar2.getName()).thenReturn("Bar");
        when(mockVolumeSnapshotNamedBar2.getCreated()).thenReturn(new Date(10000));
        when(mockVolumeSnapshotNamedBar2.getStatus()).thenReturn(Volume.Status.AVAILABLE);
        final VolumeSnapshot mockVolumeSnapshotNamedBar3 = mock(VolumeSnapshot.class);
        when(mockVolumeSnapshotNamedBar3.getId()).thenReturn("mockVolumeSnapshotNamedBar3Id");
        when(mockVolumeSnapshotNamedBar3.getName()).thenReturn("Bar");
        when(mockVolumeSnapshotNamedBar3.getCreated()).thenReturn(new Date(11110));
        when(mockVolumeSnapshotNamedBar3.getStatus()).thenReturn(Volume.Status.AVAILABLE);
        final BlockVolumeSnapshotService mockBVSS = mock(BlockVolumeSnapshotService.class);
        final List volumeSnapshots = Arrays.asList(mockVolumeSnapshotNamedFoo, mockVolumeSnapshotNamedBar1,
                mockVolumeSnapshotNamedBar2, mockVolumeSnapshotNamedBar3);
        when(mockBVSS.list()).thenReturn(volumeSnapshots);
        final OSClient mockClient = mock(OSClient.class, RETURNS_DEEP_STUBS);
        when(mockClient.blockStorage().snapshots()).thenReturn(mockBVSS);
        final ArrayList<String> expected = new ArrayList<>();

        final Openstack instance = new Openstack(mockClient);
        final List<String> actual = instance.getVolumeSnapshotIdsFor("NameNotFound");

        verify(mockBVSS).list();
        verifyNoMoreInteractions(mockBVSS);
        assertThat(new ArrayList<>(actual), equalTo(expected));
    }

    @Test
    public void getVolumeSnapshotIdsForGivenIdOfActiveVolumeSnapshotThenReturnsId() {
        final VolumeSnapshot mockVolumeSnapshotNamedFoo = mock(VolumeSnapshot.class);
        final String volumeSnapshotId = "cfd083b4-2422-4c5f-bf61-d975709375ab";
        when(mockVolumeSnapshotNamedFoo.getId()).thenReturn(volumeSnapshotId);
        when(mockVolumeSnapshotNamedFoo.getName()).thenReturn("Foo");
        when(mockVolumeSnapshotNamedFoo.getStatus()).thenReturn(Volume.Status.AVAILABLE);
        final VolumeSnapshot mockVolumeSnapshotNamedBar1 = mock(VolumeSnapshot.class);
        when(mockVolumeSnapshotNamedBar1.getId()).thenReturn("mockVolumeSnapshotNamedBar1Id");
        when(mockVolumeSnapshotNamedBar1.getName()).thenReturn("Bar");
        when(mockVolumeSnapshotNamedBar1.getCreated()).thenReturn(new Date(11111));
        when(mockVolumeSnapshotNamedBar1.getStatus()).thenReturn(Volume.Status.AVAILABLE);
        final VolumeSnapshot mockVolumeSnapshotNamedBar2 = mock(VolumeSnapshot.class);
        when(mockVolumeSnapshotNamedBar2.getId()).thenReturn("mockVolumeSnapshotNamedBar2Id");
        when(mockVolumeSnapshotNamedBar2.getName()).thenReturn("Bar");
        when(mockVolumeSnapshotNamedBar2.getCreated()).thenReturn(new Date(10000));
        when(mockVolumeSnapshotNamedBar2.getStatus()).thenReturn(Volume.Status.AVAILABLE);
        final VolumeSnapshot mockVolumeSnapshotNamedBar3 = mock(VolumeSnapshot.class);
        when(mockVolumeSnapshotNamedBar3.getId()).thenReturn("mockVolumeSnapshotNamedBar3Id");
        when(mockVolumeSnapshotNamedBar3.getName()).thenReturn("Bar");
        when(mockVolumeSnapshotNamedBar3.getCreated()).thenReturn(new Date(11110));
        when(mockVolumeSnapshotNamedBar3.getStatus()).thenReturn(Volume.Status.AVAILABLE);
        final List volumeSnapshots = Arrays.asList(mockVolumeSnapshotNamedFoo, mockVolumeSnapshotNamedBar1,
                mockVolumeSnapshotNamedBar2, mockVolumeSnapshotNamedBar3);
        final BlockVolumeSnapshotService mockBVSS = mock(BlockVolumeSnapshotService.class);
        when(mockBVSS.list()).thenReturn(volumeSnapshots);
        when(mockBVSS.get(volumeSnapshotId)).thenReturn(mockVolumeSnapshotNamedFoo);
        final OSClient mockClient = mock(OSClient.class, RETURNS_DEEP_STUBS);
        when(mockClient.blockStorage().snapshots()).thenReturn(mockBVSS);
        final ArrayList<String> expected = new ArrayList<>(Arrays.asList(mockVolumeSnapshotNamedFoo.getId()));

        final Openstack instance = new Openstack(mockClient);
        final List<String> actual = instance.getVolumeSnapshotIdsFor(volumeSnapshotId);

        verify(mockBVSS).list();
        verify(mockBVSS).get(volumeSnapshotId);
        verifyNoMoreInteractions(mockBVSS);
        assertThat(new ArrayList<>(actual), equalTo(expected));
    }

    @Test
    public void getAvailabilityZonesReturnsAZsSortedByName() {
        final AvailabilityZone mockAZ1 = mock(AvailabilityZone.class);
        when(mockAZ1.getZoneName()).thenReturn("Foo");
        final AvailabilityZone mockAZ2 = mock(AvailabilityZone.class);
        when(mockAZ2.getZoneName()).thenReturn("Bar");
        final AvailabilityZone mockAZ3 = mock(AvailabilityZone.class);
        when(mockAZ3.getZoneName()).thenReturn("Flibble");
        final ZoneService mockZS = mock(ZoneService.class);
        doReturn(Arrays.asList(mockAZ1, mockAZ2, mockAZ3)).when(mockZS).list();
        final OSClient mockClient = mock(OSClient.class, RETURNS_DEEP_STUBS);
        when(mockClient.compute().zones()).thenReturn(mockZS);
        final ArrayList<AvailabilityZone> expected = new ArrayList<>(Arrays.asList(mockAZ2, mockAZ3, mockAZ1));

        final Openstack instance = new Openstack(mockClient);
        final List<? extends AvailabilityZone> actual = instance.getAvailabilityZones();

        assertThat(new ArrayList<>(actual), equalTo(expected));
    }

    public static final ClientResponseException CLIENT_RESPONSE_FIP_DISALLOWED = new ClientResponseException(
            "Policy doesn't allow compute_extension:floating_ip_pools to be performed",
            403
    );

    @Test
    public void deleteAfterFailedBoot() {
        Openstack os = mock(Openstack.class, CALLS_REAL_METHODS);
        Server server = mock(Server.class);
        when(server.getStatus()).thenReturn(Server.Status.ERROR);
        when(server.getVmState()).thenReturn(null);

        Fault fault = mock(Fault.class);
        when(fault.getCode()).thenReturn(42);
        when(fault.getMessage()).thenReturn("It is broken!");
        when(fault.getDetails()).thenReturn("I told you once");
        when(server.getFault()).thenReturn(fault);

        doReturn(server).when(os)._bootAndWaitActive(any(ServerCreateBuilder.class), any(Integer.class));
        doThrow(new Openstack.ActionFailed("Fake deletion failure")).when(os).destroyServer(server);

        try {
            os.bootAndWaitActive(mock(ServerCreateBuilder.class), 1);
            fail();
        } catch (Openstack.ActionFailed ex) {
            assertThat(ex.getMessage(), containsString("status=ERROR vmState=null fault=42: It is broken! (I told you once)"));
            assertThat(ex.getSuppressed()[0].getMessage(), containsString("Fake deletion failure"));
        }

        verify(os, times(1)).destroyServer(server);
    }

    @Test
    public void deleteFloatingIpsWhenDeletingMachine() {
        Server server = mock(Server.class);
        when(server.getId()).thenReturn("instance-id");

        DeleteMachineSequencer sequencer = new DeleteMachineSequencer(server);

        // Instance exists at first then disappears after delete
        OSClient client = mock(OSClient.class, RETURNS_DEEP_STUBS);
        when(client.compute().servers().get(server.getId())).thenAnswer(sequencer.getServer());

        when(client.compute().servers().delete(server.getId())).thenAnswer(sequencer.deleteServer());

        ComputeFloatingIPService fips = client.compute().floatingIps();
        when(fips.list()).thenAnswer(sequencer.getAllFips());
        when(fips.deallocateIP(any(String.class))).thenAnswer(sequencer.deleteFip());

        Openstack os = new Openstack(client);
        os.destroyServer(server);

        verify(client.compute().servers()).delete(server.getId());
        verify(fips).deallocateIP("release-me");
        verify(fips, never()).deallocateIP("keep-me");
    }

    @Test @Issue("https://github.com/jenkinsci/openstack-cloud-plugin/issues/128")
    public void doNotFailPopulatingFipPools() throws Exception {
        OSClient client = mock(OSClient.class, RETURNS_DEEP_STUBS);
        when(client.compute().floatingIps()).thenThrow(CLIENT_RESPONSE_FIP_DISALLOWED);

        Openstack os = new Openstack(client);
        assertThat(os.getSortedIpPools(), Matchers.emptyIterable());
    }

    @Test @Issue("https://github.com/jenkinsci/openstack-cloud-plugin/issues/128")
    public void allocatingFipShouldFailIfFipsDisallowed() throws Exception {
        OSClient client = mock(OSClient.class, RETURNS_DEEP_STUBS);
        when(client.compute().floatingIps()).thenThrow(CLIENT_RESPONSE_FIP_DISALLOWED);

        Server server = mock(Server.class);
        when(server.getId()).thenReturn("instance-id");

        Openstack os = new Openstack(client);
        try {
            os.assignFloatingIp(server, "A1");
            fail();
        } catch (ClientResponseException ex) {
            assertThat(ex.getStatus(), equalTo(403));
        }
    }

    /**
     * Track the state of the openstack to be manifested by different client calls;
     */
    private class DeleteMachineSequencer {
        private volatile Server server;
        private final List<FloatingIP> fips = new ArrayList<>(Arrays.asList(
                NovaFloatingIP.builder().id("keep-me").instanceId("someone-elses").floatingIpAddress("0.0.0.0").build(),
                NovaFloatingIP.builder().id("release-me").instanceId("instance-id").floatingIpAddress("1.1.1.1").build()
        ));
        private ActionResponse success = ActionResponse.actionSuccess();
        private ActionResponse failure = ActionResponse.actionFailed("fake", 500);

        private DeleteMachineSequencer(Server server) {
            this.server = server;
        }

        private Answer<Server> getServer() {
            return new Answer<Server>() {
                @Override public Server answer(InvocationOnMock invocation) throws Throwable {
                    return server;
                }
            };
        }

        private Answer<ActionResponse> deleteServer() {
            return new Answer<ActionResponse>() {
                @Override public ActionResponse answer(InvocationOnMock invocation) throws Throwable {
                    server = null;
                    return success;
                }
            };
        }

        private Answer<List<FloatingIP>> getAllFips() {
            return new Answer<List<FloatingIP>>() {
                @Override public List<FloatingIP> answer(InvocationOnMock invocation) throws Throwable {
                    return new ArrayList<>(fips); // Defensive copy so deleteFip can modify this
                }
            };
        }

        private Answer<ActionResponse> deleteFip() {
            return new Answer<ActionResponse>() {
                @Override public ActionResponse answer(InvocationOnMock invocation) throws Throwable {
                    String id = (String) invocation.getArguments()[0];
                    Iterator<FloatingIP> iter = fips.iterator();
                    while (iter.hasNext()) {
                        FloatingIP fip = iter.next();
                        if (fip.getId().equals(id)) {
                            iter.remove();
                            return success;
                        }
                    }
                    return failure;
                }
            };
        }
    }
}
