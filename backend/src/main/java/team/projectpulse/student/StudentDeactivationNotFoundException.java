package team.projectpulse.student;

public class StudentDeactivationNotFoundException extends RuntimeException {
    public StudentDeactivationNotFoundException() {
        super("Student no longer exists");
    }
}
