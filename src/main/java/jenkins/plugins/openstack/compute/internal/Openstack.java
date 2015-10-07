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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.logging.Logger;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;

import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.openstack4j.api.Builders;
import org.openstack4j.api.OSClient;
import org.openstack4j.api.compute.ComputeFloatingIPService;
import org.openstack4j.model.compute.ActionResponse;
import org.openstack4j.model.compute.Flavor;
import org.openstack4j.model.compute.FloatingIP;
import org.openstack4j.model.compute.Server;
import org.openstack4j.model.compute.ServerCreate;
import org.openstack4j.model.compute.builder.ServerCreateBuilder;
import org.openstack4j.model.image.Image;
import org.openstack4j.model.network.Network;
import org.openstack4j.openstack.OSFactory;

import hudson.util.Secret;
import jenkins.model.Jenkins;

/**
 * Encapsulate {@link OSClient}.
 *
 * The is to make sure the client is truly immutable and provide easy-to-mock abstraction for unittesting.
 *
 * For server manipulation, this implementation provides metadata fingerprinting
 * to identify machines started via this plugin from given instance so it will not
 * manipulate servers it does not "own". In other words, pretends that there are no
 * other machines running in connected tenant.
 *
 * @author ogondza
 */
@Restricted(NoExternalUse.class)
public class Openstack {

    private static final Logger LOGGER = Logger.getLogger(Openstack.class.getName());
    private static final String FINGERPRINT_KEY = "openstack-cloud-instance";

    private final OSClient client;

    public Openstack(@Nonnull String endPointUrl, @Nonnull String identity, @Nonnull Secret credential, @CheckForNull String region) {
        // TODO refactor to split username:tenant everywhere including UI
        String[] id = identity.split(":", 2);
        String username = id.length > 0 ? id[0] : "";
        String tenant = id.length > 1 ? id[1] : "";
        client = OSFactory.builder().endpoint(endPointUrl)
                .credentials(username, credential.getPlainText())
                .tenantName(tenant)
                .authenticate()
                .useRegion(region)
        ;
    }

    public @Nonnull List<? extends Network> getSortedNetworks() {
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

    public @Nonnull List<? extends Image> getSortedImages() {
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

    public @Nonnull List<? extends Flavor> getSortedFlavors() {
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

    public @Nonnull List<? extends Server> getRunningNodes() {
        List<Server> running = new ArrayList<Server>();

        // We need details to inspect state and metadata
        boolean detailed = true;
        for (Server n: client.compute().servers().list(detailed)) {
            if (isRunning(n) && isOurs(n)) {
                running.add(n);
            }
        }

        return running;
    }

    /**
     * Determine whether the server is considered running for the purposes of provisioning.
     */
    private boolean isRunning(@Nonnull Server server) {
        switch (server.getStatus()) {
            case UNRECOGNIZED:
            case UNKNOWN:
            case MIGRATING:
            case SHUTOFF:
            case DELETED:
                return false;
            default:
                return true;
        }
    }

    private boolean isOurs(@Nonnull Server server) {
        return instanceFingerprint().equals(server.getMetadata().get(FINGERPRINT_KEY));
    }

    /**
     * Identification for instances launched by this instance via this plugin.
     *
     * @return Identifier to filter instances we control.
     */
    private @Nonnull String instanceFingerprint() {
        return Jenkins.getInstance().getLegacyInstanceId();
    }

    public @Nonnull Server getServerById(@Nonnull String id) throws NoSuchElementException {
        Server server = client.compute().servers().get(id);
        if (server == null) throw new NoSuchElementException("No such server running: " + id);
        return server;
    }

    public @Nonnull Server bootAndWaitActive(@Nonnull ServerCreateBuilder request, @Nonnegative int timeout) {
        request.addMetadataItem(FINGERPRINT_KEY, instanceFingerprint());
        return client.compute().servers().bootAndWaitActive(request.build(), timeout);
    }

    public static @Nonnull String getDetails(@Nonnull Server server) {
        return new StringBuilder("Server name=").append(server.getName())
                .append(", id=").append(server.getId())
                .append(", status=").append(server.getStatus())
                .toString()
        ;
    }

    public void destroyServer(@Nonnull Server server) {
        // Do not checking fingerprint here presuming all Servers provided by
        // this implementation are ours.
        ActionResponse res = client.compute().servers().delete(server.getId());
        ActionFailed.throwIfFailed(res);

        // Remove all associated floating IPS
        ComputeFloatingIPService fips = client.compute().floatingIps();
        for (FloatingIP ip: fips.list()) {
            if (server.getId().equals(ip.getInstanceId())) {
                fips.removeFloatingIP(server, ip.getFloatingIpAddress());
                fips.deallocateIP(ip.getId());
            }
        }
    }

    public static final class ActionFailed extends RuntimeException {
        public static void throwIfFailed(@Nonnull ActionResponse res) {
            if (res.isSuccess()) return;
            throw new ActionFailed(res);
        }

        public ActionFailed(ActionResponse res) {
            super(res.toString());
        }
    }

    public @Nonnull FloatingIP assignFloatingIp(@Nonnull Server server) {
        ComputeFloatingIPService fips = client.compute().floatingIps();
        String publicPool = null;
        FloatingIP ip = fips.allocateIP(publicPool);
        try {
            fips.addFloatingIP(server, ip.getFloatingIpAddress());
        } catch (Throwable ex) {
            fips.deallocateIP(ip.getId());
            throw ex;
        }

        return ip;
    }
}
