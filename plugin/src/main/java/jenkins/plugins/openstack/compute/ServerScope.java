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
package jenkins.plugins.openstack.compute;

import hudson.model.Job;
import hudson.model.Run;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.cloudstats.CloudStatistics;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.openstack4j.model.compute.Server;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Server can be scoped to certain Jenkins entities to constrain its lifetime.
 *
 * Openstack plugin will clean the instance after it is considered out of scope.
 */
@Restricted(NoExternalUse.class)
public abstract class ServerScope {

    private static final Logger LOGGER = Logger.getLogger(ServerScope.class.getName());

    /**
     * Name of the openstack metadata key
     */
    public static final String METADATA_KEY = "jenkins-scope";

    protected final @Nonnull String name;
    protected final @Nonnull String specifier;

    public static ServerScope parse(String scope) throws IllegalArgumentException {
        if (scope.startsWith("unlimited")) {
            return Unlimited.getInstance();
        }
        String[] chunks = scope.trim().split(":", 2);
        if (chunks.length != 2) throw new IllegalArgumentException("Invalid scope: " + scope);
        if ("node".equals(chunks[0])) {
            return new Node(chunks[1]);
        } else if ("run".equals(chunks[0])) {
            return new Build(chunks[1]);
        } else if ("time".equals(chunks[0])) {
            return new Time(chunks[1]);
        } else {
            throw new IllegalArgumentException("Invalid scope kind: " + chunks[0]);
        }
    }

    private ServerScope(@Nonnull String scopeName, @Nonnull String specifier) {
        this.name = scopeName;
        this.specifier = specifier;
    }

    /**
     * Get the scope of a server.
     *
     * @return The scope or null if there is none declared.
     * @throws IllegalStateException In case the scope can not be parsed.
     */
    public static @Nonnull ServerScope extract(Server server) throws IllegalStateException {
        String scope = server.getMetadata().get(METADATA_KEY);
        // Provisioned in a way that do not support scoping or before scoping was introduced
        if (scope == null) return Unlimited.getInstance();

        try {
            return parse(scope);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Unable to parse scope '" + scope + "' of " + server.getName());
        }
    }

    @Override
    public String toString() {
        return getValue();
    }

    /**
     * Get Metadata value that represent this scope.
     */
    public String getValue() {
        return name + ":" + specifier;
    }

    @Override
    public int hashCode() {
        return name.hashCode() * 31 + specifier.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        ServerScope that = (ServerScope) o;

        if (!name.equals(that.name)) return false;
        if (!specifier.equals(that.specifier)) return false;

        return _equals(that);
    }

    /**
     * Contribute additional criteria for equality.
     *
     * This is mostly useful for testing as the implementations are not expected
     * to have mutable state or any state not represented by specifier.
     */
    protected boolean _equals(ServerScope o) {
        return true;
    }

    /**
     * Determine whether the server is out of scope or not.
     */
    abstract public boolean isOutOfScope(@Nonnull Server server);

    /**
     * Server is scoped to Jenkins node of the name equal to the specifier.
     *
     * Server marked as <code>node:asdf</code> will live only to support Jenkins node <code>asdf</code>.
     */
    public static final class Node extends ServerScope {
        private final @Nonnull String name;
        private final @CheckForNull Integer cloudStatsFingerprint;

        public Node(@Nonnull String specifier) {
            super("node", specifier);

            String[] split = specifier.split(":");
            switch (split.length) {
                case 1:
                    name = specifier;
                    cloudStatsFingerprint = null;
                break;
                case 2:
                    name = split[0];
                    cloudStatsFingerprint = Integer.parseInt(split[1]);
                break;
                default: throw new IllegalArgumentException("Invalid node scope specifier " + specifier);
            }
        }

        public Node(@Nonnull String serverName, @Nonnull ProvisioningActivity.Id id) {
            super("node", serverName+":"+id.getFingerprint());
            name = serverName;
            cloudStatsFingerprint = id.getFingerprint();
        }

        @Nonnull public String getName() {
            return name;
        }

        @Override
        public boolean isOutOfScope(@Nonnull Server server) {
            if (computerExists()) return false;

            if (cloudStatsFingerprint != null) {
                // The node may be provisioned or deleted at the moment - do not interfere
                for (ProvisioningActivity pa : CloudStatistics.get().getActivities()) {
                    // Note the node name might not have been assigned yet so using fingerprint instead
                    if (pa.getId().getFingerprint() == cloudStatsFingerprint) {
                        switch (pa.getCurrentPhase()) {
                            case PROVISIONING:
                                return false; // Node not yet created
                            case LAUNCHING:
                            case OPERATING:
                                LOGGER.warning("Node does not exist for " + pa.getCurrentPhase() + " " + specifier);
                                return false;
                            case COMPLETED:
                                return true;
                        }
                        assert false: "Unreachable";
                    }
                }
            }

            Date created = server.getCreated();
            if (created != null) {
                long now = System.currentTimeMillis();
                long ageHours = (now - created.getTime()) / (1000 * 60 * 60);

                if (ageHours < 1) return false; // Make sure not to throw it away during launching
            }

            // Resolving activity by name is not reliable as they may not be assigned yet or the activity might already
            // be rotated when in completed state. In the latter case it should be treated as out of scope.
            return true;
        }

        private boolean computerExists() {
            hudson.model.Node node = Jenkins.get().getNode(getName());
            if (!(node instanceof JCloudsSlave)) return false; // Node does not exists or is not our node

            if (cloudStatsFingerprint == null) return true; // CloudStats id not reported during provisioning - not deeper verification

            final JCloudsSlave slave = ((JCloudsSlave) node);
            return cloudStatsFingerprint == slave.getId().getFingerprint();
        }
    }

    public static final class Build extends ServerScope {
        private final @Nonnull String project;
        private final int run;

        public Build(Run run) {
            this(run.getParent().getFullName() + ":" + run.getNumber());
        }

        public Build(String specifier) {
            super("run", specifier);

            String[] chunks = this.specifier.split(":", 2);
            if (chunks.length != 2) throw new IllegalArgumentException("Invalid run specifier: " + specifier);
            project = chunks[0];
            run = Integer.parseInt(chunks[1]);

            if (project.isEmpty() || run < 0) throw new IllegalArgumentException(
                    "Invalid run specifier: " + specifier
            );
        }

        public @Nonnull String getProject() {
            return project;
        }

        public int getRunNumber() {
            return run;
        }

        @Override
        public boolean isOutOfScope(@Nonnull Server server) {
            Job job = Jenkins.get().getItemByFullName(project, Job.class);
            if (job == null) return true; // Presuming it was deleted/renamed, either way the build do not need the server anymore
            hudson.model.Run run = job.getBuildByNumber(this.run);
            if (run == null) return true; // Presuming it was deleted already

            return !run.isLogUpdated(); // Even post-production completed
        }

        @Override
        protected boolean _equals(ServerScope o) {
            Build that = (Build) o;
            return run == that.run && project.equals(that.project);
        }
    }

    public static final class Time extends ServerScope {

        // Track the time using master clock so we do not have to sync with openstack time difference
        private final long aliveUntil;

        public Time(int duration, TimeUnit tu) {
            this(System.currentTimeMillis() + tu.toMillis(duration));
        }

        private Time(long millis) {
            super("time", format().format(new Date(millis)));
            aliveUntil = millis;
        }

        // The instance is not type safe so create a new one every time
        private static SimpleDateFormat format() {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        }

        public Time(String specifier) {
            super("time", specifier);
            try {
                aliveUntil = format().parse(specifier).getTime();
            } catch (ParseException e) {
                throw new IllegalArgumentException(e);
            }
        }

        @Override
        public boolean isOutOfScope(@Nonnull Server server) {
            return System.currentTimeMillis() > aliveUntil;
        }

        @Override
        public String getValue() {
            return name + ":" + format().format(new Date(aliveUntil));
        }

        @Override
        protected boolean _equals(ServerScope o) {
            Time that = (Time) o;
            return aliveUntil == that.aliveUntil;
        }
    }

    /**
     * Opt-out of any cleanup performed by the plugin.
     */
    public static final class Unlimited extends ServerScope {

        private static Unlimited INSTANCE = new Unlimited();

        private Unlimited() {
            super("unlimited", "unlimited");
        }

        public static Unlimited getInstance() {
            return INSTANCE;
        }

        @Override
        public boolean isOutOfScope(@Nonnull Server server) {
            return false;
        }
    }
}
