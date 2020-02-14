/*
 *
 *  * The MIT License
 *  *
 *  * Copyright (c) Red Hat, Inc.
 *  *
 *  * Permission is hereby granted, free of charge, to any person obtaining a copy
 *  * of this software and associated documentation files (the "Software"), to deal
 *  * in the Software without restriction, including without limitation the rights
 *  * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  * copies of the Software, and to permit persons to whom the Software is
 *  * furnished to do so, subject to the following conditions:
 *  *
 *  * The above copyright notice and this permission notice shall be included in
 *  * all copies or substantial portions of the Software.
 *  *
 *  * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  * THE SOFTWARE.
 *
 */
package jenkins.plugins.openstack.compute;

import hudson.EnvVars;
import hudson.slaves.CommandLauncher;
import hudson.slaves.ComputerLauncher;
import jenkins.model.Jenkins;
import jenkins.plugins.openstack.compute.slaveopts.LauncherFactory;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;

public class TestCommandLauncherFactory extends LauncherFactory {
    private static final long serialVersionUID = -1430772041065953918L;
    private final @Nonnull String command;

    public TestCommandLauncherFactory() {
        this("java -jar '%s'");
    }

    public TestCommandLauncherFactory(@Nonnull String command) {
        this.command = command;
    }

    @Override
    public ComputerLauncher createLauncher(@Nonnull JCloudsSlave slave) {
        String command = String.format(this.command, getAbsolutePath());
        return new CommandLauncher(command, new EnvVars());
    }

    private static @Nonnull String getAbsolutePath() {
        try {
            return new File(Jenkins.get().getJnlpJars("slave.jar").getURL().toURI()).getAbsolutePath();
        } catch (URISyntaxException | IOException e) {
            throw new Error(e);
        }
    }

    @Override
    public @CheckForNull String isWaitingFor(@Nonnull JCloudsSlave slave) throws JCloudsCloud.ProvisioningFailedException {
        return null;
    }
}
