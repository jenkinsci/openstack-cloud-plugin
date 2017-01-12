package jenkins.plugins.openstack.compute;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import jenkins.model.Jenkins;
import org.jenkinsci.lib.configprovider.AbstractConfigProviderImpl;
import org.jenkinsci.lib.configprovider.ConfigProvider;
import org.jenkinsci.lib.configprovider.model.Config;
import org.jenkinsci.lib.configprovider.model.ContentType;
import org.kohsuke.stapler.DataBoundConstructor;

public class UserDataConfig extends Config {

    @DataBoundConstructor
    public UserDataConfig(String id, String name, String comment, String content) {
        super(id, name, comment, content);
    }

    @Override
    public ConfigProvider getDescriptor() {
        return Jenkins.getActiveInstance().getDescriptorByType(UserDataConfigProvider.class);
    }

    @Extension(ordinal = 70)
    public static class UserDataConfigProvider extends AbstractConfigProviderImpl {

        public UserDataConfigProvider() {
            load();
        }

        @Override
        public ContentType getContentType() {
            return ContentType.DefinedType.HTML;
        }

        @Override
        public String getDisplayName() {
            return "OpenStack User Data";
        }

        @NonNull
        @Override
        public UserDataConfig newConfig(@NonNull String id) {
            return new UserDataConfig(id, "UserData", "", "");
        }

        // used for migration only
        @Override
        public UserDataConfig convert(Config config) {
            return new UserDataConfig(config.id, config.name, config.comment, config.content);
        }
    }
}
