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

import org.junit.Test;
import org.openstack4j.model.compute.Server;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class FipScopeTest {

    private static final String FNGRPRNT = "https://some-quite-long-jenkins-url-to-make-sure-it-fits.acme.com:8080/jenkins";
    public static final String REALISTIC_EXAMPLE
            = "{ 'jenkins-instance': 'https://some-quite-long-jenkins-url-to-make-sure-it-fits.acme.com:8080/jenkins', 'jenkins-scope': 'server:d8eca2df-7795-4069-b2ef-1e2412491345' }";

    @Test
    public void getDescription() {
        assertEquals(REALISTIC_EXAMPLE, FipScope.getDescription(FNGRPRNT, server("d8eca2df-7795-4069-b2ef-1e2412491345")));
        assertThat("Possible length is larger than maximal size of FIP description", REALISTIC_EXAMPLE.length(), lessThanOrEqualTo(250));
    }

    private Server server(String id) {
        Server mock = mock(Server.class);
        when(mock.getId()).thenReturn(id);
        return mock;
    }

    @Test
    public void getServerId() {
        assertEquals("d8eca2df-7795-4069-b2ef-1e2412491345", FipScope.getServerId(FNGRPRNT, REALISTIC_EXAMPLE));

        assertNull(FipScope.getServerId(FNGRPRNT, "{ 'jenkins-instance': 'https://some.other.jenkins.io', 'jenkins-scope': 'server:d8eca2df-7795-4069-b2ef-1e2412491345' }"));
        assertNull(FipScope.getServerId(FNGRPRNT, "{ [ 'not', 'the', 'json', 'you', 'expect' ] }"));
        assertNull(FipScope.getServerId(FNGRPRNT, "[ 'not', 'the', 'json', 'you', 'expect' ]"));
        assertNull(FipScope.getServerId(FNGRPRNT, "Human description"));
        assertNull(FipScope.getServerId(FNGRPRNT, ""));
        assertNull(FipScope.getServerId(FNGRPRNT, null));
    }
}
