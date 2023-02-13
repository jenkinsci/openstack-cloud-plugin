package jenkins.plugins.openstack.compute.auth.totp;

import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.time.SystemTimeProvider;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class TotpGeneratorTest {
    @Test
    public void thatWrongTotpSecretIsInvalid() {

        assertFalse(TotpGenerator.isValidSecret("ä"));
    }

    @Test
    public void thatInvalidTotpSecretThrowsException() {

        assertThrows(TotpPasscodeGenerationException.class, () -> TotpGenerator.generatePasscode("ä"));
    }

    @Test
    public void thatCorrectTotpSecretIsValid() {
        String secret = "JBSWY3DPEHPK3PXP";

        assertTrue(TotpGenerator.isValidSecret(secret));
    }

    @Test
    public void thatValidPasscodeCanBeGenerated() {
        String secret = "JBSWY3DPEHPK3PXP";
        DefaultCodeVerifier codeVerifier = new DefaultCodeVerifier(new DefaultCodeGenerator(), new SystemTimeProvider());

        String passcode = TotpGenerator.generatePasscode(secret);

        assertTrue(codeVerifier.isValidCode(secret, passcode));
    }
}