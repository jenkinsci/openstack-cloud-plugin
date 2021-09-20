package jenkins.plugins.openstack.compute.auth;

import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.exceptions.CodeGenerationException;
import dev.samstevens.totp.time.SystemTimeProvider;

import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

public class TotpGenerator {
    private static final Logger LOGGER = Logger.getLogger(TotpGenerator.class.getName());

    public static Optional<String> generateToken(String totpSecret) {
        CodeGenerator codeGenerator = new DefaultCodeGenerator();
        SystemTimeProvider timeProvider = new SystemTimeProvider();
        long currentBucket = timeProvider.getTime() / 30;
        try {
            return Optional.of(codeGenerator.generate(totpSecret, currentBucket));
        } catch (CodeGenerationException e) {
            LOGGER.log(Level.WARNING, e.getMessage());
            return Optional.empty();
        }
    }
}
