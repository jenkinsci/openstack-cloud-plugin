package jenkins.plugins.openstack.compute.auth.totp;

import com.google.common.base.Strings;
import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.exceptions.CodeGenerationException;
import dev.samstevens.totp.time.SystemTimeProvider;
import org.apache.commons.codec.binary.Base32;

public class TotpGenerator {

    private final CodeGenerator codeGenerator;
    private final SystemTimeProvider timeProvider;
    private final int counter;
    private final Base32 base32;

    public TotpGenerator() {
        codeGenerator = new DefaultCodeGenerator();
        timeProvider = new SystemTimeProvider();
        counter = 30;
        this.base32 = new Base32();
    }

    public boolean isValidSecret(String totpSecret) {
        return !Strings.isNullOrEmpty(totpSecret)
                && base32.isInAlphabet(totpSecret);
    }

    public String generatePasscode(String totpSecret) {
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
