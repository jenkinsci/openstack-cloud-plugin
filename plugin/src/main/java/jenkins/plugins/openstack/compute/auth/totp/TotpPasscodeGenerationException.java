package jenkins.plugins.openstack.compute.auth.totp;

public class TotpPasscodeGenerationException extends RuntimeException {

    public TotpPasscodeGenerationException(String message) {
        super(message);
    }

    public TotpPasscodeGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
