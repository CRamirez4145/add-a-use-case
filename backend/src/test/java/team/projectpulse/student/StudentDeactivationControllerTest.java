package team.projectpulse.student;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import team.projectpulse.student.converter.StudentToStudentDtoConverter;
import team.projectpulse.section.Section;
import team.projectpulse.system.exception.ExceptionHandlerAdvice;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class StudentDeactivationControllerTest {
    private final StudentService service = mock(StudentService.class);
    private final org.springframework.test.web.servlet.MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new StudentDeactivationController(service, new StudentToStudentDtoConverter()))
            .addPlaceholderValue("api.endpoint.base-url", "/api/v1")
            .setControllerAdvice(new ExceptionHandlerAdvice()).build();
    private static final String URL = "/api/v1/sections/1/students/7/deactivation";

    @ParameterizedTest
    @EnumSource(StudentDeactivation.Outcome.class)
    void mapsBothHandledOutcomesInsideData(StudentDeactivation.Outcome outcome) throws Exception {
        Student student = new Student();
        student.setId(7);
        Section section = new Section();
        section.setSectionId(1);
        student.setSection(section);
        var result = new StudentDeactivation(outcome, student);
        when(service.deactivateStudent(1, 7)).thenReturn(result);
        mvc.perform(post(URL)).andExpect(status().isOk())
                .andExpect(jsonPath("$.flag").value(true))
                .andExpect(jsonPath("$.message").value(result.message()))
                .andExpect(jsonPath("$.data.outcome").value(outcome.name()))
                .andExpect(jsonPath("$.data.student.id").value(7))
                .andExpect(jsonPath("$.data.student.enabled").value(false));
    }

    @Test
    void mapsServiceDenialWithoutStatusDisclosure() throws Exception {
        when(service.deactivateStudent(1, 7)).thenThrow(new StudentDeactivationDeniedException());
        mvc.perform(post(URL)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.flag").value(false)).andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("You are not authorized to deactivate this student."))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void mapsScopedAbsence() throws Exception {
        when(service.deactivateStudent(1, 7)).thenThrow(new StudentDeactivationNotFoundException());
        mvc.perform(post(URL)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.flag").value(false)).andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("Student no longer exists"))
                .andExpect(jsonPath("$.data").isEmpty());
    }
}
