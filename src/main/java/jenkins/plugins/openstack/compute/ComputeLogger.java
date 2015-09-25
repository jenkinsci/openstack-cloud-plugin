package jenkins.plugins.openstack.compute;

import java.util.logging.Logger;

/**
 * bumps {@code openstack.compute} logging category debug to info.
 *
 * @author Adrian Cole
 */
class ComputeLogger extends org.jclouds.logging.jdk.JDKLogger {

    public static class Factory extends JDKLoggerFactory {
        public org.jclouds.logging.Logger getLogger(String category) {
            if (category.equals("openstack.compute") || (category.equals("openstack.wire") && wireLogging))
                return new ComputeLogger(Logger.getLogger(category));
            else
                return super.getLogger(category);
        }
    }

    public ComputeLogger(Logger logger) {
        super(logger);
    }

    @Override
    public boolean isDebugEnabled() {
        return true;
    }

    @Override
    protected void logDebug(String message) {
        super.logInfo(message);
    }

    public static boolean wireLogging = Boolean.getBoolean(ComputeLogger.class.getName() + ".wireLogging");
}
