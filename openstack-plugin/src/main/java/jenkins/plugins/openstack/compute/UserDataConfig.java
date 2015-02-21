package jenkins.plugins.openstack.compute;


import hudson.Extension;
import org.jenkinsci.lib.configprovider.AbstractConfigProviderImpl;
import org.jenkinsci.lib.configprovider.model.Config;
import org.jenkinsci.lib.configprovider.model.ContentType;
import org.kohsuke.stapler.DataBoundConstructor;


public class UserDataConfig extends Config {

    @DataBoundConstructor
    public UserDataConfig(String id, String name, String comment, String content) {
        super(id, name, comment, content);
    }

    @Extension(ordinal = 70)
    public static class UserDataConfigProvider extends AbstractConfigProviderImpl {

        @Override
        public ContentType getContentType() {
            return ContentType.DefinedType.HTML;
        }

        @Override
        public String getDisplayName() {
            return "Openstack User Data";
        }

    }
}
