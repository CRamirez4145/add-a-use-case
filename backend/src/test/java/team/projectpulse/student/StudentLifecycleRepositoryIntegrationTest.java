package team.projectpulse.student;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import team.projectpulse.AbstractIntegrationTest;
import team.projectpulse.user.UserRepository;
import team.projectpulse.user.UserService;

import static org.assertj.core.api.Assertions.assertThat;

/** MySQL proof of the shared primitives; command/evaluation races are covered by T6/T18 later. */
class StudentLifecycleRepositoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private StudentRepository studentRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserService userService;
    @Autowired
    private EntityManager entityManager;

    @Test
    void scopedLookupLocksOnlyStudentInNamedSection() {
        Student student = this.studentRepository.findAll().getFirst();
        Integer sectionId = student.getSection().getSectionId();
        this.entityManager.clear();

        Student locked = this.studentRepository.findForUpdateByIdAndSection(student.getId(), sectionId).orElseThrow();
        assertThat(this.entityManager.getLockMode(locked)).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
        assertThat(this.studentRepository.findForUpdateByIdAndSection(student.getId(), Integer.MAX_VALUE)).isEmpty();
        assertThat(this.studentRepository.findForUpdateByIdAndSection(Integer.MAX_VALUE, sectionId)).isEmpty();
    }

    @Test
    void accountStatusReadsDatabaseRatherThanManagedStudentOrInstructor() {
        // The bulk update deliberately leaves each already-managed entity stale.
        // Scalar queries must still read enabled from the shared table for both subtypes.
        var accounts = this.userRepository.findAll();
        assertThat(accounts).anyMatch(account -> account instanceof Student);
        assertThat(accounts).anyMatch(account -> !(account instanceof Student));
        for (var account : accounts) {
            boolean original = account.isEnabled();
            this.entityManager.createQuery("update PeerEvaluationUser account set account.enabled = :enabled where account.id = :id")
                    .setParameter("enabled", !original).setParameter("id", account.getId()).executeUpdate();
            assertThat(account.isEnabled()).isEqualTo(original);
            assertThat(this.userService.isAccountActive(account.getId())).isEqualTo(!original);
        }
        assertThat(this.userService.isAccountActive(Integer.MAX_VALUE)).isFalse();
    }
}
