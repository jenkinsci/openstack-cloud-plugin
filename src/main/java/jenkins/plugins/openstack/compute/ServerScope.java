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

import javax.annotation.Nonnull;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * Server can be scoped to certain Jenkins entities to constrain its lifetime.
 *
 * Openstack plugin will clean the instance after it is considered out of scope.
 */
@Restricted(NoExternalUse.class)
public abstract class ServerScope {

    /**
     * Name of the openstack metadata key
     */
    public static final String METADATA_KEY = "jenkins-scope";

    protected final @Nonnull String name;
    protected final @Nonnull String specifier;

    public static ServerScope parse(String scope) throws IllegalArgumentException {
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
    abstract public boolean isOutOfScope();

    /**
     * Server is scoped to Jenkins node of the name equal to the specifier.
     *
     * Server marked as <tt>node:asdf</tt> will live only to support Jenkins node <tt>asdf</tt>.
     */
    public static final class Node extends ServerScope {

        public Node(String specifier) {
            super("node", specifier);
        }

        public String getName() {
            return specifier;
        }

        @Override
        public boolean isOutOfScope() {
            if (Jenkins.getActiveInstance().getNode(specifier) != null) return false;

            // The node may be provisioning at the moment
            for (ProvisioningActivity pa : CloudStatistics.get().getNotCompletedActivities()) {
                if (specifier.equals(pa.getName())) {
                    return false;
                }
            }

            return true;
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
        public boolean isOutOfScope() {
            Job job = Jenkins.getActiveInstance().getItemByFullName(project, Job.class);
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
        public boolean isOutOfScope() {
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
}
