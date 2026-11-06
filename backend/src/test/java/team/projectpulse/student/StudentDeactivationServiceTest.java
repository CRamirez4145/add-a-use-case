package team.projectpulse.student;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import team.projectpulse.section.SectionRepository;
import team.projectpulse.system.UserUtils;
import team.projectpulse.user.userinvitation.UserInvitationService;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** UC-STU-deactivate-student T5, T11, T16; endpoint guards are tested separately. */
@ExtendWith(MockitoExtension.class)
class StudentDeactivationServiceTest {
    @Mock StudentRepository studentRepository;
    @Mock SectionRepository sectionRepository;
    @Mock UserInvitationService userInvitationService;
    @Mock PasswordEncoder passwordEncoder;
    @Mock UserUtils userUtils;
    @Mock StudentSecurityService studentSecurityService;
    @Mock EntityManager entityManager;
    @InjectMocks StudentService service;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void transitionsOnceOrReturnsNoOpWithoutSave(boolean enabled) {
        Student student = new Student("username", "First", "Last", "email", "hash", enabled, "student");
        when(studentSecurityService.canDeactivateInSection(1)).thenReturn(true);
        when(studentRepository.findForUpdateByIdAndSection(7, 1)).thenReturn(Optional.of(student));
        var result = service.deactivateStudent(1, 7);
        assertThat(result.outcome()).isEqualTo(enabled ? StudentDeactivation.Outcome.DEACTIVATED : StudentDeactivation.Outcome.ALREADY_DEACTIVATED);
        assertThat(result.message()).isEqualTo(enabled ? "Student has been deactivated." : "Student is already deactivated");
        assertThat(result.student()).isSameAs(student);
        assertThat(student.isEnabled()).isFalse();
        verify(studentRepository, times(enabled ? 1 : 0)).save(student);
    }

    @Test
    void serviceRecheckDeniesBeforeLookingUpTarget() {
        assertThatThrownBy(() -> service.deactivateStudent(1, 7)).isInstanceOf(StudentDeactivationDeniedException.class)
                .hasMessage("You are not authorized to deactivate this student.");
        verifyNoInteractions(studentRepository, entityManager);
    }

    @Test
    void authorizedScopedAbsenceHasExactMessageAndNoWrite() {
        when(studentSecurityService.canDeactivateInSection(1)).thenReturn(true);
        when(studentRepository.findForUpdateByIdAndSection(7, 1)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.deactivateStudent(1, 7)).isInstanceOf(StudentDeactivationNotFoundException.class)
                .hasMessage("Student no longer exists");
        verify(studentRepository, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void changedStatusRejectsAllProfileChanges(boolean enabled) {
        Student student = new Student("original", "First", "Last", "email", "hash", enabled, "student");
        var section = new team.projectpulse.section.Section();
        section.setSectionId(1);
        student.setSection(section);
        Student update = new Student("changed", "Changed", "Changed", "changed", "", !enabled, "student");
        when(studentRepository.findById(7)).thenReturn(Optional.of(student));
        when(studentRepository.findForUpdateByIdAndSection(7, 1)).thenReturn(Optional.of(student));
        assertThatThrownBy(() -> service.updateStudent(7, update)).isInstanceOf(IllegalArgumentException.class);
        assertThat(student.getUsername()).isEqualTo("original");
        assertThat(student.getFirstName()).isEqualTo("First");
        assertThat(student.isEnabled()).isEqualTo(enabled);
        verify(studentRepository, never()).save(any());
    }
}
