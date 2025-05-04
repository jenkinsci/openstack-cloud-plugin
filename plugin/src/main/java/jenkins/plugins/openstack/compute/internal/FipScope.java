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

import java.util.Objects;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import net.sf.json.JSONObject;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.openstack4j.model.compute.Server;

/**
 * Scope of FIP for deletion.
 *
 * This uses URL+ServerID, do not need to use instance identity as the ServerID is unique.
 */
@Restricted(NoExternalUse.class)
public class FipScope {
    /*package*/ static final int MAX_DESCRIPTION_LENGTH = 250;

    public static @Nonnull String getDescription(
            @Nonnull String url, @Nonnull String identity, @Nonnull Server server) {
        String description = "{ '" + Openstack.FINGERPRINT_KEY_URL + "': '" + url + "', '"
                + Openstack.FINGERPRINT_KEY_FINGERPRINT + "': '" + identity
                + "', 'jenkins-scope': 'server:" + server.getId() + "' }";

        if (description.length() < MAX_DESCRIPTION_LENGTH) return description;

        // Avoid URL that is used only for human consumption anyway
        return "{ '" + Openstack.FINGERPRINT_KEY_FINGERPRINT + "': '" + identity + "', 'jenkins-scope': 'server:"
                + server.getId() + "' }";
    }

    public static @CheckForNull String getServerId(
            @Nonnull String url, @Nonnull String identity, @CheckForNull String description) {
        String scope = getScopeString(url, identity, description);
        if (scope == null) return null;

        if (!scope.startsWith("server:")) {
            throw new IllegalArgumentException("Unknown scope of '" + scope + " description " + description);
        }
        return scope.substring(7);
    }

    private static @CheckForNull String getScopeString(
            @Nonnull String url, @Nonnull String identity, @CheckForNull String description) {
        try {
            JSONObject jsonObject = JSONObject.fromObject(description);

            // Not ours
            String attachedIdentity = jsonObject.optString(Openstack.FINGERPRINT_KEY_FINGERPRINT, null);
            String attachedUrl = jsonObject.optString(Openstack.FINGERPRINT_KEY_URL, null);
            if (attachedIdentity == null && attachedUrl == null) return null;
            if (attachedIdentity != null && !Objects.equals(attachedIdentity, identity)) return null;
            if (attachedUrl != null && !Objects.equals(attachedUrl, url)) return null;

            return jsonObject.getString("jenkins-scope");
        } catch (net.sf.json.JSONException ex) {
            // Not our description
            return null;
        }
    }
}
