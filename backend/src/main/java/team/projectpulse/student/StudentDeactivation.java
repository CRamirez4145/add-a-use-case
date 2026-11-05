package team.projectpulse.student;

public record StudentDeactivation(Outcome outcome, Student student) {
    public enum Outcome { DEACTIVATED, ALREADY_DEACTIVATED }

    public String message() {
        return this.outcome == Outcome.DEACTIVATED
                ? "Student has been deactivated." : "Student is already deactivated";
    }
}
