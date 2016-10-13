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
package jenkins.plugins.openstack;

import com.gargoylesoftware.htmlunit.SgmlPage;
import com.gargoylesoftware.htmlunit.html.DomElement;
import com.gargoylesoftware.htmlunit.html.HtmlButton;
import com.gargoylesoftware.htmlunit.html.HtmlDivision;
import com.gargoylesoftware.htmlunit.html.HtmlForm;
import com.gargoylesoftware.htmlunit.html.HtmlFormUtil;
import com.gargoylesoftware.htmlunit.html.HtmlInput;
import com.gargoylesoftware.htmlunit.html.HtmlPage;

import java.io.IOException;

/**
 * @author ogondza.
 */
public class GlobalConfig {

    public static Cloud addCloud(HtmlPage page) throws IOException {
        HtmlForm f = page.getFormByName("config");
        HtmlFormUtil.getButtonByCaption(f, "Add a new cloud").click();
        page.getAnchorByText("Cloud (OpenStack)").click();
        return new Cloud(f, "//div[@descriptorid='jenkins.plugins.openstack.compute.JCloudsCloud']");
    }

    public static final class Cloud extends Holder {
        public Cloud(HtmlForm f, String wrapper) {
            super(f, wrapper);
        }
    }

    public static final class Template extends Holder {
        public Template(HtmlForm f, String wrapper) {
            super(f, wrapper);
        }
    }

    private static abstract class Holder {
        protected final String wrapper;
        private final Object form;
        private final SgmlPage page;

        public Holder(HtmlForm form, String wrapper) {
            this.form = form;
            this.page = form.getPage();
            this.wrapper = wrapper;
        }

        public void openAdvanced() throws IOException {
            ((HtmlButton) page.getFirstByXPath(wrapper + "//button[text()='Advanced...']")).click();
        }
        
        public String value(String name) {
            return elem(name).value;
        }
        
        public String def(String name) {
            return elem(name).def;
        }
        
        public Element elem(String name) {
            String xpathExpr = wrapper + "//input[@name='_." + name + "']";
            HtmlInput input = page.getFirstByXPath(xpathExpr);
            if (input != null) {
                //System.out.println(page.getParentNode().getTextContent());
                xpathExpr += "/../../following-sibling::tr[@class='validation-error-area']/td/div";
                HtmlDivision validation = page.getFirstByXPath(xpathExpr);
                return new Element(
                        fixEmpty(validation.getTextContent().replace("Inherited value: ", "")),
                        fixEmpty(input.getAttribute("value"))
                );
            }

            return null; // TODO
        }

        private String fixEmpty(String value) {
            return DomElement.ATTRIBUTE_NOT_DEFINED.equals(value) ? null : value;
        }
    }

    private static class Element {
        private final String def;
        private final String value;

        private Element(String def, String value) {
            this.def = def;
            this.value = value;
        }
    }
}
