package jenkins.model;

import javax.annotation.CheckForNull;

public class TestJenkins {
    private static Jenkins.JenkinsHolder originalHolder;

    /**
     * Sets the value that {@link Jenkins#getInstance()} will return during a test.
     * 
     * @param mockJenkinsOrNull Null to restore the original value, otherwise
     *                          (typically) a mock {@link Jenkins} instance.
     */
    public static void setJenkinsInstance(Jenkins mockJenkinsOrNull) {
        Jenkins.JenkinsHolder current = Jenkins.HOLDER;
        if (!(current == null || current instanceof OurJenkinsHolder)) {
            originalHolder = current;
        }
        if (mockJenkinsOrNull == null) {
            Jenkins.HOLDER = originalHolder;
        } else {
            Jenkins.HOLDER = new OurJenkinsHolder(mockJenkinsOrNull);
        }
    }

    private static class OurJenkinsHolder implements Jenkins.JenkinsHolder {
        private final Jenkins theInstance;

        public OurJenkinsHolder(Jenkins theInstance) {
            this.theInstance = theInstance;
        }

        public @CheckForNull Jenkins getInstance() {
            return theInstance;
        }
    };
}
