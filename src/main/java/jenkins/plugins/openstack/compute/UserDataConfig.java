package jenkins.plugins.openstack.compute;


import hudson.Extension;
import jenkins.model.Jenkins;
import org.jenkinsci.lib.configprovider.AbstractConfigProviderImpl;
import org.jenkinsci.lib.configprovider.ConfigProvider;
import org.jenkinsci.lib.configprovider.model.Config;
import org.jenkinsci.lib.configprovider.model.ContentType;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class UserDataConfig extends Config {

    @DataBoundConstructor
    public UserDataConfig(String id, String name, String comment, String content) {
        super(id, name, comment, content);
    }

    @Override public ConfigProvider getDescriptor() {
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

        public Collection<Config> getAllConfigs() {
            List<Config> c = new ArrayList<>(configs.values());
            Collections.sort(c, new Comparator<Config>() {
                @Override public int compare(Config o1, Config o2) {
                    if (o1 == null) return 1;
                    return o1.name.compareTo(o2.name);
                }
            });
            return Collections.unmodifiableCollection(c);
        }
    }
}
