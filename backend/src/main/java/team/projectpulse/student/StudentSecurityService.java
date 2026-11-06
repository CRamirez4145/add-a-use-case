package team.projectpulse.student;

import team.projectpulse.system.UserUtils;
import team.projectpulse.section.SectionSecurityService;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

/**
 * Answers the questions a route asks about one named student: is that student the caller herself
 * ({@code isStudentSelf}), and is the caller an instructor of that student's course section
 * ({@code isCurrentUserInstructorOfStudentSection}).
 *
 * <p>Every check fails closed: an id that names no row, or a link not yet established, is denied rather than
 * throwing. These methods run inside an {@code AuthorizationManager}, upstream of the {@code DispatcherServlet},
 * so an exception thrown here escapes the filter chain as a 500 that also tells the caller which ids exist.
 */
@Service
@Transactional
public class StudentSecurityService {

    private final StudentRepository studentRepository;
    private final UserUtils userUtils;
    private final SectionSecurityService sectionSecurityService;


    public StudentSecurityService(StudentRepository studentRepository, UserUtils userUtils, SectionSecurityService sectionSecurityService) {
        this.studentRepository = studentRepository;
        this.userUtils = userUtils;
        this.sectionSecurityService = sectionSecurityService;
    }

    public boolean canDeactivateInSection(Integer sectionId) {
        return sectionId != null && this.userUtils.getUserId() != null
                && (this.sectionSecurityService.isSectionOwner(sectionId)
                || this.sectionSecurityService.isSectionInstructor(sectionId));
    }

    public boolean canReadStudent(Integer studentId) {
        Integer callerId = this.userUtils.getUserId();
        return studentId != null && callerId != null
                && this.studentRepository.findReadableById(studentId, callerId).isPresent();
    }

    public boolean isStudentSelf(Integer studentId) {
        Integer studentIdFromJwt = this.userUtils.getUserId();
        return studentId.equals(studentIdFromJwt);
    }

    /**
     * Check if the user is the instructor of the section that the student is in
     *
     * @param studentId the id of the student we need to access, this is NOT the current user's Id
     * @return
     */
    public boolean isCurrentUserInstructorOfStudentSection(Integer studentId) {
        Integer instructorIdFromJwt = this.userUtils.getUserId();
        Student student = this.studentRepository.findById(studentId).orElse(null);
        if (student == null) {
            return false;
        }
        return student.getSection().getInstructors().stream()
                .anyMatch(instructor -> instructor.getId().equals(instructorIdFromJwt));
    }

}
