package team.projectpulse.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.oauth2.jwt.Jwt;
import team.projectpulse.user.UserService;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** UC-STU-deactivate-student: T15a / T15d. Decoder composition is tested with the security wiring. */
@ExtendWith(MockitoExtension.class)
class ActiveAccountJwtValidatorTest {

    @Mock
    private UserService userService;

    private Jwt token(Object userId) {
        Jwt.Builder builder = Jwt.withTokenValue("validator-unit-fixture")
                .header("alg", "RS256").subject("unchanged-subject")
                .claim("authorities", "ROLE_admin ROLE_instructor");
        if (userId != null) {
            builder.claim("userId", userId);
        }
        return builder.build();
    }

    static Stream<Number> integralIds() {
        return Stream.of(7, 7L, BigInteger.valueOf(7), new BigDecimal("7.0"),
                Integer.MAX_VALUE, (long) Integer.MAX_VALUE);
    }

    @ParameterizedTest
    @MethodSource("integralIds")
    void resolvesLosslessIntegralNumericIds(Number id) {
        when(this.userService.isAccountActive(id.intValue())).thenReturn(true);
        assertThat(new ActiveAccountJwtValidator(this.userService).validate(token(id)).hasErrors()).isFalse();
        verify(this.userService).isAccountActive(id.intValue());
    }

    static Stream<Object> malformedIds() {
        return Stream.of(null, "7", "invalid", true, 7.5, new BigDecimal("7.00001"),
                (long) Integer.MAX_VALUE + 1, (long) Integer.MIN_VALUE - 1,
                new BigInteger("9999999999999999999999999"), Double.NaN, Double.POSITIVE_INFINITY);
    }

    @ParameterizedTest
    @MethodSource("malformedIds")
    void rejectsMalformedIdsBeforeAccountLookup(Object id) {
        var result = new ActiveAccountJwtValidator(this.userService).validate(token(id));
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).allSatisfy(error -> assertThat(error.getErrorCode()).isEqualTo("invalid_token"));
        verifyNoInteractions(this.userService);
    }

    @Test
    void rejectsInactiveOrMissingAccount() {
        when(this.userService.isAccountActive(7)).thenReturn(false);
        assertThat(new ActiveAccountJwtValidator(this.userService).validate(token(7)).hasErrors()).isTrue();
    }

    @Test
    void readsAgainAndFailsClosedAfterEarlierSuccess() {
        when(this.userService.isAccountActive(7)).thenReturn(true)
                .thenThrow(new DataAccessResourceFailureException(
                        "sensitive-account@example.test password=secret token=secret",
                        new IllegalStateException("sensitive connection details")));
        Logger logger = (Logger) LoggerFactory.getLogger(ActiveAccountJwtValidator.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            var validator = new ActiveAccountJwtValidator(this.userService);
            assertThat(validator.validate(token(7)).hasErrors()).isFalse();
            assertThat(appender.list).isEmpty();
            assertThat(validator.validate(token(7)).hasErrors()).isTrue();
            verify(this.userService, times(2)).isAccountActive(7);
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getFormattedMessage()).isEqualTo(
                        "Account-status lookup failed; bearer authentication denied (failure type: DataAccessResourceFailureException).");
                assertThat(event.getArgumentArray()).containsExactly("DataAccessResourceFailureException");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void observesDeactivationOnNextValidation() {
        when(this.userService.isAccountActive(7)).thenReturn(true, false);
        var validator = new ActiveAccountJwtValidator(this.userService);
        assertThat(validator.validate(token(7)).hasErrors()).isFalse();
        assertThat(validator.validate(token(7)).hasErrors()).isTrue();
        verify(this.userService, times(2)).isAccountActive(7);
    }
}
