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

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;

import com.google.common.base.Objects;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.ExtensionPoint;
import hudson.Util;
import hudson.remoting.Which;
import hudson.util.FormValidation;
import org.apache.commons.lang.ObjectUtils;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.openstack4j.api.OSClient;
import org.openstack4j.api.client.IOSClientBuilder;
import org.openstack4j.api.compute.ComputeFloatingIPService;
import org.openstack4j.api.compute.ServerService;
import org.openstack4j.api.exceptions.ResponseException;
import org.openstack4j.model.common.ActionResponse;
import org.openstack4j.model.common.BasicResource;
import org.openstack4j.model.common.Identifier;
import org.openstack4j.model.compute.Address;
import org.openstack4j.model.compute.Fault;
import org.openstack4j.model.compute.Flavor;
import org.openstack4j.model.compute.FloatingIP;
import org.openstack4j.model.compute.Keypair;
import org.openstack4j.model.compute.Server;
import org.openstack4j.model.compute.builder.ServerCreateBuilder;
import org.openstack4j.model.image.Image;
import org.openstack4j.model.network.NetFloatingIP;
import org.openstack4j.model.network.Network;
import org.openstack4j.openstack.OSFactory;

import hudson.util.Secret;
import jenkins.model.Jenkins;

/**
 * Encapsulate {@link OSClient}.
 *
 * It is needed to make sure the client is truly immutable and provide easy-to-mock abstraction for unittesting
 *
 * For server manipulation, this implementation provides metadata fingerprinting
 * to identify machines started via this plugin from given instance so it will not
 * manipulate servers it does not "own". In other words, pretends that there are no
 * other machines running in connected tenant except for those started using this class.
 *
 * @author ogondza
 */
@Restricted(NoExternalUse.class)
public class Openstack {

    private static final Logger LOGGER = Logger.getLogger(Openstack.class.getName());
    public static final String FINGERPRINT_KEY = "jenkins-instance";

    private final OSClient client;

    public Openstack(@Nonnull String endPointUrl, @Nonnull String identity, @Nonnull Secret credential, @CheckForNull String region) {
        // TODO refactor to split tenant:username everywhere including UI
        String[] id = identity.split(":", 3);
        String tenant = id.length > 0 ? id[0] : "";
        String username = id.length > 1 ? id[1] : "";
        String domain = id.length > 2 ? id[2] : "";
        final IOSClientBuilder<? extends OSClient, ?> builder;
        if (domain.equals("")) {
            //If domain is empty it is assumed that is being used API V2
            builder = OSFactory.builderV2().endpoint(endPointUrl)
                     .credentials(username, credential.getPlainText())
                     .tenantName(tenant);
        } else {
            //If not it is assumed that it is being used API V3
            Identifier iDomain = Identifier.byName(domain);
            Identifier project = Identifier.byName(tenant);
            builder = OSFactory.builderV3().endpoint(endPointUrl)
                     .credentials(username, credential.getPlainText(), iDomain)
                     .scopeToProject(project, iDomain);
        }
        client = builder
                .authenticate()
                .useRegion(region)
        ;
        debug("Openstack client created for " + endPointUrl);
    }

    /*exposed for testing*/
    public Openstack(@Nonnull OSClient client) {
        this.client = client;
    }

    public @Nonnull Collection<? extends Network> getSortedNetworks() {
        List<? extends Network> nets = client.networking().network().list();
        Collections.sort(nets, RESOURCE_COMPARATOR);
        return nets;
    }

    public @Nonnull Collection<Image> getSortedImages() {
        List<? extends Image> images = client.images().listAll();
        TreeSet<Image> set = new TreeSet<>(RESOURCE_COMPARATOR); // Eliminate duplicate names
        set.addAll(images);
        return set;
    }

    private static final Comparator<BasicResource> RESOURCE_COMPARATOR = new Comparator<BasicResource>() {
        @Override
        public int compare(BasicResource o1, BasicResource o2) {
            return ObjectUtils.compare(o1.getName(), o2.getName());
        }
    };

    public @Nonnull Collection<? extends Flavor> getSortedFlavors() {
        List<? extends Flavor> flavors = client.compute().flavors().list();
        Collections.sort(flavors, FLAVOR_COMPARATOR);
        return flavors;
    }

    private Comparator<Flavor> FLAVOR_COMPARATOR = new Comparator<Flavor>() {
        @Override
        public int compare(Flavor o1, Flavor o2) {
            return ObjectUtils.compare(o1.getName(), o2.getName());
        }
    };

    public @Nonnull List<String> getSortedIpPools() {
        List<String> names = new ArrayList<>(client.compute().floatingIps().getPoolNames());
        Collections.sort(names);
        return names;
    }

    public @Nonnull List<Server> getRunningNodes() {
        List<Server> running = new ArrayList<>();

        // We need details to inspect state and metadata
        final boolean detailed = true;
        for (Server n: client.compute().servers().list(detailed)) {
            if (isOccupied(n) && isOurs(n)) {
                running.add(n);
            }
        }

        return running;
    }

    public List<String> getFreeFipIds() {
        ArrayList<String> free = new ArrayList<>();
        for (NetFloatingIP ip : client.networking().floatingip().list()) {
            if (ip.getFixedIpAddress() == null) {
                free.add(ip.getId());
            }
        }
        return free;
    }

    public @Nonnull List<String> getSortedKeyPairNames() {
        List<String> keyPairs = new ArrayList<>();
        for (Keypair kp : client.compute().keypairs().list()) {
            keyPairs.add(kp.getName());
        }
        return keyPairs;
    }

    public @CheckForNull String getImageIdFor(String name) {
        Map<String, String> query = new HashMap<>(2);
        query.put("name", "eq:" + name); // prefix with eq: to prevent issues with names containing colons
        query.put("status", "active");

        List<? extends Image> images = client.images().listAll(query);
        if (images.size() > 0) {
            // Pick one at random to point out failures ASAP
            return images.get(new Random().nextInt(images.size())).getId();
        }

        if (name.matches("[0-1a-f-]{36}")) return name;

        return null;
    }

    /**
     * Determine whether the server is considered occupied by openstack plugin.
     */
    private static boolean isOccupied(@Nonnull Server server) {
        switch (server.getStatus()) {
            case UNKNOWN:
            case MIGRATING:
            case SHUTOFF:
            case DELETED:
                return false;
            case UNRECOGNIZED: // needs to be considered occupied not to leak a machine
                LOGGER.log(Level.WARNING, "Machine state not recognized by openstack4j, report this as a bug: " + server);
                return true;
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
        return Jenkins.getActiveInstance().getRootUrl();
    }

    public @Nonnull Server getServerById(@Nonnull String id) throws NoSuchElementException {
        Server server = client.compute().servers().get(id);
        if (server == null) throw new NoSuchElementException("No such server running: " + id);
        return server;
    }

    public @Nonnull List<Server> getServersByName(@Nonnull String name) {
        List<Server> ret = new ArrayList<>();
        for (Server server : client.compute().servers().list(Collections.singletonMap("name", name))) {
            if (isOurs(server)) {
                ret.add(server);
            }
        }
        return ret;
    }

    /**
     * Provision machine and wait until ready.
     *
     * @throws ActionFailed Openstack failed to provision the slave or it was in erroneous state (server will be deleted in such case).
     */
    public @Nonnull Server bootAndWaitActive(@Nonnull ServerCreateBuilder request, @Nonnegative int timeout) throws ActionFailed {
        debug("Booting machine");
        try {
            Server server = _bootAndWaitActive(request, timeout);
            if (server == null) {
                // Server failed to become ACTIVE in time. Find in what state it is, then.
                String name = request.build().getName();
                List<? extends Server> servers = getServersByName(name);

                String msg = "Failed to provision the server in time (" + timeout + "ms): " + servers.toString();

                ActionFailed err = new ActionFailed(msg);
                try {
                    // We do not have the id so can not be sure which one is our
                    int size = servers.size();
                    if (size == 1) {
                        // TODO async disposer
                        destroyServer(servers.get(0));
                    } else if (size > 1) {
                        LOGGER.warning("Unable to destroy server " + name + " as there is " + size + " of them");
                    }
                } catch (Throwable ex) {
                    err.addSuppressed(ex);
                }
                throw err;
            }
            debug("Machine started: " + server.getName());
            throwIfFailed(server);
            return server;
        } catch (ResponseException ex) {
            throw new ActionFailed(ex.getMessage(), ex);
        }
    }

    @Restricted(NoExternalUse.class) // Test hook
    public Server _bootAndWaitActive(@Nonnull ServerCreateBuilder request, @Nonnegative int timeout) {
        request.addMetadataItem(FINGERPRINT_KEY, instanceFingerprint());
        return client.compute().servers().bootAndWaitActive(request.build(), timeout);
    }

    /**
     * Fetch updated info about the server.
     */
    public @Nonnull Server updateInfo(@Nonnull Server server) {
        return getServerById(server.getId());
    }

    /**
     * Delete server eagerly.
     *
     * The deletion tends to fail a couple of time before it succeeds. This method throws on any such failure. Use
     * {@link DestroyMachine} to destroy the server reliably.
     */
    public void destroyServer(@Nonnull Server server) throws ActionFailed {
        ComputeFloatingIPService fipsService = client.compute().floatingIps();
        ServerService servers = client.compute().servers();
        String nodeId = server.getId();

        for (FloatingIP ip: fipsService.list()) {
            if (nodeId.equals(ip.getInstanceId())) {
                ActionResponse res = fipsService.deallocateIP(ip.getId());
                if (res.isSuccess()) {
                    debug("Deallocated Floating IP " + ip.getFloatingIpAddress());
                } else {
                    throw new ActionFailed(
                            "Floating IP deallocation failed for " + ip.getFloatingIpAddress() + ": " + res.getFault()
                    );
                }
            }
        }

        server = servers.get(nodeId);
        if (server == null || server.getStatus() == Server.Status.DELETED) {
            debug("Machine destroyed: " + nodeId);
            return; // Deleted
        }

        ActionResponse res = servers.delete(nodeId);
        if (res.getCode() == 404) {
            debug("Machine destroyed: " + nodeId);
            return; // Deleted
        }

        throwIfFailed(res);
    }

    /**
     * Assign floating ip address to the server.
     *
     * Note that after the successful assignment, the Server instance becomes outdated as it does not contain the IP details.
     *
     * @param server Server to assign FIP
     * @param poolName Name of the FIP pool to use. If null, openstack default pool will be used.
     */
    public @Nonnull FloatingIP assignFloatingIp(@Nonnull Server server, @CheckForNull String poolName) throws ActionFailed {
        debug("Allocating floating IP for " + server.getName());
        ComputeFloatingIPService fips = client.compute().floatingIps();
        FloatingIP ip;
        try {
            ip = fips.allocateIP(poolName);
        } catch (ResponseException ex) {
            // TODO Grab some still IPs from JCloudsCleanupThread
            throw new ActionFailed(ex.getMessage() + " Allocating for " + server.getName(), ex);
        }
        debug("Floating IP allocated " + ip.getFloatingIpAddress());
        try {
            debug("Assigning floating IP to " + server.getName());
            ActionResponse res = fips.addFloatingIP(server, ip.getFloatingIpAddress());
            throwIfFailed(res);
            debug("Floating IP assigned");
        } catch (Throwable _ex) {
            ActionFailed ex = _ex instanceof ActionFailed
                    ? (ActionFailed) _ex
                    : new ActionFailed("Unable to assign floating IP for " + server.getName(), _ex)
            ;

            ActionResponse res = fips.deallocateIP(ip.getId());
            logIfFailed(res);
            throw ex;
        }

        return ip;
    }

    public void destroyFip(String fip) {
        ActionResponse delete = client.networking().floatingip().delete(fip);
        throwIfFailed(delete);
    }

    /**
     * Extract public address from server info.
     *
     * @return Floating IP, if there is none Fixed IP, null if there is none either.
     */
    public static @CheckForNull String getPublicAddress(@Nonnull Server server) {
        String fixed = null;
        for (List<? extends Address> addresses: server.getAddresses().getAddresses().values()) {
            for (Address addr: addresses) {
                if ("floating".equals(addr.getType())) {
                    return addr.getAddr();
                }

                fixed = addr.getAddr();
            }
        }

        // No floating IP found - use fixed
        return fixed;
    }

    /**
     * @return true if succeeded.
     */
    private static boolean logIfFailed(@Nonnull ActionResponse res) {
        if (res.isSuccess()) return true;
        LOGGER.log(Level.INFO, res.toString());
        return false;
    }

    private static void throwIfFailed(@Nonnull ActionResponse res) {
        if (res.isSuccess()) return;
        throw new ActionFailed(res.toString());
    }

    private void throwIfFailed(@Nonnull Server server) {
        Server.Status status = server.getStatus();
        if (status == Server.Status.ACTIVE) return; // Success

        StringBuilder sb = new StringBuilder();
        sb.append("Failed to boot server ").append(server.getName());
        if (status == Server.Status.BUILD) {
            sb.append(" in time:");
        } else {
            sb.append(":");
        }

        sb.append(" status=").append(status);
        sb.append(" vmState=").append(server.getVmState());
        Fault fault = server.getFault();
        String msg = fault == null
            ? "none"
            : String.format("%d: %s (%s)", fault.getCode(), fault.getMessage(), fault.getDetails())
        ;
        sb.append(" fault=").append(msg);

        // Destroy the server
        ActionFailed ex = new ActionFailed(sb.toString());
        try {
            // TODO async disposer
            destroyServer(server);
        } catch (ActionFailed suppressed) {
            ex.addSuppressed(suppressed);
        }
        LOGGER.log(Level.WARNING, "Machine provisioning failed: " + server, ex);
        throw ex;
    }

    /**
     * Perform some tests before calling the connection successfully established.
     */
    @SuppressFBWarnings("RV_RETURN_VALUE_IGNORED_NO_SIDE_EFFECT")
    public @CheckForNull Throwable sanityCheck() {
        // Try to talk to all endpoints the plugin rely on so we know they exist, are enabled, user have permission to
        // access them and JVM trusts their SSL cert.
        try {
            client.networking().network().get("");
            client.images().listMembers("");
            client.compute().listExtensions().size();
        } catch (Throwable ex) {
            return ex;
        }
        return null;
    }

    public static final class ActionFailed extends RuntimeException {
        public ActionFailed(String msg) {
            super(msg);
        }

        public ActionFailed(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    private static void debug(@Nonnull String msg, @Nonnull String... args) {
        LOGGER.log(Level.FINE, msg, args);
    }

    @Restricted(NoExternalUse.class) // Extension point just for testing
    public static abstract class FactoryEP implements ExtensionPoint {
        public abstract @Nonnull Openstack getOpenstack(
                @Nonnull String endPointUrl, @Nonnull String identity, @Nonnull String credential, @CheckForNull String region
        ) throws FormValidation;

        /**
         * Instantiate Openstack client.
         */
        public static @Nonnull Openstack get(
                @Nonnull String endPointUrl, @Nonnull String identity, @Nonnull String credential, @CheckForNull String region
        ) throws FormValidation {
            return ExtensionList.lookup(FactoryEP.class).get(0).getOpenstack(endPointUrl, identity, credential, region);
        }
    }

    @Extension
    public static final class Factory extends FactoryEP {
        public @Nonnull Openstack getOpenstack(String endPointUrl, String identity, String credential, @CheckForNull String region) throws FormValidation {
            endPointUrl = Util.fixEmptyAndTrim(endPointUrl);
            identity = Util.fixEmptyAndTrim(identity);
            credential = Util.fixEmptyAndTrim(credential);
            region = Util.fixEmptyAndTrim(region);

            if (endPointUrl == null) throw FormValidation.error("No endPoint specified");
            if (identity == null) throw FormValidation.error("No identity specified");
            if (credential == null) throw FormValidation.error("No credential specified");

            return new Openstack(endPointUrl, identity, Secret.fromString(credential), region);
        }
    }

    static {
        // Log where guava is coming from. This can not be reliably tested as jenkins-test-harness, hpi:run and actual
        // jenkins deployed plugin have different classloader environments. Messing around with maven-hpi-plugin opts can
        // fix or break any of that and there is no regression test to catch that.
        try {
            File path = Which.jarFile(Objects.ToStringHelper.class);
            LOGGER.info("com.google.common.base.Objects loaded from " + path);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Unable to get source of com.google.common.base.Objects", e);
        }
    }
}
