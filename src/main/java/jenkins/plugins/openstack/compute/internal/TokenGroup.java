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

import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

/**
 * Split string based on delimiters.
 *
 * @author ogondza.
 */
@Restricted(NoExternalUse.class)
public class TokenGroup {
    /**
     * Get list of tokens separated by delim.
     */
    public static @Nonnull List<String> from(@Nonnull final String csv, char delim) {
        List<String> strings = breakInput(csv, new char[] { delim });

        if (strings.size() == 1) return strings;

        ArrayList<String> ret = new ArrayList<>(strings.size() / 2 + 1);
        // Remove delimiters
        for (int i = 0; i < strings.size(); i+=2) {
            ret.add(strings.get(i));
        }

        return ret;
    }

    /**
     * Get list of lists of tokens separated by delimiters.
     *
     * The inner lists are never empty and contain tokens delimited by delim2. The outer list contains list on the inner
     * lists that ware separated by delim1. IOW, it turns "foo<D1>bar<D2>baz<D1>bax" into "((foo),(bar,baz),(bax))"
     */
    public static @Nonnull List<List<String>> from(@Nonnull final String csv, char delim1, char delim2) {
        ArrayList<List<String>> ret = new ArrayList<>();

        List<String> input = breakInput(csv, new char[] { delim1, delim2 });

        ArrayList<String> chunks = new ArrayList<>();
        for (int i = 0; i < input.size(); i+=2) {
            String token = input.get(i);
            chunks.add(token);

            if (i + 1 == input.size()) break; // last element followed by no delimiter

            char delimiter = input.get(i + 1).charAt(0);

            if (delimiter == delim1) {
                ret.add(chunks);
                chunks = new ArrayList<>();
            }
        }
        ret.add(chunks);

        return ret;
    }

    private static @Nonnull List<String> breakInput(String csv, char[] delims) {
        List<Integer> delimiters = new ArrayList<>();
        for (char delim : delims) {
            delimiters.add((int) delim);
        }

        ArrayList<String> fields = new ArrayList<>();

        StringBuilder token = new StringBuilder();
        boolean inQuotes = false;
        boolean escaped = false;
        for (int c : csv.codePoints().toArray()) {
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
                fields.add(new String(new char[] { (char) c }));
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
