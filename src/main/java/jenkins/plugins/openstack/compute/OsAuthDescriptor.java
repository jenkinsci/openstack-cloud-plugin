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

import com.google.common.base.Joiner;
import hudson.Util;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.util.ListBoxModel;
import hudson.util.ReflectionUtils;
import jenkins.plugins.openstack.compute.auth.OpenstackCredential;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.springframework.util.StringUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Helper descriptor abstraction for classes with AJAX methods depending on OS auth parameters.
 *
 * @author ogondza.
 */
@Restricted(NoExternalUse.class)
public abstract class OsAuthDescriptor<DESCRIBABLE extends Describable<DESCRIBABLE>> extends Descriptor<DESCRIBABLE> {

    public OsAuthDescriptor() {
        super();
    }

    public OsAuthDescriptor(Class<DESCRIBABLE> describableClass) {
        super(describableClass);
    }

    /**
     * Get relative <tt>fillDependsOn</tt> offsets to apply.
     */
    public abstract List<String> getAuthFieldsOffsets();

    protected static boolean hasValue(ListBoxModel m, String value) {
        for( final ListBoxModel.Option o : m) {
            if ( Objects.equals(value, o.value)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Add dependencies on credentials for methods annotated with {@link InjectOsAuth} annotation.
     */
    @Override
    public final void calcFillSettings(String field, Map<String, Object> attributes) {
        super.calcFillSettings(field, attributes);

        List<String> deps = new ArrayList<>();
        String fillDependsOn = (String) attributes.get("fillDependsOn");
        if (fillDependsOn != null) {
            deps.addAll(Arrays.asList(fillDependsOn.split(" ")));
        }

        String capitalizedFieldName = StringUtils.capitalize(field);
        String methodName = "doFill" + capitalizedFieldName + "Items";
        Method method = ReflectionUtils.getPublicMethodNamed(getClass(), methodName);

        // Replace direct reference to references to possible relative paths
        if (method.getAnnotation(InjectOsAuth.class) != null) {
            for (String attr: Arrays.asList("endPointUrl","credentialId", "zone")) {
                deps.remove(attr);
                for (String offset : getAuthFieldsOffsets()) {
                    deps.add(offset + "/" + attr);
                }
            }
        }

        if (!deps.isEmpty()) {
            attributes.put("fillDependsOn", Joiner.on(' ').join(deps));
        }
    }

    protected static boolean haveAuthDetails(String endPointUrl, OpenstackCredential openstackCredential, String zone) {
        return  Util.fixEmpty(endPointUrl)!=null && openstackCredential !=null;
    }

    public static String getDefault(String d1, Object d2) {
        d1 = Util.fixEmpty(d1);
        if (d1 != null) return d1;
        if (d2 != null) return Util.fixEmpty(String.valueOf(d2));
        return null;
    }

    /**
     * Method annotation to denote AJAX method accepts OS auth parameters.
     */
    @Retention(RUNTIME)
    @Target({METHOD})
    public @interface InjectOsAuth {}
}
