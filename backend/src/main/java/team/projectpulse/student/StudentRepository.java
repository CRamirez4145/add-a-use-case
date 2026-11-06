package team.projectpulse.student;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StudentRepository extends JpaRepository<Student, Integer>, JpaSpecificationExecutor<Student> {

    // Read access only. Ownership is resolved through the target's actual course section;
    // neither a caller default nor an administrator authority establishes that scope.
    @Query("""
            select student from Student student
            where student.id = :studentId and
              (student.id = :callerId
               or student.section.course.courseAdmin.id = :callerId
               or exists (select section.sectionId from Section section join section.instructors instructor
                          where section = student.section and instructor.id = :callerId))
            """)
    Optional<Student> findReadableById(Integer studentId, Integer callerId);

    /**
     * Shared serialization point for deactivation and evaluation writes. The caller must hold a transaction.
     * Acquiring a lock does not replace first-level-cache state. Callers must detach the returned
     * target and repeat this scoped locking query before checking enabled, retaining the lock
     * throughout. Do not refresh afterward: Hibernate may issue an unlocked snapshot read.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select student from Student student where student.id = :studentId and student.section.sectionId = :sectionId")
    Optional<Student> findForUpdateByIdAndSection(Integer studentId, Integer sectionId);

    // Find all students in a section
    List<Student> findBySectionSectionId(Integer sectionId);

    List<Student> findByTeamTeamId(Integer teamId);
}
