package jenkins.plugins.openstack.compute.auth.totp;

import com.google.common.base.Strings;
import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.exceptions.CodeGenerationException;
import dev.samstevens.totp.time.SystemTimeProvider;
import org.apache.commons.codec.binary.Base32;

public class TotpGenerator {

    private TotpGenerator() {}

    private static final CodeGenerator codeGenerator = new DefaultCodeGenerator();
    private static final SystemTimeProvider timeProvider = new SystemTimeProvider();
    private static final int counter = 30;
    private static final Base32 base32  = new Base32();
    public static boolean isValidSecret(String totpSecret) {
        return !Strings.isNullOrEmpty(totpSecret)
                && base32.isInAlphabet(totpSecret);
    }

    public static String generatePasscode(String totpSecret) {
        if (!isValidSecret(totpSecret)) {
            throw new TotpPasscodeGenerationException("The TOTP secret is invalid.");
        }
        long currentBucket = timeProvider.getTime() / counter;
        try {
            return codeGenerator.generate(totpSecret, currentBucket);
        } catch (CodeGenerationException e) {
            throw new TotpPasscodeGenerationException(e.getMessage(), e);
        }
    }

}
