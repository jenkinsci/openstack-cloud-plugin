package jenkins.plugins.openstack.compute;


import hudson.Extension;
import java.util.ArrayList;
import java.util.List;
import org.jenkinsci.lib.configprovider.AbstractConfigProviderImpl;
import org.jenkinsci.lib.configprovider.model.Config;
import org.jenkinsci.lib.configprovider.model.ContentType;
import org.kohsuke.stapler.DataBoundConstructor;


public class UserDataConfig extends Config {
    public final List<Arg> args;

    @DataBoundConstructor
    public UserDataConfig(String id, String name, String comment, String content, List<Arg> args) {
        super(id, name, comment, content);
        if (args != null) {
            List<Arg> filteredArgs = new ArrayList<UserDataConfig.Arg>();
            for (Arg arg : args) {
                if (arg.name != null && arg.name.trim().length() > 0) {
                    filteredArgs.add(arg);
                }
            }
            this.args = filteredArgs;
        } else {
            this.args = null;
        }
    }

    public static class Arg {
        public final String name;
        @DataBoundConstructor
        public Arg(final String name) {
            this.name = name;
        }
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
            return "UserDataConfig";
        }

    }
}
