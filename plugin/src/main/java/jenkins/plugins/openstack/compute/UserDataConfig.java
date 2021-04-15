package jenkins.plugins.openstack.compute;

import hudson.Extension;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.jenkinsci.lib.configprovider.AbstractConfigProviderImpl;
import org.jenkinsci.lib.configprovider.ConfigProvider;
import org.jenkinsci.lib.configprovider.model.Config;
import org.jenkinsci.lib.configprovider.model.ContentType;
import org.jenkinsci.plugins.configfiles.ConfigFiles;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.annotation.Nonnull;
import javax.annotation.CheckForNull;
import java.util.ArrayList;
import java.util.Collection;

public class UserDataConfig extends Config {
    private static final long serialVersionUID = -1136594228956429772L;

    @DataBoundConstructor
    public UserDataConfig(String id, String name, String comment, String content) {
        super(id, name, comment, content);
    }

    @Override
    public ConfigProvider getDescriptor() {
        return Jenkins.get().getDescriptorByType(UserDataConfigProvider.class);
    }

    /**
     * @return Null when id is null or content is empty.
     */
    public static @CheckForNull String resolve(@CheckForNull String userDataId) {
        if (userDataId == null) return null;

        Config userData = ConfigFiles.getByIdOrNull(Jenkins.get(), userDataId);
        if (userData == null) throw new IllegalArgumentException("Unable to locate OpenStack user-data named '" + userDataId + "'");
        if (!(userData instanceof UserDataConfig)) throw new IllegalArgumentException(
                "The config file used for user-data is not of the correct type: " + userData.getClass()
        );

        return userData.content.isEmpty() ? null : userData.content ;
    }

    @Extension(ordinal = 70) @Symbol("openstackUserData")
    public static class UserDataConfigProvider extends AbstractConfigProviderImpl {

        @SuppressWarnings("deprecation") // https://github.com/jenkinsci/config-file-provider-plugin/pull/114
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

        @Override
        public @Nonnull Config newConfig(@Nonnull String id) {
            return new UserDataConfig(id, "UserData", "", "");
        }

        // used for migration only
        @SuppressWarnings({"unchecked", "deprecation"})
        @Override
        public Config convert(Config config) {
            return new UserDataConfig(config.id, config.name, config.comment, config.content);
        }

        @Restricted(DoNotUse.class) // Jelly
        public Collection<UserDataVariableResolver.Entry> getVariables() {
            return UserDataVariableResolver.STUB.values();
        }

        @Restricted(DoNotUse.class) // Jelly
        public Collection<String> usages(@Nonnull String id) {
            ArrayList<String> usages = new ArrayList<String>();
            for (JCloudsCloud cloud : JCloudsCloud.getClouds()) {
                for (JCloudsSlaveTemplate template : cloud.getTemplates()) {
                    if (id.equals(template.getEffectiveSlaveOptions().getUserDataId())) {
                        usages.add(cloud.name + " / " + template.getName());
                    }
                }
            }
            return usages;
        }
    }
}
