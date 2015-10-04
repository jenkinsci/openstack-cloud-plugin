/*
 * The MIT License
 *
 * Copyright (c) 2015 Red Hat, Inc.
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
package jenkins.plugins.openstack.compute.internal;

import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.openstack4j.api.OSClient;
import org.openstack4j.model.compute.Flavor;
import org.openstack4j.model.compute.ext.AvailabilityZone;
import org.openstack4j.model.image.Image;
import org.openstack4j.model.network.Network;
import org.openstack4j.openstack.OSFactory;

@Restricted(NoExternalUse.class)
public class Openstack {

    private final OSClient client;

    public Openstack(String endPointUrl, String identity, String credential) {
        // TODO refactor to split username:tenant everywhere including UI
        String[] id = identity.split(":", 2);
        String username = id.length > 0 ? id[0] : "";
        String tenant = id.length > 1 ? id[1] : "";
        client = OSFactory.builder().endpoint(endPointUrl)
                .credentials(username, credential)
                .tenantName(tenant)
                .authenticate()
        ;
    }

    public OSClient getClient() {
        return client;
    }

    public List<? extends Network> getSortedNetworks() {
        List<? extends Network> nets = client.networking().network().list();
        Collections.sort(nets, NETWORK_COMPARATOR);
        return nets;
    }

    private static final Comparator<Network> NETWORK_COMPARATOR = new Comparator<Network>() {
        @Override
        public int compare(Network o1, Network o2) {
            return o1.getName().compareTo(o2.getName());
        }
    };

    public List<? extends Image> getSortedImages() {
        List<? extends Image> images = client.images().listAll();
        Collections.sort(images, IMAGE_COMPARATOR);
        return images;
    }

    private static final Comparator<Image> IMAGE_COMPARATOR = new Comparator<Image>() {
        @Override
        public int compare(Image o1, Image o2) {
            return o1.getName().compareTo(o2.getName());
        }
    };

    public List<? extends Flavor> getSortedFlavors() {
        List<? extends Flavor> flavors = client.compute().flavors().list();
        Collections.sort(flavors, FLAVOR_COMPARATOR);
        return flavors;
    }

    private Comparator<Flavor> FLAVOR_COMPARATOR = new Comparator<Flavor>() {
        @Override
        public int compare(Flavor o1, Flavor o2) {
            return o1.getName().compareTo(o2.getName());
        }
    };

//    public List<? extends AvailabilityZone> getSortedRegions() {
//        // TODO sort
//        return client.compute().
//    }
}
