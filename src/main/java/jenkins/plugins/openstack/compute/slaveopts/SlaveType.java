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

import com.thoughtworks.xstream.converters.UnmarshallingContext;
import com.thoughtworks.xstream.converters.reflection.ReflectionConverter;
import com.thoughtworks.xstream.converters.reflection.ReflectionProvider;
import com.thoughtworks.xstream.io.HierarchicalStreamReader;
import com.thoughtworks.xstream.mapper.Mapper;
import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.JNLPLauncher;
import jenkins.model.Jenkins;
import jenkins.plugins.openstack.compute.JCloudsCloud;
import jenkins.plugins.openstack.compute.JCloudsSlave;
import jenkins.plugins.openstack.compute.SlaveOptions;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Node launcher factory.
 *
 * @author ogondza.
 */
public abstract class SlaveType extends AbstractDescribableImpl<SlaveType> {
    private static final Logger LOGGER = Logger.getLogger(SlaveType.class.getName());

    /**
     * Create launcher to be used to start the computer.
     */
    public abstract ComputerLauncher createLauncher(@Nonnull JCloudsSlave slave) throws IOException;

    /**
     * Detect the machine is provisioned and can be added to Jenkins for launching.
     * <p>
     * This is guaranteed to be called after server is/was ACTIVE.
     */
    public abstract boolean isReady(@Nonnull JCloudsSlave slave);

    /**
     * Launch nodes wia ssh-slaves plugin.
     */
    public static final class SSH extends SlaveType {

        public static final SlaveType SSH = new SSH();

        @DataBoundConstructor
        public SSH() {
        }

        @Override
        public ComputerLauncher createLauncher(@Nonnull JCloudsSlave slave) throws IOException {
            int maxNumRetries = 5;
            int retryWaitTime = 15;

            SlaveOptions opts = slave.getSlaveOptions();
            String credentialsId = opts.getCredentialsId();
            if (credentialsId == null) {
                throw new JCloudsCloud.ProvisioningFailedException("No ssh credentials selected");
            }

            String publicAddress = slave.getPublicAddressIpv4();
            if (publicAddress == null) {
                throw new IOException("The slave is likely deleted");
            }
            if ("0.0.0.0".equals(publicAddress)) {
                throw new IOException("Invalid host 0.0.0.0, your host is most likely waiting for an ip address.");
            }

            Integer timeout = opts.getStartTimeout();
            timeout = timeout == null ? 0: (timeout / 1000); // Never propagate null - always set some timeout

            return new SSHLauncher(publicAddress, 22, credentialsId, opts.getJvmOptions(), null, "", "", timeout, maxNumRetries, retryWaitTime);
        }

        /**
         * The node is considered ready when ssh port is open.
         */
        @Override
        public boolean isReady(@Nonnull JCloudsSlave slave) {

            // richnou:
            //	Use Ipv4 Method to make sure IPV4 is the default here
            //	OVH cloud provider returns IPV6 as last address, and getPublicAddress returns the last address
            //  The Socket connection test then does not work.
            //	This method could return the address object and work on it, but for now stick to IPV4
            //  for the sake of simplicity
            //
            String publicAddress = slave.getPublicAddressIpv4();
            // Wait until ssh is exposed not to timeout for too long in ssh-slaves launcher
            try {
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(publicAddress, 22), 500);
                socket.close();
                return true;
            } catch (ConnectException | NoRouteToHostException | SocketTimeoutException ex) {
                // Exactly what we are looking for
                LOGGER.log(Level.FINEST, "SSH port at " + publicAddress + " not open (yet)", ex);
                return false;
            } catch (IOException ex) {
                // TODO: General IOException to be understood and handled explicitly
                LOGGER.log(Level.INFO, "SSH port  at " + publicAddress + " not (yet) open?", ex);
                return false;
            } catch (Exception ex) {
                LOGGER.log(Level.WARNING, "SSH probe failed", ex);
                // We have no idea what happen. Log the cause and proceed with the server so it fail fast.
                return true;
            }
        }

        @Extension
        public static final class Desc extends Descriptor<SlaveType> {
        }
    }

    /**
     * Wait for JNLP connection to be made.
     */
    public static final class JNLP extends SlaveType {

        public static final SlaveType JNLP = new JNLP();

        @DataBoundConstructor
        public JNLP() {
        }

        @Override
        public ComputerLauncher createLauncher(@Nonnull JCloudsSlave slave) throws IOException {
            Jenkins.getActiveInstance().addNode(slave);
            return new JNLPLauncher();
        }

        @Override
        public boolean isReady(@Nonnull JCloudsSlave slave) {
            // The address might not be visible at all so let's just wait for connection.
            return slave.getChannel() != null;
        }

        @Extension
        public static final class Desc extends Descriptor<SlaveType> {
        }
    }

    static {
        Jenkins.XSTREAM2.registerConverter(new CompatibilityConverter(
                Jenkins.XSTREAM2.getMapper(),
                Jenkins.XSTREAM2.getReflectionProvider(),
                SlaveType.class
        ));
    }

    // Deserialize configuration that was saved when SlaveType was an enum: "<slaveType>JNLP</slaveType>". Do not
    // intercept the serialization in any other way.
    private static class CompatibilityConverter extends ReflectionConverter {
        private CompatibilityConverter(Mapper mapper, ReflectionProvider reflectionProvider, Class type) {
            super(mapper, reflectionProvider, type);
        }

        @Override public Object unmarshal(HierarchicalStreamReader reader, UnmarshallingContext context) {
            switch (reader.getValue()) {
                case "SSH":
                    return SSH.SSH;
                case "JNLP":
                    return JNLP.JNLP;
                default:
                    return super.unmarshal(reader, context);
            }
        }
    }
}
