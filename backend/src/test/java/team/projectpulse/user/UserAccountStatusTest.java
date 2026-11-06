package team.projectpulse.user;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserAccountStatusTest {

    @Mock
    private UserRepository userRepository;

    @Test
    void readsPersistedStatusEachTimeIncludingMissingAccount() {
        when(this.userRepository.findAccountEnabledById(7))
                .thenReturn(Optional.of(true), Optional.of(false), Optional.empty());
        var service = new UserService(this.userRepository, null, null);
        assertThat(service.isAccountActive(7)).isTrue();
        assertThat(service.isAccountActive(7)).isFalse();
        assertThat(service.isAccountActive(7)).isFalse();
        verify(this.userRepository, times(3)).findAccountEnabledById(7);
    }

    @Test
    void nullIdentityIsInactiveWithoutQuery() {
        assertThat(new UserService(this.userRepository, null, null).isAccountActive(null)).isFalse();
        verifyNoInteractions(this.userRepository);
    }

    @Test
    void lookupFailureDoesNotReturnAnActiveAccount() {
        when(this.userRepository.findAccountEnabledById(7))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));
        assertThatThrownBy(() -> new UserService(this.userRepository, null, null).isAccountActive(7))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }
}
