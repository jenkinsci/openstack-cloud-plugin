package jenkins.plugins.openstack.compute;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.model.Descriptor;
import hudson.slaves.RetentionStrategy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nonnull;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * @author Vijay Kiran
 */
public class JCloudsRetentionStrategy extends RetentionStrategy<JCloudsComputer> {
    private transient ReentrantLock checkLock;

    @DataBoundConstructor
    public JCloudsRetentionStrategy() {
        readResolve();
    }

    @Override
    public long check(@Nonnull JCloudsComputer c) {
        if (disabled) {
            LOGGER.fine("Skipping check - disabled");
            return 1;
        }
        LOGGER.finest("Checking");

        if (!checkLock.tryLock()) {
            LOGGER.info("Failed to acquire retention lock - skipping");
            return 1;
        }

        try {
            doCheck(c);
        } finally {
            checkLock.unlock();
        }
        return 1;
    }

    private void doCheck(JCloudsComputer c) {
        if (c.isPendingDelete()) return; // No need to do it again
        if (c.isConnecting())
            return; // Do not discard slave while launching for the first time when "idle time" does not make much sense
        if (!c.isIdle() || c.isUserOffline()) return; // Occupied by user initiated activity

        final JCloudsSlave node = c.getNode();
        if (node == null) return; // Node is gone already

        final int retentionTime = node.getSlaveOptions().getRetentionTime();
        if (retentionTime <= 0) return; // 0 is handled in JCloudsComputer, negative values needs no handling
        final long idleSince = c.getIdleStart();
        final long idleMilliseconds = getNow() - idleSince;
        if (idleMilliseconds > TimeUnit.MINUTES.toMillis(retentionTime)) {
            if (JCloudsPreCreationThread.isNeededReadyComputer(node.getComputer())) {
                LOGGER.fine("Keeping " + c.getName() + " to meet minimum requirements");
                return;
            }
            LOGGER.info("Scheduling " + c.getName() + " for termination after " + retentionTime
                    + " minutes as it was idle since " + new Date(idleSince));
            if (LOGGER.isLoggable(Level.FINE)) {
                try {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    Jenkins.XSTREAM2.toXMLUTF8(node, out);
                    LOGGER.fine(out.toString("UTF-8"));
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Failed to dump node config", e);
                }
            }
            c.setPendingDelete(true);
        }
    }

    /*package for mocking*/ long getNow() {
        return System.currentTimeMillis();
    }

    /**
     * Try to connect to it ASAP.
     */
    @Override
    public void start(JCloudsComputer c) {
        c.connect(false);
    }

    // no @Extension since this retention strategy is used only for cloud nodes that we provision automatically.
    public static class DescriptorImpl extends Descriptor<RetentionStrategy<?>> {
        @Override
        public String getDisplayName() {
            return "JClouds";
        }
    }

    protected Object readResolve() {
        checkLock = new ReentrantLock(false);
        return this;
    }

    private static final Logger LOGGER = Logger.getLogger(JCloudsRetentionStrategy.class.getName());

    @SuppressFBWarnings({"MS_SHOULD_BE_FINAL", "Left modifiable from groovy"})
    /*package*/ static boolean disabled = Boolean.getBoolean(JCloudsRetentionStrategy.class.getName() + ".disabled");
}
