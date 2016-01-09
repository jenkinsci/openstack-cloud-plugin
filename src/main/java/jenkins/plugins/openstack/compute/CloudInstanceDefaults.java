package jenkins.plugins.openstack.compute;

import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Holds default values for Cloud Instances, like default retention time.
 */
@Restricted(NoExternalUse.class)
final public class CloudInstanceDefaults {
    public static final int DEFAULT_INSTANCE_RETENTION_TIME_IN_MINUTES = 30;
}
