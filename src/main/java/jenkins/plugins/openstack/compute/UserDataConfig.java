package jenkins.plugins.openstack.compute;

import hudson.Extension;
import jenkins.model.Jenkins;
import org.jenkinsci.lib.configprovider.AbstractConfigProviderImpl;
import org.jenkinsci.lib.configprovider.ConfigProvider;
import org.jenkinsci.lib.configprovider.model.Config;
import org.jenkinsci.lib.configprovider.model.ContentType;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collection;

public class UserDataConfig extends Config {

    @DataBoundConstructor
    public UserDataConfig(String id, String name, String comment, String content) {
        super(id, name, comment, content);
    }

    @Override
    public ConfigProvider getDescriptor() {
        return Jenkins.get().getDescriptorByType(UserDataConfigProvider.class);
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

        @Nonnull
        // @Override c-f-p 2.15+
        public Config newConfig(@Nonnull String id) {
            return new UserDataConfig(id, "UserData", "", "");
        }

        // used for migration only
        // @Override c-f-p 2.15+
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
                        usages.add(cloud.name + " / " + template.name);
                    }
                }
            }
            return usages;
        }
    }
}
