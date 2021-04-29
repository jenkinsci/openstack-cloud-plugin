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

import hudson.Util;
import hudson.util.VariableResolver;
import org.jenkinsci.main.modules.instance_identity.InstanceIdentity;
import jenkins.slaves.JnlpAgentReceiver;
import jenkins.model.Jenkins;
import javax.annotation.Nonnull;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Base64;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * Variable resolver that commits to resolve documented set of variables.
 *
 * This is to ensure that the documentation does not get outdated when code changes.
 *
 * @author ogondza.
 */
/*package*/ class UserDataVariableResolver implements VariableResolver<String> {
    // Exposed for jelly
    /*package*/ static final Map<String, Entry> STUB = new LinkedHashMap<>();
    static {
        stub(
                "SLAVE_JENKINS_HOME",
                "The 'Remote FS Root' for the agent.",
                r -> Util.fixNull(r.opts.getFsRoot())
        );
        stub(
                "SLAVE_JVM_OPTIONS",
                "The 'Custom JVM Options'.",
                r -> Util.fixNull(r.opts.getJvmOptions())
        );
        stub(
                "JENKINS_URL",
                "The URL of the Jenkins instance.",
                r -> r.rootUrl
        );
        stub(
                "SLAVE_JAR_URL",
                "The URL of the executable slave.jar.",
                r -> r.rootUrl + "jnlpJars/slave.jar"
        );
        stub(
                "SLAVE_JNLP_URL",
                "The endpoint URL for the JNLP connection.",
                r -> r.rootUrl + "computer/" + Util.rawEncode(r.serverName) + "/slave-agent.jnlp"
        );
        stub(
                "SLAVE_JNLP_SECRET",
                "The JNLP 'secret' key. This key authorizes to connect Jenkins agent process to a Jenkins computer. Note that referencing this in server user-data (and then using it to launch agent process) makes it exposed to any user or automation permitted to access the Jenkins agent (including running builds). Also, when OpenStack deployment uses metadata service, which it often does, it must be deployed in a way this is guaranteed not to leak. See <a href='https://wiki.openstack.org/wiki/OSSN/OSSN-0074'>OSSN-0074</a> for further details.",
                r ->  Util.fixNull(JnlpAgentReceiver.SLAVE_SECRET.mac(r.serverName))
        );
        stub(
                "SLAVE_LABELS",
                "Labels of the node.",
                r -> r.labelString
        );
        stub(
                "AGENT_NAME",
                "Agent name",
                r -> r.serverName
        );
        stub(
                "JENKINS_INSTANCE_IDENTITY",
                "Jenkins instance identity. See <a href='https://wiki.jenkins.io/display/JENKINS/Instance+Identity'>Instance Identity</a>",
                r -> Util.fixNull(java.util.Base64.getEncoder().encodeToString(org.jenkinsci.main.modules.instance_identity.InstanceIdentity.get().getPublic().getEncoded()))
        );
        stub(
                "AGENT_JNLP_HOST",
                "Jenkins agent jnlp host",
                r -> r.host
        );
        stub(
                "AGENT_JNLP_PORT",
                "Jenkins agent jnlp port",
                r -> Util.fixNull(Integer.toString(Jenkins.get().getSlaveAgentPort()))
        );        
    }
    private static void stub(@Nonnull String name, @Nonnull String doc, @Nonnull ValueCalculator vc) {
        STUB.put(name, new Entry(name, doc, vc));
    }

    private final @Nonnull String rootUrl;
    private final @Nonnull String serverName;
    private final @Nonnull String labelString;
    private final @Nonnull String host;
    private final @Nonnull SlaveOptions opts;

    /*package*/ UserDataVariableResolver(
            @Nonnull String rootUrl, @Nonnull String serverName, @Nonnull String labelString, @Nonnull SlaveOptions opts
    ) {
        this.rootUrl = rootUrl;
        this.serverName = serverName;
        this.labelString = labelString;
        this.opts = opts;
        String host;
        try{
            host = new URI(rootUrl).getHost();
        } catch(URISyntaxException ex) {
            host = rootUrl;
        }
        this.host = host;
    }

    @Override
    public String resolve(@Nonnull String name) {
        Entry entry = STUB.get(name);
        if (entry == null) return null; // Not a variable we can replace. Perhaps user wants this pass through.
        return entry.vc.get(this);
    }

    public static final class Entry {
        private final @Nonnull String name;
        private final @Nonnull String description;
        private final @Nonnull ValueCalculator vc;

        private Entry(@Nonnull String name, @Nonnull String docs, @Nonnull ValueCalculator vc) {
            this.name = name;
            this.description = docs;
            this.vc = vc;
        }

        public @Nonnull String getDescription() {
            return description;
        }

        public @Nonnull String getName() {
            return name;
        }
    }

    @FunctionalInterface
    private interface ValueCalculator {
        @Nonnull String get(@Nonnull UserDataVariableResolver r);
    }
}
