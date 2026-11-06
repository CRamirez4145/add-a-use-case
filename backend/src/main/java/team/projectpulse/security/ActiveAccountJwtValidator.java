package team.projectpulse.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import team.projectpulse.user.UserService;

import java.math.BigDecimal;

/** Supplements signature and timestamp validation with fresh shared-account state. */
public class ActiveAccountJwtValidator implements OAuth2TokenValidator<Jwt> {

    private static final Logger LOGGER = LoggerFactory.getLogger(ActiveAccountJwtValidator.class);

    private static final OAuth2Error INVALID_ACCOUNT = new OAuth2Error(
            "invalid_token", "The account is not active.", null);

    private final UserService userService;

    public ActiveAccountJwtValidator(UserService userService) {
        this.userService = userService;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        Object claim = token.getClaims().get("userId");
        if (!(claim instanceof Number number)) {
            return OAuth2TokenValidatorResult.failure(INVALID_ACCOUNT);
        }

        final int userId;
        try {
            // intValue() would silently truncate fractional or overflowing signed claims.
            userId = new BigDecimal(number.toString()).intValueExact();
        } catch (NumberFormatException | ArithmeticException exception) {
            return OAuth2TokenValidatorResult.failure(INVALID_ACCOUNT);
        }

        try {
            if (this.userService.isAccountActive(userId)) {
                return OAuth2TokenValidatorResult.success();
            }
        } catch (RuntimeException exception) {
            // Exception messages and stack traces may contain SQL parameters or connection secrets.
            // Record only the failure category, never the token, claims, account id, or exception payload.
            LOGGER.error("Account-status lookup failed; bearer authentication denied (failure type: {}).",
                    exception.getClass().getSimpleName());
            return OAuth2TokenValidatorResult.failure(INVALID_ACCOUNT);
        }
        return OAuth2TokenValidatorResult.failure(INVALID_ACCOUNT);
    }
}
