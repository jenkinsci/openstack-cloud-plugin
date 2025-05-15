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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.openstack4j.model.compute.Server;

public class FipScopeTest {

    private static final String URL = "https://some-quite-long-jenkins-url-to-make-sure-it-fits.acme.com:8080/jenkins";
    private static final String FINGERPRINT = "3919bce9a2f5fc4f730bd6462e23454ecb1fb089";
    public static final String LEGACY_DESCRIPTION =
            "{ 'jenkins-instance': 'https://some-quite-long-jenkins-url-to-make-sure-it-fits.acme.com:8080/jenkins', 'jenkins-scope': 'server:d8eca2df-7795-4069-b2ef-1e2412491345' }";
    public static final String EXPECTED_DESCRIPTION =
            "{ 'jenkins-instance': 'https://some-quite-long-jenkins-url-to-make-sure-it-fits.acme.com:8080/jenkins', 'jenkins-identity': '3919bce9a2f5fc4f730bd6462e23454ecb1fb089', 'jenkins-scope': 'server:d8eca2df-7795-4069-b2ef-1e2412491345' }";
    public static final String ABBREVIATED_DESCRIPTION =
            "{ 'jenkins-identity': '3919bce9a2f5fc4f730bd6462e23454ecb1fb089', 'jenkins-scope': 'server:d8eca2df-7795-4069-b2ef-1e2412491345' }";

    @Test
    public void getDescription() {
        assertEquals(
                EXPECTED_DESCRIPTION,
                FipScope.getDescription(URL, FINGERPRINT, server("d8eca2df-7795-4069-b2ef-1e2412491345")));
        assertThat(
                "Possible length is larger than maximal size of FIP description",
                EXPECTED_DESCRIPTION.length(),
                lessThanOrEqualTo(FipScope.MAX_DESCRIPTION_LENGTH));
    }

    private Server server(String id) {
        Server mock = mock(Server.class);
        when(mock.getId()).thenReturn(id);
        return mock;
    }

    @Test
    public void getServerId() {
        //        assertEquals("d8eca2df-7795-4069-b2ef-1e2412491345", FipScope.getServerId(URL, FINGERPRINT,
        // EXPECTED_DESCRIPTION));
        assertEquals(
                "d8eca2df-7795-4069-b2ef-1e2412491345", FipScope.getServerId(URL, FINGERPRINT, LEGACY_DESCRIPTION));
        assertEquals(
                "d8eca2df-7795-4069-b2ef-1e2412491345",
                FipScope.getServerId(URL, FINGERPRINT, ABBREVIATED_DESCRIPTION));

        assertNull(
                FipScope.getServerId(
                        URL,
                        FINGERPRINT,
                        "{ 'jenkins-instance': 'https://some.other.jenkins.io', 'jenkins-scope': 'server:d8eca2df-7795-4069-b2ef-1e2412491345' }"));
        assertNull(
                FipScope.getServerId(
                        URL,
                        FINGERPRINT,
                        "{ 'jenkins-identity': 'different-than-expected', 'jenkins-scope': 'server:d8eca2df-7795-4069-b2ef-1e2412491345' }"));
        assertNull(FipScope.getServerId(URL, FINGERPRINT, "{ foo: [ 'not', 'the', 'json', 'you', 'expect' ] }"));
        assertNull(FipScope.getServerId(URL, FINGERPRINT, "[ 'not', 'the', 'json', 'you', 'expect' ]"));
        assertNull(FipScope.getServerId(URL, FINGERPRINT, "Human description"));
        assertNull(FipScope.getServerId(URL, FINGERPRINT, ""));
        assertNull(FipScope.getServerId(URL, FINGERPRINT, null));
    }
}
