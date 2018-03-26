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
package jenkins.plugins.openstack.compute.slaveopts;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHAuthenticator;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernameListBoxModel;
import com.trilead.ssh2.Connection;
import hudson.Extension;
import hudson.Util;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.ItemGroup;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.security.ACL;
import hudson.security.AccessControlled;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.JNLPLauncher;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import jenkins.plugins.openstack.compute.JCloudsCloud;
import jenkins.plugins.openstack.compute.JCloudsSlave;
import jenkins.plugins.openstack.compute.SlaveOptions;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.Serializable;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Node launcher factory.
 *
 * @author ogondza.
 */
@Restricted(NoExternalUse.class)
public abstract class LauncherFactory extends AbstractDescribableImpl<LauncherFactory> implements Serializable {
    private static final long serialVersionUID = -8322868020681278525L;
    private static final Logger LOGGER = Logger.getLogger(LauncherFactory.class.getName());

    /**
     * Create launcher to be used to start the computer.
     */
    public abstract ComputerLauncher createLauncher(@Nonnull JCloudsSlave slave) throws IOException;

    /**
     * Detect the machine is provisioned and can be added to Jenkins for launching.
     *
     * This is guaranteed to be called after server is/was ACTIVE.
     *
     * @param slave Slave we are waiting to be ready.
     * @return null if it is ready or string cause if it is not. The cause will be reported alongside the timeout exception
     *      if this will not become ready in time.
     * @throws jenkins.plugins.openstack.compute.JCloudsCloud.ProvisioningFailedException If the provisioning needs to
     *      be aborted right away without waiting for the timeout.
     */
    public abstract @CheckForNull String isWaitingFor(@Nonnull JCloudsSlave slave) throws JCloudsCloud.ProvisioningFailedException;

    /**
     * Launch nodes via ssh-slaves plugin.
     */
    public static final class SSH extends LauncherFactory {
        private static final long serialVersionUID = -1108865485314632255L;

        private final String credentialsId;
        private final String javaPath;

        public String getCredentialsId() {
            return credentialsId;
        }

        public String getJavaPath() {
            return javaPath;
        }

        @DataBoundConstructor
        public SSH(String credentialsId, String javaPath) {
            this.credentialsId = credentialsId;
            this.javaPath = Util.fixEmptyAndTrim(javaPath);
        }

        public SSH(String credentialsId) {
            this(credentialsId, null);
        }

        @Override
        public ComputerLauncher createLauncher(@Nonnull JCloudsSlave slave) throws IOException {
            int maxNumRetries = 5;
            int retryWaitTime = 15;

            if (credentialsId == null) {
                throw new JCloudsCloud.ProvisioningFailedException("No ssh credentials specified for " + slave.getNodeName());
            }

            String publicAddress = slave.getPublicAddressIpv4();

            final SlaveOptions opts = slave.getSlaveOptions();
            Integer timeout = opts.getStartTimeout();
            timeout = timeout == null ? 0: (timeout / 1000); // Never propagate null - always set some timeout

            return new SSHLauncher(publicAddress, 22, credentialsId, opts.getJvmOptions(), javaPath, "", "", timeout, maxNumRetries, retryWaitTime);
        }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            SSH ssh = (SSH) o;

            return Objects.equals(credentialsId, ssh.credentialsId) && Objects.equals(javaPath, ssh.javaPath);
        }

        @Override public int hashCode() {
            return Objects.hash(credentialsId, javaPath);
        }

        @Override public String toString() {
            return "LauncherFactory.SSH: credId:" + credentialsId + ", javaPath:" + javaPath;
        }

        /**
         * The node is considered ready when ssh port is open.
         */
        @Override
        public @CheckForNull String isWaitingFor(@Nonnull JCloudsSlave slave) {

            // richnou:
            //	Use Ipv4 Method to make sure IPV4 is the default here
            //	OVH cloud provider returns IPV6 as last address, and getPublicAddress returns the last address
            //  The Socket connection test then does not work.
            //	This method could return the address object and work on it, but for now stick to IPV4
            //  for the sake of simplicity

            String publicAddress;
            try {
                publicAddress = slave.getPublicAddressIpv4();
            } catch (NoSuchElementException ex) {
                throw new JCloudsCloud.ProvisioningFailedException(ex.getMessage(), ex);
            }

            // Wait until ssh is exposed not to timeout for too long in ssh-slaves launcher
            try {
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(publicAddress, 22), 500);
                socket.close();
                return null;
            } catch (ConnectException | NoRouteToHostException | SocketTimeoutException ex) {
                // Exactly what we are looking for
                String msg = "SSH port at " + publicAddress + " not open (yet)";
                LOGGER.log(Level.FINEST, msg, ex);
                return msg;
            } catch (IOException ex) {
                // TODO: General IOException to be understood and handled explicitly
                String msg = "SSH port at " + publicAddress + " does not seem to respond correctly: " + ex.getMessage();
                LOGGER.log(Level.INFO, msg, ex);
                return msg;
            } catch (Exception ex) {
                LOGGER.log(Level.WARNING, "SSH probe failed", ex);
                // We have no idea what happen. Log the cause and proceed with the server so it fail fast.
                return null;
            }
        }

        @Symbol("ssh")
        @Extension
        public static final class Desc extends Descriptor<LauncherFactory> {
            @Restricted(DoNotUse.class)
            public ListBoxModel doFillCredentialsIdItems(@AncestorInPath ItemGroup context) {
                if (!(context instanceof AccessControlled ? (AccessControlled) context : Jenkins.getActiveInstance()).hasPermission(Computer.CONFIGURE)) {
                    return new ListBoxModel();
                }
                List<StandardUsernameCredentials> credentials = CredentialsProvider.lookupCredentials(
                        StandardUsernameCredentials.class, context, ACL.SYSTEM, SSHLauncher.SSH_SCHEME
                );
                return new StandardUsernameListBoxModel()
                        .withMatching(SSHAuthenticator.matcher(Connection.class), credentials)
                        .withEmptySelection()
                ;
            }
        }
    }

    /**
     * Wait for JNLP connection to be made.
     */
    public static final class JNLP extends LauncherFactory {
        private static final long serialVersionUID = -1112849796889317240L;

        public static final LauncherFactory JNLP = new JNLP();

        //For the purposes of Declarative Pipeline functionality
        @DataBoundConstructor
        public JNLP(){}

        @Override
        public ComputerLauncher createLauncher(@Nonnull JCloudsSlave slave) throws IOException {
            Jenkins.getActiveInstance().addNode(slave);
            return new JNLPLauncher();
        }

        @Override
        public @CheckForNull String isWaitingFor(@Nonnull JCloudsSlave slave) {
            // The address might not be visible at all so let's just wait for connection.
            return slave.getChannel() != null ? null : "JNLP connection was not established yet";
        }

        @Override
        public int hashCode() {
            return 31;
        }

        @Override
        public boolean equals(Object obj) {
            return obj != null && getClass() == obj.getClass();
        }

        private Object readResolve() {
            return JNLP; // Let's avoid creating instances where we can
        }

        @Symbol("jnlp")
        @Extension
        public static final class Desc extends Descriptor<LauncherFactory> {
            @Override
            public LauncherFactory newInstance(StaplerRequest req, @Nonnull JSONObject formData) throws FormException {
                return JNLP; // Let's avoid creating instances where we can
            }
        }
    }

    /**
     * No slave type specified. This exists only as a field in UI dropdown to be read by stapler and converted to plain old null.
     */
    // Therefore, noone refers to this as a symbol or tries to serialize it, ever.
    @SuppressWarnings({"unused", "serial"})
    public static final class Unspecified extends LauncherFactory {
        private Unspecified() {} // Never instantiate

        @Override public ComputerLauncher createLauncher(@Nonnull JCloudsSlave slave) throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override public @CheckForNull String isWaitingFor(@Nonnull JCloudsSlave slave) {
            throw new UnsupportedOperationException();
        }

        @Extension(ordinal = Double.MAX_VALUE)
        public static final class Desc extends Descriptor<LauncherFactory> {
            @Override public @Nonnull String getDisplayName() {
                return "Inherit / Override later";
            }

            @Override public LauncherFactory newInstance(StaplerRequest req, @Nonnull JSONObject formData) throws FormException {
                return null; // Make sure this is never instantiated and hence will be treated as absent
            }
        }
    }
}
