package team.projectpulse.security.authorizationmanagers;

import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;
import team.projectpulse.student.StudentSecurityService;
import java.util.function.Supplier;

/** Student-details GET only; mutation and report guards intentionally remain separate. */
@Component
public class StudentReadAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {
    private final StudentSecurityService studentSecurityService;

    public StudentReadAuthorizationManager(StudentSecurityService studentSecurityService) {
        this.studentSecurityService = studentSecurityService;
    }

    @Override
    public AuthorizationResult authorize(Supplier<? extends Authentication> authentication, RequestAuthorizationContext context) {
        Integer studentId = PathVariables.readId(context, "studentId");
        return new AuthorizationDecision(studentId != null && this.studentSecurityService.canReadStudent(studentId));
    }
}
