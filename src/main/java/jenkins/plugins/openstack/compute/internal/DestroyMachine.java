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
package jenkins.plugins.openstack.compute.internal;

import jenkins.plugins.openstack.compute.JCloudsCloud;
import org.jenkinsci.plugins.resourcedisposer.Disposable;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.openstack4j.model.compute.Server;

import javax.annotation.Nonnull;
import java.util.NoSuchElementException;

@Restricted(NoExternalUse.class)
public final class DestroyMachine implements Disposable {
    private static final long serialVersionUID = 1L;
    private final @Nonnull String cloudName;
    private final @Nonnull String nodeId;

    /*package*/
    public DestroyMachine(@Nonnull String cloudName, @Nonnull String nodeId) {
        this.cloudName = cloudName;
        this.nodeId = nodeId;
    }

    @Override
    public @Nonnull State dispose() {
        // Cannot be cached as it is scoped to thread
        Openstack os = JCloudsCloud.getByName(cloudName).getOpenstack();
        Server server;
        try {
            server = os.getServerById(nodeId);
        } catch (NoSuchElementException ex) {
            return State.PURGED; // Disappeared in the meantime.
        }
        os.destroyServer(server);
        return State.PURGED; // If not thrown
    }

    @Override
    public @Nonnull String getDisplayName() {
        return "Openstack " + cloudName + " machine " + nodeId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        DestroyMachine that = (DestroyMachine) o;

        if (!cloudName.equals(that.cloudName)) return false;
        return nodeId.equals(that.nodeId);
    }

    @Override
    public int hashCode() {
        int result = cloudName.hashCode();
        result = 31 * result + nodeId.hashCode();
        return result;
    }
}
