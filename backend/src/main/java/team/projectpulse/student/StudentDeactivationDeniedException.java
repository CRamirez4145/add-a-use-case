package team.projectpulse.student;

import org.springframework.security.access.AccessDeniedException;

public class StudentDeactivationDeniedException extends AccessDeniedException {
    public StudentDeactivationDeniedException() {
        super("You are not authorized to deactivate this student.");
    }
}
