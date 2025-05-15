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

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Split string based on delimiters.
 *
 * @author ogondza.
 */
@Restricted(NoExternalUse.class)
public class TokenGroup {

    public static final String STRIP_ALL_WHITESPACE = null;

    /**
     * Get list of tokens separated by delim.
     */
    public static @Nonnull List<String> from(@Nonnull final String input, char delim) {
        List<String> strings = breakInput(input, new char[] {delim});

        ArrayList<String> ret = new ArrayList<>(strings.size() / 2 + 1);
        // Remove delimiters - odd members
        for (int i = 0; i < strings.size(); i += 2) {
            ret.add(clean(strings.get(i)));
        }

        return ret;
    }

    /**
     * Get list of lists of tokens separated by delimiters.
     *
     * The inner lists are never empty and contain tokens delimited by delim2. The outer list contains list on the inner
     * lists that ware separated by delim1. IOW, it turns "foo&lt;D1&gt;bar&lt;D2&gt;baz&lt;D1&gt;bax" into "((foo),(bar,baz),(bax))"
     */
    public static @Nonnull List<List<String>> from(@Nonnull final String input, char delim1, char delim2) {
        ArrayList<List<String>> ret = new ArrayList<>();

        List<String> strings = breakInput(input, new char[] {delim1, delim2});

        ArrayList<String> chunks = new ArrayList<>();
        for (int i = 0; i < strings.size(); i += 2) {
            String token = strings.get(i);
            chunks.add(clean(token));

            if (i + 1 == strings.size()) break; // last element followed by no delimiter

            char delimiter = strings.get(i + 1).charAt(0);

            if (delimiter == delim1) {
                ret.add(chunks);
                chunks = new ArrayList<>();
            }
        }
        ret.add(chunks);

        return ret;
    }

    /**
     * Remove unwanted characters, line breaks, newlines, etc.
     */
    private static String clean(String in) {
        return StringUtils.strip(in, STRIP_ALL_WHITESPACE);
    }

    private static @Nonnull List<String> breakInput(String input, char[] delims) {
        List<Integer> delimiters = new ArrayList<>();
        for (char delim : delims) {
            delimiters.add((int) delim);
        }

        ArrayList<String> fields = new ArrayList<>();

        StringBuilder token = new StringBuilder();
        boolean inQuotes = false;
        boolean escaped = false;
        for (int c : input.codePoints().toArray()) {
            if (c == '\\') {
                if (escaped) {
                    token.append('\\');
                } else {
                    escaped = true;
                    continue; // Not to erase the escape
                }
            } else if (c == '\"' && !escaped) {
                inQuotes = !inQuotes;
            } else if (delimiters.contains(c) && !inQuotes) {
                fields.add(token.toString());
                fields.add(String.valueOf((char) c));
                token.setLength(0);
            } else {
                token.append((char) c);
            }
            escaped = false;
        }
        fields.add(token.toString());

        return fields;
    }
}
