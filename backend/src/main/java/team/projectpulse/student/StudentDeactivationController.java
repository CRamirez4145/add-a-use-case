package team.projectpulse.student;

import org.springframework.web.bind.annotation.*;
import team.projectpulse.student.converter.StudentToStudentDtoConverter;
import team.projectpulse.student.dto.StudentDto;
import team.projectpulse.system.Result;
import team.projectpulse.system.StatusCode;

@RestController
@RequestMapping("${api.endpoint.base-url}/sections/{sectionId}/students/{studentId}/deactivation")
public class StudentDeactivationController {
    private final StudentService studentService;
    private final StudentToStudentDtoConverter converter;

    public StudentDeactivationController(StudentService studentService, StudentToStudentDtoConverter converter) {
        this.studentService = studentService;
        this.converter = converter;
    }

    public record DeactivationData(StudentDeactivation.Outcome outcome, StudentDto student) {}

    @PostMapping
    public Result deactivate(@PathVariable Integer sectionId, @PathVariable Integer studentId) {
        StudentDeactivation result = this.studentService.deactivateStudent(sectionId, studentId);
        return new Result(true, StatusCode.SUCCESS, result.message(),
                new DeactivationData(result.outcome(), this.converter.convert(result.student())));
    }
}
