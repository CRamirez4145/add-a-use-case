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

    /**
     * Shared serialization point for deactivation and evaluation writes. The caller must hold a transaction.
     * An evaluation caller that already loaded the student must also refresh it under this lock before
     * checking enabled: acquiring a lock does not refresh Hibernate's first-level cache.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select student from Student student where student.id = :studentId and student.section.sectionId = :sectionId")
    Optional<Student> findForUpdateByIdAndSection(Integer studentId, Integer sectionId);

    // Find all students in a section
    List<Student> findBySectionSectionId(Integer sectionId);

    List<Student> findByTeamTeamId(Integer teamId);
}
