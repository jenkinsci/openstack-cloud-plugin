/*
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
package jenkins.plugins.openstack.compute.slaveopts;

import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.plugins.openstack.PluginTestRule;
import jenkins.plugins.openstack.compute.internal.Openstack;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.openstack4j.api.OSClient;
import org.openstack4j.api.image.v2.ImageService;
import org.openstack4j.model.image.v2.Image;
import org.openstack4j.model.storage.block.Volume;
import org.openstack4j.model.storage.block.VolumeSnapshot;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

public class BootSourceTest {
    private static final FormValidation VALIDATION_REQUIRED = FormValidation.error(hudson.util.Messages.FormValidation_ValidateRequired());

    public @Rule PluginTestRule j = new PluginTestRule();

    private BootSource.Image.Desc id;
    private BootSource.VolumeSnapshot.Desc vsd;

    @Before
    public void before() {
        id = (BootSource.Image.Desc) j.jenkins.getDescriptorOrDie(BootSource.Image.class);
        vsd = (BootSource.VolumeSnapshot.Desc) j.jenkins.getDescriptorOrDie(BootSource.VolumeSnapshot.class);
    }

    @Test
    public void constructorInvariants() {
        try {
            new BootSource.Image(null, null, false);
            fail();
        } catch (NullPointerException e) {
            assertThat(e.getMessage(), containsString("Image name missing"));
        }

        try {
            new BootSource.VolumeSnapshot(null, null, false);
            fail();
        } catch (NullPointerException e) {
            assertThat(e.getMessage(), containsString("Volume snapshot name missing"));
        }

        try {
            new BootSource.VolumeFromImage(null, 1, null, false);
            fail();
        } catch (NullPointerException e) {
            assertThat(e.getMessage(), containsString("Image name missing"));
        }

        try {
            new BootSource.VolumeFromImage("foo", 0, null, false);
            fail();
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage(), containsString("Volume size must be positive, got 0"));
        }
    }

    @Test
    public void doFillImageNameItemsPopulatesImageNamesNotIds() {
        Image image = mock(Image.class);
        when(image.getId()).thenReturn("image-id");
        final String imageName = "image-name";
        when(image.getName()).thenReturn(imageName);

        Openstack os = j.fakeOpenstackFactory();
        final String credentialsId = j.dummyCredentials();

        doReturn(Collections.singletonMap(imageName, Collections.singletonList(image))).when(os).getImages();

        ListBoxModel list = id.doFillNameItems("", "OSurl", false, credentialsId, "OSzone", null);
        assertEquals(2, list.size());
        assertEquals("First menu entry is 'nothing selected'", "", list.get(0).value);
        ListBoxModel.Option item = list.get(1);
        assertEquals("menu item name", imageName, item.name);
        assertEquals("menu item value", imageName, item.value);
    }

    @Test
    public void doFillImageNameItemsPopulatesImageNamesWithFilter() {
        Image image = mock(Image.class);
        when(image.getId()).thenReturn("image-id");
        final String imageName = "image-name";
        when(image.getName()).thenReturn(imageName);

        Image imageFiltered = mock(Image.class);
        when(imageFiltered.getId()).thenReturn("filtered-id");
        final String imageFilteredName = "filtered-name";
        when(imageFiltered.getName()).thenReturn(imageFilteredName);

        Openstack os = j.fakeOpenstackFactory();
        final String credentialsId = j.dummyCredentials();
        Map<String, List> images = new HashMap();
        images.put(imageName, Collections.singletonList(image));
        images.put(imageFilteredName, Collections.singletonList(imageFiltered));
        doReturn(images).when(os).getImages();

        ListBoxModel list = id.doFillNameItems("", "OSurl", false, credentialsId, "OSzone", "image-.*");
        assertEquals(2, list.size());
        assertEquals("First menu entry is 'nothing selected'", "", list.get(0).value);
        ListBoxModel.Option item = list.get(1);
        assertEquals("menu item name", imageName, item.name);
        assertEquals("menu item value", imageName, item.value);
    }

    @Test
    public void doFillSnapshotNameItemsPopulatesVolumeSnapshotNames() {
        VolumeSnapshot volumeSnapshot = mock(VolumeSnapshot.class);
        when(volumeSnapshot.getId()).thenReturn("vs-id");
        when(volumeSnapshot.getName()).thenReturn("vs-name");
        when(volumeSnapshot.getStatus()).thenReturn(Volume.Status.AVAILABLE);
        final String credentialsId = j.dummyCredentials();

        Openstack os = j.fakeOpenstackFactory();
        when(os.getVolumeSnapshots()).thenReturn(Collections.singletonMap("vs-name", Collections.singletonList(volumeSnapshot)));

        ListBoxModel list = vsd.doFillNameItems("existing-vs-name", "OSurl", false, credentialsId, "OSzone", null);
        assertEquals(3, list.size());
        assertEquals("First menu entry is 'nothing selected'", "", list.get(0).value);
        assertEquals("Second menu entry is the VS OpenStack can see", "vs-name", list.get(1).name);
        assertEquals("Second menu entry is the VS OpenStack can see", "vs-name", list.get(1).value);
        assertEquals("Third menu entry is the existing value", "existing-vs-name", list.get(2).name);
        assertEquals("Third menu entry is the existing value", "existing-vs-name", list.get(2).value);
    }

    @Test
    public void doFillSnapshotNameItemsPopulatesVolumeSnapshotNamesWithFilter() {
        VolumeSnapshot volumeSnapshot = mock(VolumeSnapshot.class);
        when(volumeSnapshot.getId()).thenReturn("vs-id");
        when(volumeSnapshot.getName()).thenReturn("vs-name");
        when(volumeSnapshot.getStatus()).thenReturn(Volume.Status.AVAILABLE);

        VolumeSnapshot volumeSnapshotFiltered = mock(VolumeSnapshot.class);
        when(volumeSnapshotFiltered.getId()).thenReturn("vs-filtered-id");
        when(volumeSnapshotFiltered.getName()).thenReturn("vs-filtered-name");
        when(volumeSnapshotFiltered.getStatus()).thenReturn(Volume.Status.AVAILABLE);
        final String credentialsId = j.dummyCredentials();

        Openstack os = j.fakeOpenstackFactory();
        Map<String, List<VolumeSnapshot>> volumes = new HashMap();
        volumes.put("vs-name", Collections.singletonList(volumeSnapshot));
        volumes.put("vs-filtered-name", Collections.singletonList(volumeSnapshot));
        when(os.getVolumeSnapshots()).thenReturn(volumes);

        ListBoxModel list = vsd.doFillNameItems("existing-vs-name", "OSurl", false, credentialsId, "OSzone", "vs-filtered.*");
        assertEquals(3, list.size());
        assertEquals("First menu entry is 'nothing selected'", "", list.get(0).value);
        assertEquals("Second menu entry is the VS OpenStack can see", "vs-filtered-name", list.get(1).name);
        assertEquals("Second menu entry is the VS OpenStack can see", "vs-filtered-name", list.get(1).value);
        assertEquals("Third menu entry is the existing value", "existing-vs-name", list.get(2).name);
        assertEquals("Third menu entry is the existing value", "existing-vs-name", list.get(2).value);
    }

    @Test @Issue("JENKINS-29993")
    public void doFillImageIdItemsAcceptsNullAsImageName() {
        Image image = mock(Image.class);
        when(image.getId()).thenReturn("image-id");
        when(image.getName()).thenReturn(null);

        OSClient<?> osClient = mock(OSClient.class);
        ImageService imageService = mock(ImageService.class);
        when(osClient.imagesV2()).thenReturn(imageService);
        doReturn(Collections.singletonList(image)).when(imageService).list(any());

        j.fakeOpenstackFactory(new Openstack(osClient));
        final String credentialsId = j.dummyCredentials();

        ListBoxModel list = id.doFillNameItems("", "OSurl", false, credentialsId, "OSzone", null);
        assertThat(list.get(0).name, list, Matchers.iterableWithSize(2));
        assertEquals(2, list.size());
        ListBoxModel.Option item = list.get(1);
        assertEquals("image-id", item.name);
        assertEquals("image-id", item.value);

        verify(imageService).list(any());
        verifyNoMoreInteractions(imageService);
    }

    @Test
    public void doCheckImageIdWhenNoValueSet() throws Exception {
        final String urlC, urlT, zoneC, zoneT;
        urlC= urlT= zoneC= zoneT= "dummy";

        final String credentialsIdCloud = j.dummyCredentials();
        final String credentialsIdTemplate = j.dummyCredentials();

        final FormValidation actual = id.doCheckName("", urlC, urlT, false, false, credentialsIdCloud, credentialsIdTemplate, zoneC, zoneT);
        assertThat(actual, j.validateAs(VALIDATION_REQUIRED));
    }

    @Test
    public void doCheckImageIdWhenImageIsNotFoundInOpenstack() throws Exception {
        final String urlC, urlT,zoneC, zoneT;
        urlC= urlT= zoneC= zoneT= "dummy";
        final Openstack os = mock(Openstack.class);
        final List<String> noIDs = Collections.emptyList();
        final String credentialsIdCloud = j.dummyCredentials();
        final String credentialsIdTemplate = j.dummyCredentials();
        when(os.getImageIdsFor("imageNotFound")).thenReturn(noIDs);
        j.fakeOpenstackFactory(os);
        final FormValidation expected = FormValidation.error("Not found");

        final FormValidation actual = id.doCheckName("imageNotFound", urlC, urlT, false, false, credentialsIdCloud, credentialsIdTemplate, zoneC, zoneT);
        assertThat(actual, j.validateAs(expected));
    }

    @Test
    public void doCheckImageIdWhenOneImageIsFound() throws Exception {
        final String urlC, urlT, zoneC, zoneT;
        urlC= urlT= zoneC= zoneT= "dummy";
        final Openstack os = mock(Openstack.class);
        final String credentialsIdCloud = j.dummyCredentials();
        final String credentialsIdTemplate = j.dummyCredentials();
        when(os.getImageIdsFor("imageFound")).thenReturn(Collections.singletonList("imageFoundId"));
        j.fakeOpenstackFactory(os);
        final FormValidation expected = FormValidation.ok();

        final FormValidation actual = id.doCheckName("imageFound", urlC, urlT, false, false, credentialsIdCloud, credentialsIdTemplate, zoneC, zoneT);
        assertThat(actual, j.validateAs(expected));
    }

    @Test
    public void doCheckImageIdWhenMultipleImagesAreFoundForTheName() throws Exception {
        final String urlC, urlT, zoneC, zoneT;
        urlC= urlT= zoneC= zoneT= "dummy";
        final Openstack os = mock(Openstack.class);
        final String credentialsIdCloud = j.dummyCredentials();
        final String credentialsIdTemplate = j.dummyCredentials();
        when(os.getImageIdsFor("imageAmbiguous")).thenReturn(Arrays.asList("imageAmbiguousId1", "imageAmbiguousId2"));
        j.fakeOpenstackFactory(os);
        final FormValidation expected = FormValidation.warning("Multiple matching results");

        final FormValidation actual = id.doCheckName("imageAmbiguous", urlC, urlT, false, false, credentialsIdCloud, credentialsIdTemplate, zoneC, zoneT);
        assertThat("imageAmbiguous", actual, j.validateAs(expected));
    }

    @Test
    public void doCheckImageIdWhenOneVolumeSnapshotIsFound() throws Exception {
        final String urlC, urlT, zoneC, zoneT;
        urlC= urlT= zoneC= zoneT= "dummy";
        final Openstack os = mock(Openstack.class);
        final String credentialsIdCloud = j.dummyCredentials();
        final String credentialsIdTemplate = j.dummyCredentials();
        when(os.getVolumeSnapshotIdsFor("vsFound")).thenReturn(Collections.singletonList("vsFoundId"));
        j.fakeOpenstackFactory(os);
        final FormValidation expected = FormValidation.ok();

        final FormValidation actual = vsd.doCheckName("vsFound", urlC, urlT, false, false, credentialsIdCloud, credentialsIdTemplate, zoneC, zoneT);
        assertThat("vsFound", actual, j.validateAs(expected));
    }
}
