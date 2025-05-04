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

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * @author ogondza.
 */
public class TokenGroupTest {
    @Test
    public void securityGroups() {
        assertEquals(l("foo"), TokenGroup.from("\"foo\"", ','));
        assertEquals(l("foo bar"), TokenGroup.from("foo bar", ','));
        assertEquals(l("foo bar", "baz"), TokenGroup.from("foo bar,baz", ','));
        assertEquals(l("a,b|c\"\\"), TokenGroup.from("\"a,b|c\\\"\\\\\"", ','));

        // Whitespace trimmed
        assertEquals(l("foo"), TokenGroup.from(" foo\n", ','));
        assertEquals(l("foo", "bar"), TokenGroup.from(" \"foo\" , bar ", ','));
        assertEquals(l("foo", "bar"), TokenGroup.from("\n\"foo\"\n,\nbar\n", ','));
        // Single quotes are not quotes
        assertEquals(l("'foo'"), TokenGroup.from("'foo'", ','));
        assertEquals(l("foo\\bar"), TokenGroup.from("foo\\\\bar", ','));
    }

    @Test
    public void networks() {
        assertEquals(l(l("foo")), TokenGroup.from("\"foo\"", ',', '|'));
        assertEquals(l(l("foo bar")), TokenGroup.from("foo bar", ',', '|'));
        assertEquals(l(l("foo bar"), l("baz")), TokenGroup.from("foo bar,baz", ',', '|'));
        assertEquals(l(l("a,b|c\"\\")), TokenGroup.from("\"a,b|c\\\"\\\\\"", ',', '|'));

        // Whitespace trimmed
        assertEquals(l(l("foo")), TokenGroup.from("\tfoo\n", ',', '|'));
        assertEquals(l(l("foo", "bar")), TokenGroup.from(" \"foo\" | bar ", ',', '|'));
        assertEquals(l(l("foo"), l("bar")), TokenGroup.from("\n\"foo\"\n,\nbar\n", ',', '|'));
        // Single quotes are not quotes
        assertEquals(l(l("'foo'")), TokenGroup.from("'foo'", ',', '|'));
        assertEquals(l(l("foo\\bar")), TokenGroup.from("foo\\\\bar", ',', '|'));

        assertEquals(l(l("foo"), l("bar", "baz")), TokenGroup.from("foo,bar|baz", ',', '|'));
        assertEquals(
                l(l("doo", "foo"), l("xoo"), l("bar", "baz"), l("bax")),
                TokenGroup.from("doo|foo,xoo,bar|baz,bax", ',', '|'));
        assertEquals(l(l("foo bar", "baz")), TokenGroup.from("foo bar|baz", ',', '|'));
    }

    @SafeVarargs
    private static <A> List<A> l(A... args) {
        return new ArrayList<>(Arrays.asList(args));
    }
}
