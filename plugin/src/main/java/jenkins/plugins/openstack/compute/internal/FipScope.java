/*
 * The MIT License
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

import net.sf.json.JSONObject;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.openstack4j.model.compute.Server;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

@Restricted(NoExternalUse.class)
public class FipScope {

    public static @Nonnull String getDescription(@Nonnull String instanceFingerprint, @Nonnull Server server) {
        return "{ '" + Openstack.FINGERPRINT_KEY + "': '" + instanceFingerprint + "', 'jenkins-scope': 'server:" + server.getId() + "' }";
    }

    public static @CheckForNull String getServerId(@Nonnull String instanceFingerprint, @CheckForNull String description) {
        String scope = getScopeString(instanceFingerprint, description);
        if (scope == null) return null;

        if (!scope.startsWith("server:")) {
            throw new IllegalArgumentException("Unknown scope of '" + scope + " description " + description);
        }
        return scope.substring(7);
    }

    private static @CheckForNull String getScopeString(@Nonnull String instanceFingerprint, @CheckForNull String description) {
        try {
            JSONObject jsonObject = JSONObject.fromObject(description);

            boolean isOurs = instanceFingerprint.equals(jsonObject.getString(Openstack.FINGERPRINT_KEY));
            if (!isOurs) return null;

            return jsonObject.getString("jenkins-scope");
        } catch (net.sf.json.JSONException ex) {
            // Not our description
            return null;
        }
    }
}
