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
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;

import com.google.common.base.Objects;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.util.concurrent.UncheckedExecutionException;
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
import org.openstack4j.api.exceptions.ClientResponseException;
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
import org.openstack4j.model.identity.v2.Access;
import org.openstack4j.model.identity.v3.Token;
import org.openstack4j.model.image.Image;
import org.openstack4j.model.network.NetFloatingIP;
import org.openstack4j.model.network.Network;
import org.openstack4j.openstack.OSFactory;

import hudson.util.Secret;
import jenkins.model.Jenkins;

/**
 * Encapsulate {@link OSClient}.
 *
 * It is needed to make sure the client is truly immutable and provide easy-to-mock abstraction for unittesting.
 *
 * For server manipulation, this implementation provides metadata fingerprinting
 * to identify machines started via this plugin from given instance so it will not
 * manipulate servers it does not "own". In other words, pretends that there are no
 * other machines running in connected tenant except for those started using this class.
 *
 * @author ogondza
 */
@Restricted(NoExternalUse.class)
@ThreadSafe
public class Openstack {

    private static final Logger LOGGER = Logger.getLogger(Openstack.class.getName());
    public static final String FINGERPRINT_KEY = "jenkins-instance";

    // Store the OS session token so clients can be created from it per all threads using this.
    private final ClientProvider<?> clientProvider;

    // Used when calculating differences between our clock and the OpenStack endpoint's clock.
    private final long instanceCreationTimeMillis;

    private Openstack(@Nonnull String endPointUrl, @Nonnull String identity, @Nonnull Secret credential, @CheckForNull String region) {
        instanceCreationTimeMillis = System.currentTimeMillis();
        // TODO refactor to split tenant:username everywhere including UI
        String[] id = identity.split(":", 3);
        String tenant = id.length > 0 ? id[0] : "";
        String username = id.length > 1 ? id[1] : "";
        String domain = id.length > 2 ? id[2] : "";
        final IOSClientBuilder<? extends OSClient<?>, ?> builder;
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
        OSClient<?> client = builder
                .authenticate()
                .useRegion(region)
        ;

        clientProvider = ClientProvider.get(client);
        debug("Openstack client created for " + endPointUrl);
    }

    /*exposed for testing*/
    public Openstack(@Nonnull final OSClient<?> client) {
        instanceCreationTimeMillis = System.currentTimeMillis();
        this.clientProvider = new ClientProvider<Void>(null) {
            @Override public @Nonnull OSClient<?> get() {
                return client;
            }

            @Override public Date _getExpires() {
                return ClientProvider.get(client).getExpires();
            }

            @Override
            protected Date _getIssued() {
                return ClientProvider.get(client).getIssued();
            }
        };
    }

    /**
     * Date representing when this instance's login session is no longer valid.
     * <p>
     * <b>NOTE:</b> This is supplied by the OpenStack service endpoint so it may
     * be offset if the OpenStack endpoint's clock differs from our own.
     * </p>
     */
    public @Nonnull Date getExpires() {
        return clientProvider.getExpires();
    }

    /**
     * Date representing when this instance's login session was started, or null
     * if the authentication method cannot provide that data.
     * <p>
     * <b>NOTE:</b> This is supplied by the OpenStack service endpoint so it may
     * be offset if the OpenStack endpoint's clock differs from our own.
     */
    public @CheckForNull Date getIssued() {
        return clientProvider.getIssued();
    }

    /**
     * System clock (from {@link System#currentTimeMillis()}) representing when
     * this instance's login session was started according to our own clock.
     * <p>
     * <b>NOTE:</b> If the OpenStack service endpoint's clock is perfectly in
     * sync with ours and logging in takes no time at all then this will be
     * equal to {@link #getIssued()}.
     * </p>
     */
    public long getLoginTimeMillis() {
        return instanceCreationTimeMillis;
    }

    public @Nonnull Collection<? extends Network> getSortedNetworks() {
        List<? extends Network> nets = clientProvider.get().networking().network().list();
        Collections.sort(nets, RESOURCE_COMPARATOR);
        return nets;
    }

    public @Nonnull Collection<Image> getSortedImages() {
        List<? extends Image> images = clientProvider.get().images().listAll();
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
        List<? extends Flavor> flavors = clientProvider.get().compute().flavors().list();
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
        ComputeFloatingIPService ipService = getComputeFloatingIPService();
        if (ipService == null) return Collections.emptyList();

        List<String> names = new ArrayList<>(ipService.getPoolNames());
        Collections.sort(names);
        return names;
    }

    /**
     * @return null when user is not authorized to use the endpoint which is a valid use-case.
     */
    private @CheckForNull ComputeFloatingIPService getComputeFloatingIPService() {
        try {
            return clientProvider.get().compute().floatingIps();
        } catch (ClientResponseException ex) {
            // https://github.com/jenkinsci/openstack-cloud-plugin/issues/128
            if (ex.getStatus() == 403) return null;
            throw ex;
        }
    }

    public @Nonnull List<Server> getRunningNodes() {
        List<Server> running = new ArrayList<>();

        // We need details to inspect state and metadata
        final boolean detailed = true;
        for (Server n: clientProvider.get().compute().servers().list(detailed)) {
            if (isOccupied(n) && isOurs(n)) {
                running.add(n);
            }
        }

        return running;
    }

    public List<String> getFreeFipIds() {
        ArrayList<String> free = new ArrayList<>();
        for (NetFloatingIP ip : clientProvider.get().networking().floatingip().list()) {
            if (ip.getFixedIpAddress() == null) {
                free.add(ip.getId());
            }
        }
        return free;
    }

    public @Nonnull List<String> getSortedKeyPairNames() {
        List<String> keyPairs = new ArrayList<>();
        for (Keypair kp : clientProvider.get().compute().keypairs().list()) {
            keyPairs.add(kp.getName());
        }
        return keyPairs;
    }

    public @CheckForNull String getImageIdFor(String name) {
        Map<String, String> query = new HashMap<>(2);
        query.put("name", name);
        query.put("status", "active");

        List<? extends Image> images = clientProvider.get().images().listAll(query);
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
        Server server = clientProvider.get().compute().servers().get(id);
        if (server == null) throw new NoSuchElementException("No such server running: " + id);
        return server;
    }

    public @Nonnull List<Server> getServersByName(@Nonnull String name) {
        List<Server> ret = new ArrayList<>();
        for (Server server : clientProvider.get().compute().servers().list(Collections.singletonMap("name", name))) {
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

                String msg = "Failed to provision the " + name + " in time (" + timeout + "ms). Existing server(s): " + servers.toString();

                ActionFailed err = new ActionFailed(msg);
                try {
                    // We do not have the id so can not be sure which one is ours
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
        return clientProvider.get().compute().servers().bootAndWaitActive(request.build(), timeout);
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
        String nodeId = server.getId();

        ComputeFloatingIPService fipsService = getComputeFloatingIPService();
        if (fipsService != null) {
            for (FloatingIP ip : fipsService.list()) {
                if (nodeId.equals(ip.getInstanceId())) {
                    ActionResponse res = fipsService.deallocateIP(ip.getId());
                    if (res.isSuccess()) {
                        debug("Deallocated Floating IP " + ip.getFloatingIpAddress());
                    } else {
                        throw new ActionFailed(
                                "Floating IP deallocation failed for " + ip.getFloatingIpAddress() + ": " + res.getFault() + "(" + res.getCode()  + ")"
                        );
                    }
                }
            }
        }

        ServerService servers = clientProvider.get().compute().servers();
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
        ComputeFloatingIPService fips = clientProvider.get().compute().floatingIps(); // This throws when user is not authorized to manipulate FIPs
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
        ActionResponse delete = clientProvider.get().networking().floatingip().delete(fip);

        // Deleted by some other action. Being idempotent here and reporting success.
        if (delete.getCode() == 404) return;

        throwIfFailed(delete);
    }

    /**
     * Extract public address from server info.
     *
     * @return Floating IP, if there is none Fixed IP, null if there is none either.
     */
    public static @CheckForNull Address getPublicAddressObject(@Nonnull Server server) {
    	Address fixed = null;
        for (List<? extends Address> addresses: server.getAddresses().getAddresses().values()) {
            for (Address addr: addresses) {
                if ("floating".equals(addr.getType())) {
                    return addr;
                }

                fixed = addr;
            }
        }

        // No floating IP found - use fixed
        return fixed;
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
     * Extract public address from server info.
     *
     * @return Floating IP, if there is none Fixed IP, null if there is none either.
     */
    public static @CheckForNull String getPublicAddressIpv4(@Nonnull Server server) {
        String fixed = null;
        for (List<? extends Address> addresses: server.getAddresses().getAddresses().values()) {
            for (Address addr: addresses) {
                if ("floating".equals(addr.getType())) {
                    return addr.getAddr();
                } else if (addr.getVersion()==4) {
                	fixed = addr.getAddr();
                }
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
            OSClient<?> client = clientProvider.get();
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
        /** Holds instances until their login session expires or until they've been unused for an hour. */
        private final transient @Nonnull Cache<String, Openstack> cache = CacheBuilder.newBuilder()
                .expireAfterAccess(1, TimeUnit.HOURS)
                .build()
        ;

        /** Called to instantiate a new Openstack instance */
        public abstract @Nonnull Openstack getOpenstack(
                @Nonnull String endPointUrl, @Nonnull String identity, @Nonnull String credential, @CheckForNull String region
        ) throws FormValidation;

        /**
         * Obtain Openstack client, instantiating a new one (only) if necessary.
         * This is thread-safe.
         */
        public static @Nonnull Openstack get(
                @Nonnull final String endPointUrl, @Nonnull final String identity, @Nonnull final String credential, @CheckForNull final String region
        ) throws FormValidation {
            final String fingerprint = Util.getDigestOf(endPointUrl + '\n' + identity + '\n' + credential + '\n' + region);
            final FactoryEP ep = ExtensionList.lookup(FactoryEP.class).get(0);
            // Discard existing cached instance if it has expired.
            final String reason;
            final Openstack cachedInstance = ep.cache.getIfPresent(fingerprint);
            if (cachedInstance == null) {
                reason = "there was no instance in the cache";
            } else {
                final long timeNow = System.currentTimeMillis();
                if (cachedInstance.isExpired(timeNow)) {
                    LOGGER.log(Level.FINE, "{0} has expired - invalidating {1} entry \"{2}\".", new Object[] {cachedInstance, ep.cache, fingerprint});
                    ep.cache.invalidate(fingerprint);
                    reason = "the token had expired in the old instance";
                } else {
                    reason = "another thread realised that the token had expired in the old instance";
                }
            }
            final Callable<Openstack> cacheMissFunction = new Callable<Openstack>() {
                @Override
                public Openstack call() throws FormValidation {
                    LOGGER.log(Level.FINER, "Creating new {0}(\"{1}\",\"{2}\",...,\"{3}\") for cache {4} entry \"{5}\" as {6}.",
                            new Object[] {Openstack.class.getSimpleName(), endPointUrl, identity, region, ep.cache, fingerprint, reason});
                    final Openstack newInstance = ep.getOpenstack(endPointUrl, identity, credential, region);
                    final Date expiryDate = newInstance.getExpires();
                    LOGGER.log(Level.INFO, "Created new {0}(\"{1}\",\"{2}\",...,\"{3}\")={4} (login expires at {5}) for cache {6} entry \"{7}\" as {8}.",
                            new Object[] {Openstack.class.getSimpleName(), endPointUrl, identity, region, newInstance, expiryDate, ep.cache, fingerprint, reason});
                    return newInstance;
                }
            };
            // Get an instance, creating a new one if necessary.
            // If there wasn't one earlier and still isn't, we'll create one.
            // If there wasn't one earlier but is now, it'll be recent enough that it won't have expired yet.
            // If there was one earlier but isn't now, it'll be because we decided it'd expired or another thread did.
            // If there was one earlier and still is, it passed our expiry check a moment ago.
            try {
                final Openstack instance = ep.cache.get(fingerprint, cacheMissFunction);
                return instance;
            } catch (UncheckedExecutionException | ExecutionException e) {
                // Exception was thrown when creating a new instance.
                final Throwable cause = e.getCause();
                if (cause instanceof FormValidation) {
                    throw (FormValidation) cause;
                }
                if (cause instanceof RuntimeException) {
                    throw (RuntimeException) cause;
                }
                throw new RuntimeException(e);
            }
        }

        public static @Nonnull FactoryEP replace(@Nonnull FactoryEP factory) {
            ExtensionList<Openstack.FactoryEP> lookup = ExtensionList.lookup(Openstack.FactoryEP.class);
            lookup.clear();
            lookup.add(factory);
            return factory;
        }
    }

    /**
     * Checks that the login session is still valid. This takes into
     * consideration any potential time difference between our clock and the
     * remote clock. It also allows for a margin of error.
     * 
     * @param rightNowAccordingToUs
     *            Time "now".
     * @return true if the login session is now too old to be used OR if it
     *         might expire too soon, false if it will be valid for some time.
     */
    private boolean isExpired(final long rightNowAccordingToUs) {
        // Workaround bug JENKINS-46541.
        // Openstack4J will parse "2017-08-30T13:46:51.480655Z" as "Aug 30
        // 13:54:51 GMT 2017" because it treats ".480655" to be "480655
        // milliseconds" rather than "480655 microseconds".
        // This means that the expiry date can be over-stated by up to 999000
        // milliseconds. We have to ensure we account for that.
        final long openstack4jDateParsingBugWorkaround = 999000L;
        // The token needs to stay valid during the operation(s) we're about to
        // use it for, e.g. waiting for an instance to boot up, so we allow 10
        // minutes.
        final long maxExpectedUsageDuration = 10L * 60L * 1000L;
        final Date tokenIssueDateAccordingToOpenstack = getIssued();
        final long amountOpenstackTimeIsAheadOfUs;
        final long marginOfClockError;
        if (tokenIssueDateAccordingToOpenstack == null) {
            // OpenStack client didn't tell us a login time, so we can't
            // compensate for our time being different.
            amountOpenstackTimeIsAheadOfUs = 0L;
            // ... but we can allow for OpenStack time and our Time to be up to
            // 60 seconds adrift
            marginOfClockError = 60000L;
        } else {
            // Our clocks may differ, so we allow for that difference...
            final long openstackTime = tokenIssueDateAccordingToOpenstack.getTime();
            final long ourTime = getLoginTimeMillis();
            amountOpenstackTimeIsAheadOfUs = openstackTime - ourTime;
            // .. and allow for 10 seconds of drift over the token lifetime.
            marginOfClockError = 10000L;
        }
        final Date tokenExpiryDateAccordingToOpenstack = getExpires();
        final long tokenExpiryAccordingToOpenstack = tokenExpiryDateAccordingToOpenstack.getTime();
        final long remaining = tokenExpiryAccordingToOpenstack - amountOpenstackTimeIsAheadOfUs - rightNowAccordingToUs
                - marginOfClockError - maxExpectedUsageDuration - openstack4jDateParsingBugWorkaround;
        final boolean tokenIsNotValidEnough = remaining <= 0L;
        return tokenIsNotValidEnough;
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

    /**
     * Abstract away the fact client can not be shared between threads and the implementation details for different
     * versions of keystone.
     */
    private static abstract class ClientProvider<T> {
        protected final T storage;

        private ClientProvider(T token) {
            storage = token;
        }

        /**
         * Reuse auth session between different threads creating separate client for every use.
         */
        public abstract @Nonnull OSClient<?> get();

        protected abstract Date _getExpires();
        protected abstract Date _getIssued();

        @Nonnull Date getExpires() {
            Date ex = _getExpires();
            if (ex == null) {
                throw new AssertionError("No expiration specified in " + storage);
            }
            return ex;
        }

        @CheckForNull Date getIssued() {
            Date ex = _getIssued();
            return ex;
        }

        private static ClientProvider<?> get(OSClient<?> client) {
            if (client instanceof OSClient.OSClientV2) return new SessionClientV2Provider((OSClient.OSClientV2) client);
            if (client instanceof OSClient.OSClientV3) return new SessionClientV3Provider((OSClient.OSClientV3) client);

            throw new AssertionError(
                    "Unsupported openstack4j client " + client.getClass().getName()
            );
        }

        private static class SessionClientV2Provider extends ClientProvider<Access> {

            private SessionClientV2Provider(OSClient.OSClientV2 toStore) {
                super(toStore.getAccess());
            }

            public @Nonnull OSClient<?> get() {
                return OSFactory.clientFromAccess(storage);
            }

            public Date _getExpires() {
                return storage.getToken().getExpires();
            }

            @Override
            protected Date _getIssued() {
                return null;
            }
        }

        private static class SessionClientV3Provider extends ClientProvider<Token> {

            private SessionClientV3Provider(OSClient.OSClientV3 toStore) {
                super(toStore.getToken());
            }

            public @Nonnull OSClient<?> get() {
                return OSFactory.clientFromToken(storage);
            }

            public Date _getExpires() {
                return storage.getExpires();
            }

            @Override
            protected Date _getIssued() {
                return storage.getIssuedAt();
            }
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
