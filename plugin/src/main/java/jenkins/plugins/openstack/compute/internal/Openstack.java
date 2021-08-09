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

import com.cloudbees.plugins.credentials.common.PasswordCredentials;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.annotations.VisibleForTesting;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.ExtensionPoint;
import hudson.Util;
import hudson.util.FormValidation;
import jenkins.model.Jenkins;
import jenkins.plugins.openstack.compute.auth.OpenstackCredential;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.openstack4j.api.Builders;
import org.openstack4j.api.OSClient;
import org.openstack4j.api.client.IOSClientBuilder;
import org.openstack4j.api.compute.ServerService;
import org.openstack4j.api.exceptions.ResponseException;
import org.openstack4j.api.networking.NetFloatingIPService;
import org.openstack4j.api.networking.NetworkingService;
import org.openstack4j.core.transport.Config;
import org.openstack4j.model.common.ActionResponse;
import org.openstack4j.model.compute.Address;
import org.openstack4j.model.compute.Fault;
import org.openstack4j.model.compute.Flavor;
import org.openstack4j.model.compute.Keypair;
import org.openstack4j.model.compute.Server;
import org.openstack4j.model.compute.builder.ServerCreateBuilder;
import org.openstack4j.model.compute.ext.AvailabilityZone;
import org.openstack4j.model.identity.v2.Access;
import org.openstack4j.model.identity.v3.Token;
import org.openstack4j.model.image.v2.Image;
import org.openstack4j.model.network.NetFloatingIP;
import org.openstack4j.model.network.Network;
import org.openstack4j.model.network.Port;
import org.openstack4j.model.network.Router;
import org.openstack4j.model.network.ext.NetworkIPAvailability;
import org.openstack4j.model.network.options.PortListOptions;
import org.openstack4j.model.storage.block.Volume;
import org.openstack4j.model.storage.block.Volume.Status;
import org.openstack4j.model.storage.block.VolumeSnapshot;
import org.openstack4j.openstack.OSFactory;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Encapsulate {@link OSClient}.
 *
 * It is needed to make sure the client is truly immutable and provide easy-to-mock abstraction for unit testing.
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

    private static final Comparator<Date> ACCEPT_NULLS = Comparator.nullsLast(Comparator.naturalOrder());
    private static final Comparator<Flavor> FLAVOR_COMPARATOR = Comparator.nullsLast(Comparator.comparing(Flavor::getName));
    private static final Comparator<AvailabilityZone> AVAILABILITY_ZONES_COMPARATOR = Comparator.nullsLast(
            Comparator.comparing(AvailabilityZone::getZoneName)
    );
    private static final Comparator<VolumeSnapshot> VOLUMESNAPSHOT_DATE_COMPARATOR = Comparator.nullsLast(
            Comparator.comparing(VolumeSnapshot::getCreated, ACCEPT_NULLS).thenComparing(VolumeSnapshot::getId))
    ;
    private static final Comparator<Image> IMAGE_DATE_COMPARATOR = Comparator.nullsLast(
            Comparator.comparing(Image::getUpdatedAt, ACCEPT_NULLS).thenComparing(Image::getCreatedAt, ACCEPT_NULLS).thenComparing(Image::getId)
    );

    // Store the OS session token so clients can be created from it per all threads using this.
    private final ClientProvider clientProvider;

    private Openstack(@Nonnull String endPointUrl, boolean ignoreSsl, @Nonnull OpenstackCredential auth, @CheckForNull String region) {

        final IOSClientBuilder<? extends OSClient<?>, ?> builder = auth.getBuilder(endPointUrl);

        Config config = Config.newConfig();
        config.withConnectionTimeout(20_000);
        config.withReadTimeout(20_000);
        if (ignoreSsl) {
            config.withSSLVerificationDisabled();
        }

        OSClient<?> client = builder
                .withConfig(config)
                .authenticate()
                .useRegion(region);

        clientProvider = ClientProvider.get(client, region, config);
        debug("Openstack client created for \"{0}\", \"{1}\".", auth.toString(), region);
    }

    /*exposed for testing*/
    public Openstack(@Nonnull final OSClient<?> client) {
        this.clientProvider = new ClientProvider() {
            @Override public @Nonnull OSClient<?> get() {
                return client;
            }

            @Override public @Nonnull String getInfo() {
                return "";
            }
        };
    }

    public static @Nonnull String getFlavorInfo(@Nonnull Flavor f) {
        return String.format(
                "%s (CPUs: %s, RAM: %sMB, Disk: %sGB, SWAP: %sMB, Ephemeral: %sGB)",
                f.getName(), f.getVcpus(), f.getRam(), f.getDisk(), f.getSwap(), f.getEphemeral()
        );
    }

    /**
     * Get information about OpenStack deployment.
     */
    public @Nonnull String getInfo() {
        return clientProvider.getInfo();
    }

    @VisibleForTesting
    public  @Nonnull List<? extends Network> _listNetworks() {
        return clientProvider.get().networking().network().list();
    }

    /**
     * List all requested network details.
     *
     * @param nameOrIds List of network names/IDs.
     * @return Map of ID and network.
     */
    public @Nonnull Map<String, Network> getNetworks(@Nonnull List<String> nameOrIds) {
        if (nameOrIds.isEmpty()) return Collections.emptyMap();

        nameOrIds = new ArrayList<>(nameOrIds); // Not to modify the argument

        Map<String, Network> ret = new HashMap<>();
        List<? extends Network> networks = _listNetworks();
        for (Network n: networks) {
            if (nameOrIds.contains(n.getName())) {
                ret.put(n.getId(), n);
                nameOrIds.removeAll(Collections.singletonList(n.getName()));
            } else if (nameOrIds.contains(n.getId())) {
                ret.put(n.getId(), n);
                nameOrIds.removeAll(Collections.singletonList(n.getId()));
            }
            if (nameOrIds.isEmpty()) break;
        }

        if (!nameOrIds.isEmpty()) throw new NoSuchElementException("Unable to find networks for: " + nameOrIds);

        return ret;
    }

    /**
     * For every network requested, return mapping of network and number of available fixed addresses.
     *
     * Note the network-ip-availability is usually available for admins only so the method may return <tt>null</tt> in that case.
     *
     * @return Map of requested networks and their free capacity. Might be empty.
     */
    public @Nonnull Map<Network, Integer> getNetworksCapacity(@Nonnull Map<String, Network> declaredNetworks) {
        if (declaredNetworks.isEmpty()) throw new IllegalArgumentException("No request networks provided");

        List<String> declaredIds = declaredNetworks.values().stream().map(Network::getId).collect(Collectors.toList());

        List<? extends NetworkIPAvailability> networkIPAvailabilities = clientProvider.get().networking().networkIPAvailability().get();

        return networkIPAvailabilities.stream().filter( // Those we care for
                n -> declaredIds.contains(n.getNetworkId())
        ).collect(Collectors.toMap( // network -> capacity
                n -> declaredNetworks.get(n.getNetworkId()),
                n -> n.getTotalIps().subtract(n.getUsedIps()).intValue()
        ));
    }

    /**
     * Finds all {@link Image}s.
     *
     * @return A Map of collections of images, indexed by name (or id if the
     *         image has no name) in ascending order and, in the event of
     *         name-collisions, the images for a given name are sorted by
     *         creation date.
     */
    public @Nonnull Map<String, List<Image>> getImages() {
        final List<? extends Image> list = getAllImages();
        TreeMap<String, List<Image>> data = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Image image : list) {
            final String name = Util.fixNull(image.getName());
            final String nameOrId = name.isEmpty() ? image.getId() : name;
            List<Image> sameNamed = data.get(nameOrId);
            if (sameNamed == null) {
                sameNamed = new ArrayList<>();
                data.putIfAbsent(nameOrId, sameNamed);

            }
            sameNamed.add(image);
        }
        for (List<Image> sameNamed : data.values()) {
            sameNamed.sort(IMAGE_DATE_COMPARATOR);
        }

        return data;
    }

    // Glance2 API does not have the listAll() pagination helper in the library so reimplementing it here
    private @Nonnull List<Image> getAllImages() {
        final int LIMIT = 100;
        Map<String, String> params = new HashMap<>(2);
        params.put("limit", Integer.toString(LIMIT));

        List<? extends Image> page = clientProvider.get().imagesV2().list(params);
        List<Image> all = new ArrayList<>(page);
        while(page.size() == LIMIT) {
            params.put("marker", page.get(LIMIT - 1).getId());
            page = clientProvider.get().imagesV2().list(params);
            all.addAll(page);
        }

        return all;
    }

    /**
     * Finds all {@link VolumeSnapshot}s that are {@link Status#AVAILABLE}.
     *
     * @return A Map of collections of {@link VolumeSnapshot}s, indexed by name
     *         (or id if the volume snapshot has no name) in ascending order
     *         and, in the event of name-collisions, the volume snapshots for a
     *         given name are sorted by creation date.
     */
    public @Nonnull Map<String, List<VolumeSnapshot>> getVolumeSnapshots() {
        final List<? extends VolumeSnapshot> list = clientProvider.get().blockStorage().snapshots().list();
        TreeMap<String, List<VolumeSnapshot>> data = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        //final TreeMultimap<String, VolumeSnapshot> set = TreeMultimap.create(String.CASE_INSENSITIVE_ORDER, VOLUMESNAPSHOT_DATE_COMPARATOR);
        for (VolumeSnapshot vs : list) {
            if (vs.getStatus() != Status.AVAILABLE) {
                continue;
            }
            final String name = Util.fixNull(vs.getName());
            final String nameOrId = name.isEmpty() ? vs.getId() : name;
            List<VolumeSnapshot> sameNamed = data.get(nameOrId);
            if (sameNamed == null) {
                sameNamed = new ArrayList<>();
                data.putIfAbsent(nameOrId, sameNamed);

            }
            sameNamed.add(vs);
        }
        for (List<VolumeSnapshot> sameNamed : data.values()) {
            sameNamed.sort(VOLUMESNAPSHOT_DATE_COMPARATOR);
        }
        return data;
    }


    public @Nonnull Collection<? extends Flavor> getSortedFlavors() {
        List<? extends Flavor> flavors = clientProvider.get().compute().flavors().list();
        flavors.sort(FLAVOR_COMPARATOR);
        return flavors;
    }


    public @Nonnull List<String> getSortedIpPools() {
        List<? extends Router> routers = clientProvider.get().networking().router().list();
        List<? extends Network> networks = clientProvider.get().networking().network().list();

        // There can be multiple router->subnet connection for the same network
        HashSet<Network> applicablePublicNetworks = new HashSet<>();
        for (Router r: routers) {
            for (Network n: networks) {
                if (Objects.equals(r.getExternalGatewayInfo().getNetworkId(), n.getId())) {
                    applicablePublicNetworks.add(n);
                }
            }
        }

        return applicablePublicNetworks.stream().map(Network::getName).sorted().collect(Collectors.toList());
    }

    public @Nonnull List<? extends AvailabilityZone> getAvailabilityZones() {
        final List<? extends AvailabilityZone> zones = clientProvider.get().compute().zones().list();
        zones.sort(AVAILABILITY_ZONES_COMPARATOR);
        return zones;
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

    /**
     * Get list of Floating IPs created for this Jenkins instance that are not connected to any server.
     */
    public @Nonnull List<String> getFreeFipIds() {
        List<String> freeIps = new ArrayList<>();
        for (NetFloatingIP ip : clientProvider.get().networking().floatingip().list()) {
            if (ip.getFixedIpAddress() != null) continue; // Used

            String serverId = FipScope.getServerId(instanceFingerprint(), ip.getDescription());
            if (serverId == null) continue; // Not ours

            freeIps.add(ip.getId());
        }

        return freeIps;
    }

    public @Nonnull List<String> getSortedKeyPairNames() {
        List<String> keyPairs = new ArrayList<>();
        for (Keypair kp : clientProvider.get().compute().keypairs().list()) {
            keyPairs.add(kp.getName());
        }
        return keyPairs;
    }

    /**
     * Finds the Id(s) of all active {@link Image}s with the given name or ID.
     * If we have found multiple {@link Image}s then they will be listed in
     * ascending date order (oldest first).
     *
     * @param nameOrId The {@link Image} name or ID.
     * @return Zero, one or multiple IDs.
     */
    public @Nonnull List<String> getImageIdsFor(String nameOrId) {
        final Collection<Image> sortedObjects = new TreeSet<>(IMAGE_DATE_COMPARATOR);
        final Map<String, String> query = new HashMap<>(2);
        query.put("name", nameOrId);
        query.put("status", "active");
        final List<? extends Image> findByName = clientProvider.get().imagesV2().list(query);
        sortedObjects.addAll(findByName);
        if (nameOrId.matches("[0-9a-f-]{36}")) {
            final Image findById = clientProvider.get().imagesV2().get(nameOrId);
            if (findById != null && findById.getStatus() == Image.ImageStatus.ACTIVE) {
                sortedObjects.add(findById);
            }
        }
        final List<String> ids = new ArrayList<>();
        for (Image i : sortedObjects) {
            ids.add(i.getId());
        }
        return ids;
    }

    /**
     * Finds the Id(s) of all available {@link VolumeSnapshot}s with the given name
     * or ID. If we have found multiple {@link VolumeSnapshot}s then they will
     * be listed in ascending date order (oldest first).
     *
     * @param nameOrId The {@link VolumeSnapshot} name or ID.
     * @return Zero, one or multiple IDs.
     */
    public @Nonnull List<String> getVolumeSnapshotIdsFor(String nameOrId) {
        final Collection<VolumeSnapshot> sortedObjects = new TreeSet<>(VOLUMESNAPSHOT_DATE_COMPARATOR);
        // OpenStack block-storage/v3 API doesn't allow us to filter by name, so fetch all and search.
        final Map<String, List<VolumeSnapshot>> allVolumeSnapshots = getVolumeSnapshots();
        final Collection<VolumeSnapshot> findByName = allVolumeSnapshots.get(nameOrId);
        if (findByName != null) {
            sortedObjects.addAll(findByName);
        }
        if (nameOrId.matches("[0-9a-f-]{36}")) {
            final VolumeSnapshot findById = clientProvider.get().blockStorage().snapshots().get(nameOrId);
            if (findById != null && findById.getStatus() == Status.AVAILABLE) {
                sortedObjects.add(findById);
            }
        }
        final List<String> ids = new ArrayList<>();
        for (VolumeSnapshot i : sortedObjects) {
            ids.add(i.getId());
        }
        return ids;
    }

    /**
     * Gets the description of a {@link VolumeSnapshot}. This will be visible
     * if a user looks at volume snapshots using the OpenStack command-line or WebUI
     * and may well contain useful information.
     *
     * @param volumeSnapshotId
     *            The ID of the volume snapshot whose description is to be retrieved.
     * @return The description string, or null if there isn't one.
     */
    public @CheckForNull String getVolumeSnapshotDescription(String volumeSnapshotId) {
        return clientProvider.get().blockStorage().snapshots().get(volumeSnapshotId).getDescription();
    }

    /**
     * Sets the name and description of a {@link Volume}. These will be visible
     * if a user looks at volumes using the OpenStack command-line or WebUI.
     *
     * @param volumeId
     *            The ID of the volume whose name and description are to be set.
     * @param newVolumeName
     *            The new name for the volume.
     * @param newVolumeDescription
     *            The new description for the volume.
     */
    public void setVolumeNameAndDescription(String volumeId, String newVolumeName, String newVolumeDescription) {
        final ActionResponse res = clientProvider.get().blockStorage().volumes().update(volumeId, newVolumeName, newVolumeDescription);
        throwIfFailed(res);
    }

    /**
     * Determine whether the server is considered occupied by openstack plugin.
     */
    public static boolean isOccupied(@Nonnull Server server) {
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
        String rootUrl = Jenkins.get().getRootUrl();
        if (rootUrl == null) throw new IllegalStateException("Jenkins instance URL is not configured");
        return rootUrl;
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
            debug("Machine started: {0}", server.getName());
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

        NetFloatingIPService fipService = clientProvider.get().networking().floatingip();
        List<String> portIds = getServerPorts(server).stream().map(Port::getId).collect(Collectors.toList());
        List<? extends NetFloatingIP> associatedFips = fipService.list().stream().filter(
                fip -> portIds.contains(fip.getPortId())
        ).collect(Collectors.toList());

        ServerService servers = clientProvider.get().compute().servers();
        server = servers.get(nodeId);
        if (server == null || server.getStatus() == Server.Status.DELETED) {
            debug("Machine destroyed: {0}", nodeId);
        }

        ActionResponse serverDelete = servers.delete(nodeId);
        if (serverDelete.getCode() == 404) {
            debug("Machine destroyed: {0}", nodeId);
        } else {
            throwIfFailed(serverDelete);
        }

        for (NetFloatingIP fip : associatedFips) {
            ActionResponse fipDelete = fipService.delete(fip.getId());
            if (fipDelete.getCode() == 404) {
                debug("Fip destroyed: {0}", fip.getId());
                continue;
            }

            throwIfFailed(fipDelete);
        }
    }

    /**
     * Assign floating ip address to the server.
     *
     * Note that after the successful assignment, the old Server instance becomes outdated as it does not contain the IP details.
     *
     * The IP description will contain fingerprint linking it back to server it was created for. It is guaranteed it will
     * be created after the server so any IP found without its server running is a leaked one.
     *
     * @param server Server to assign FIP
     * @param poolName Name of the FIP pool to use.
     * @return Updated server.
     */
    public @Nonnull Server assignFloatingIp(@Nonnull Server server, @Nonnull String poolName) throws ActionFailed {
        debug("Allocating floating IP for {0} in {1}", server.getName(), server.getName());
        NetworkingService networking = clientProvider.get().networking();

        String desc = FipScope.getDescription(instanceFingerprint(), server);

        Port port = getServerPorts(server).get(0);
        Network network = networking.network().list(Collections.singletonMap("name", poolName)).get(0);
        NetFloatingIP fip = Builders.netFloatingIP().floatingNetworkId(network.getId()).portId(port.getId()).description(desc).build();
        try {
            NetFloatingIP ip = networking.floatingip().create(fip);
            // Make sure address information is reflected in metadata
            for (int i = 0; i < 30; i++) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt(); // Reset interrupt flag
                    throw new ActionFailed("Interrupted", ex);
                }
                server = updateInfo(server);
                Optional<? extends Address> newIp = server.getAddresses().getAddresses().values().stream()
                        .flatMap(Collection::stream)
                        .filter(address -> Objects.equals(address.getAddr(), ip.getFloatingIpAddress()))
                        .findFirst()
                ;
                if (newIp.isPresent()) {
                    return server;
                }
            }
            destroyFip(ip.getId());
            throw new ActionFailed("IP address not propagated in time for " + server.getName());
        } catch (ResponseException ex) {
            // TODO Grab some still IPs from JCloudsCleanupThread
            throw new ActionFailed(ex.getMessage() + " Allocating for " + server.getName(), ex);
        }
    }

    private List<? extends Port> getServerPorts(@Nonnull Server server) {
        return clientProvider.get().networking().port().list(PortListOptions.create().deviceId(server.getId()));
    }

    public void destroyFip(String fip) {
        ActionResponse delete = clientProvider.get().networking().floatingip().delete(fip);

        // Deleted by some other action. Being idempotent here and reporting success.
        if (delete.getCode() == 404) return;

        throwIfFailed(delete);
    }

    /**
     * Extract public address from server.
     *
     * @return Preferring IPv4 over IPv6 and floating address over fixed.
     * @throws IllegalArgumentException When address can not be understood.
     * @throws NoSuchElementException When no suitable address is found.
     */
    public static @CheckForNull String getAccessIpAddress(@Nonnull Server server) throws IllegalArgumentException, NoSuchElementException {
        return getAccessIpAddressObject(server).getAddr();
    }

    public static Address getAccessIpAddressObject(@Nonnull Server server) {
        Address fixedIPv4 = null;
        Address fixedIPv6 = null;
        Address floatingIPv6 = null;
        Collection<List<? extends Address>> addressMap = server.getAddresses().getAddresses().values();
        for (List<? extends Address> addresses: addressMap) {
            for (Address addr: addresses) {
                String type = addr.getType();
                int version = addr.getVersion();
                String address = addr.getAddr();

                if (version != 4 && version != 6) throw new IllegalArgumentException(
                        "Unknown or unsupported IP protocol version: " + version
                );

                if (Objects.equals(type, "floating")) {
                    if (version == 4) {
                        // The most favourable option so we can return early here
                        return addr;
                    } else {
                        if (floatingIPv6 == null) {
                            floatingIPv6 = addr;
                        }
                    }
                } else {
                    if (version == 4) {
                        if (fixedIPv4 == null) {
                            fixedIPv4 = addr;
                        }
                    } else {
                        if (fixedIPv6 == null) {
                            fixedIPv6 = addr;
                        }
                    }
                }
            }
        }

        if (floatingIPv6 != null) return floatingIPv6;
        if (fixedIPv4 != null) return fixedIPv4;
        if (fixedIPv6 != null) return fixedIPv6;

        throw new NoSuchElementException("No access IP address found for " + server.getName() + ": " + addressMap);
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
            client.networking().network().list();
            client.images().listMembers("");
            client.compute().flavors().list();
        } catch (Throwable ex) {
            return ex;
        }
        return null;
    }

    public static final class ActionFailed extends RuntimeException {
        private static final long serialVersionUID = -1657469882396520333L;

        public ActionFailed(String msg) {
            super(msg);
        }

        public ActionFailed(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    /**
     * Logs a message at {@link Level#FINE}.
     * 
     * @param msg
     *            The message format, where '{0}' will be replaced by args[0],
     *            '{1}' by args[1] etc.
     * @param args
     *            The arguments.
     */
    private static void debug(@Nonnull String msg, @Nonnull String... args) {
        LOGGER.log(Level.FINE, msg, args);
    }

    @Restricted(NoExternalUse.class) // Extension point just for testing
    public static abstract class FactoryEP implements ExtensionPoint {
        private final transient @Nonnull Cache<String, Openstack> cache = Caffeine.newBuilder()
                // There is no clear reasoning behind particular expiration policy except that individual instances can
                // have different token expiration time, which is something guava does not support. This expiration needs
                // to be implemented separately.
                // According to OpenStack documentation, default token lifetime is one hour BUT we have to ensure that
                // we do not cache anything beyond its expiry (see JENKINS-46541) so we've settled on 10 minutes as a
                // compromise between discarding too quickly and the danger of keeping them for too long.
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .build()
        ;

        public abstract @Nonnull Openstack getOpenstack(
                @Nonnull String endPointUrl, boolean ignoreSsl, @Nonnull OpenstackCredential openstackCredential, @CheckForNull String region
        ) throws FormValidation;

        /**
         * Instantiate Openstack client.
         */
        public static @Nonnull Openstack get(
                @Nonnull final String endPointUrl, final boolean ignoreSsl, @Nonnull final OpenstackCredential auth, @CheckForNull final String region
        ) throws FormValidation {
            final String fingerprint = Util.getDigestOf(endPointUrl +  '\n'
                    + ignoreSsl + '\n'
                    + auth.toString() + '\n'
                    + (auth instanceof PasswordCredentials ? ((PasswordCredentials) auth).getPassword().getEncryptedValue() + '\n' : "")
                    + region);
            final FactoryEP ep = ExtensionList.lookup(FactoryEP.class).get(0);
            final Function<String, Openstack> cacheMissFunction = (String unused) -> {
                try {
                    return ep.getOpenstack(endPointUrl, ignoreSsl, auth, region);
                } catch (FormValidation ex) {
                    throw new RuntimeException(ex);
                }
            };

            try {
                // cacheMissFunction is guaranteed to return nonnull
                return Objects.requireNonNull(ep.cache.get(fingerprint, cacheMissFunction));
            } catch (RuntimeException e) { // Propagated from cacheMissFunction
                // Exception was thrown when creating a new instance.
                final Throwable cause = e.getCause();
                if (cause instanceof FormValidation) {
                    throw (FormValidation) cause;
                }
                if (cause instanceof RuntimeException) {
                    throw (RuntimeException) cause;
                }
                throw e;
            }
        }

        @SuppressWarnings("deprecation")
        public static @Nonnull FactoryEP replace(@Nonnull FactoryEP factory) {
            ExtensionList<Openstack.FactoryEP> lookup = ExtensionList.lookup(Openstack.FactoryEP.class);
            lookup.clear();
            lookup.add(factory);
            return factory;
        }

        @Restricted(NoExternalUse.class) // Just for testing
        public static @Nonnull Cache<String, Openstack> getCache() {
            final FactoryEP ep = ExtensionList.lookup(FactoryEP.class).get(0);
            return ep.cache;
        }
    }

    @Extension
    public static final class Factory extends FactoryEP {
        public @Nonnull Openstack getOpenstack(@Nonnull String endPointUrl, boolean ignoreSsl, @Nonnull OpenstackCredential auth, @CheckForNull String region) throws FormValidation {
            endPointUrl = Util.fixEmptyAndTrim(endPointUrl);
            region = Util.fixEmptyAndTrim(region);

            if (endPointUrl == null) throw FormValidation.error("No endPoint specified");
            if (auth == null) throw FormValidation.error("No credential specified");

            return new Openstack(endPointUrl, ignoreSsl, auth, region);
        }
    }

    /**
     * Abstract away the fact client can not be shared between threads and the implementation details for different
     * versions of keystone.
     */
    private static abstract class ClientProvider {
        /**
         * Reuse auth session between different threads creating separate client for every use.
         */
        public abstract @Nonnull OSClient<?> get();

        public abstract @Nonnull String getInfo();

        private static ClientProvider get(OSClient<?> client, String region, Config config) {
            if (client instanceof OSClient.OSClientV2) return new SessionClientV2Provider((OSClient.OSClientV2) client, region, config);
            if (client instanceof OSClient.OSClientV3) return new SessionClientV3Provider((OSClient.OSClientV3) client, region, config);

            throw new AssertionError(
                    "Unsupported openstack4j client " + client.getClass().getName()
            );
        }

        private static class SessionClientV2Provider extends ClientProvider {
            protected final Access storage;
            protected final String region;
            protected final Config config;
            private SessionClientV2Provider(OSClient.OSClientV2 toStore, String usedRegion, Config clientConfig) {
                storage = toStore.getAccess();
                region = usedRegion;
                config = clientConfig;
            }

            public @Nonnull OSClient<?> get() {
                return OSFactory.clientFromAccess(storage, config).useRegion(region);
            }

            @Override
            public @Nonnull String getInfo() {
                StringBuilder sb = new StringBuilder();
                for (Access.Service service: storage.getServiceCatalog()) {
                    if (sb.length() != 0) {
                        sb.append(", ");
                    }
                    sb.append(service.getType()).append('/').append(service.getName()).append(':').append(service.getVersion());
                }
                return sb.toString();
            }
        }

        private static class SessionClientV3Provider extends ClientProvider {
            private final Token storage;
            private final String region;
            protected final Config config;
            private SessionClientV3Provider(OSClient.OSClientV3 toStore, String usedRegion, Config clientConfig) {
                storage = toStore.getToken();
                region = usedRegion;
                config = clientConfig;
            }

            public @Nonnull OSClient<?> get() {
                return OSFactory.clientFromToken(storage, config).useRegion(region);
            }

            @Override
            public @Nonnull String getInfo() {
                // TODO version and enabled does not seem to be ever set and printing anything is pointless without it
                return "";
//                StringBuilder sb = new StringBuilder();
//                for (Service service: storage.getCatalog()) {
//                    if (sb.length() != 0) {
//                        sb.append(", ");
//                    }
//
//                    sb.append(service.getType()).append('/').append(service.getName()).append(':').append(service.getVersion());
//                    if (!service.isEnabled()) {
//                        sb.append(" (disabled)");
//                    }
//                }
//                return sb.toString();
            }
        }
    }
}
