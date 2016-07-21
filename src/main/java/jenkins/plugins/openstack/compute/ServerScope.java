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

import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import javax.annotation.Nonnull;

/**
 * Server can be scoped to certain Jenkins entities to constrain its lifetime.
 *
 * Openstack plugin will clean the instance after the condition defined by the scope info attached.
 */
@Restricted(NoExternalUse.class)
public class ServerScope {

    /** Name of the openstack metadata key */
    public static final String METADATA_KEY = "jenkins-scope";

    /**
     * Machine is scoped to Jenkins node of specified name.
     *
     * Once {@link jenkins.model.Jenkins#getNode(String)} fails to get the node, machine is considered leaked.
     */
    public static final String SCOPE_NODE = "node";

    /**
     * Machine is scoped to Jenkins build execution specified by a build tag.
     */
    // TODO define and implement for build wrapper
    //public static final String SCOPE_BUILD = "build";

    /**
     * Assemble scope value. All valid scopes need to be defined in this class.
     */
    public static @Nonnull String scope(@Nonnull String name, @Nonnull String specifier) {
        return name + ":" + specifier;
    }
}
