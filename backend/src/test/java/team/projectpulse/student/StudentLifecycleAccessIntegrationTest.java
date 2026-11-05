package team.projectpulse.student;

import jakarta.persistence.EntityManager;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import team.projectpulse.AbstractIntegrationTest;
import team.projectpulse.instructor.Instructor;
import team.projectpulse.section.SectionRepository;
import team.projectpulse.system.exception.ObjectNotFoundException;
import team.projectpulse.user.UserRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** UC-STU-deactivate-student: corrected T2, T24/T25, plus command refusal/regression flows. */
class StudentLifecycleAccessIntegrationTest extends AbstractIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired StudentRepository students;
    @Autowired SectionRepository sections;
    @Autowired StudentService service;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtDecoder decoder;
    private static final String BASE = "/api/v1";

    @AfterEach
    void clearIdentity() { SecurityContextHolder.clearContext(); }

    private Student target() { return (Student) users.findByUsername("j.smith@abc.edu").orElseThrow(); }
    private Instructor owner() { return (Instructor) users.findByUsername("b.wei@abc.edu").orElseThrow(); }
    private String details(Student student) { return BASE + "/students/" + student.getId(); }
    private String command(Student student) {
        return BASE + "/sections/" + student.getSection().getSectionId() + "/students/" + student.getId() + "/deactivation";
    }
    private String login(String username) throws Exception {
        var response = mvc.perform(post(BASE + "/users/login").with(httpBasic(username, "123456")))
                .andExpect(status().isOk()).andReturn().getResponse();
        return "Bearer " + new JSONObject(response.getContentAsString()).getJSONObject("data").getString("token");
    }
    private void unassignOwner(Student student, boolean noDefault) {
        Instructor owner = owner();
        student.getSection().getInstructors().remove(owner);
        owner.getSections().remove(student.getSection());
        owner.setDefaultSection(noDefault ? null : sections.findAll().stream()
                .filter(section -> !section.getSectionId().equals(student.getSection().getSectionId())).findFirst().orElseThrow());
        em.flush();
    }
    private Map<String, Object> history() {
        return Map.of("activities", jdbc.queryForList("select * from activity order by activity_id"),
                "evaluations", jdbc.queryForList("select * from peer_evaluation order by peer_evaluation_id"),
                "ratings", jdbc.queryForList("select * from rating order by rating_id"));
    }
    private Map<String, Object> account(int id) {
        return new LinkedHashMap<>(jdbc.queryForMap("select * from peer_evaluation_user where id = ?", id));
    }

    @ParameterizedTest
    @ValueSource(strings = {"assigned", "owner-other-default", "owner-no-default"})
    void detailsThenDeactivationUsesActualSectionAndChangesOnlyEnabled(String caller) throws Exception {
        Student student = target();
        if (!caller.equals("assigned")) unassignOwner(student, caller.equals("owner-no-default"));
        String token = login(caller.equals("assigned") ? "b.gates@abc.edu" : "b.wei@abc.edu");
        var before = account(student.getId());
        var callerBefore = account(owner().getId());
        var historyBefore = history();
        var membershipBefore = List.copyOf(student.getSection().getInstructors());
        var read = mvc.perform(get(details(student)).header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.sectionId").value(student.getSection().getSectionId()))
                .andReturn().getResponse();
        int sectionId = new JSONObject(read.getContentAsString()).getJSONObject("data").getInt("sectionId");
        mvc.perform(post(BASE + "/sections/" + sectionId + "/students/" + student.getId() + "/deactivation")
                        .header("Authorization", token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Student has been deactivated."))
                .andExpect(jsonPath("$.data.outcome").value("DEACTIVATED"))
                .andExpect(jsonPath("$.data.student.enabled").value(false));
        em.flush();
        var after = account(student.getId());
        assertThat(after.get("enabled")).isEqualTo(false);
        before.remove("enabled"); after.remove("enabled");
        assertThat(after).isEqualTo(before);
        assertThat(history()).isEqualTo(historyBefore);
        assertThat(account(owner().getId())).isEqualTo(callerBefore);
        assertThat(student.getSection().getInstructors()).containsExactlyInAnyOrderElementsOf(membershipBefore);
        mvc.perform(post(command(student)).header("Authorization", token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Student is already deactivated"))
                .andExpect(jsonPath("$.data.outcome").value("ALREADY_DEACTIVATED"));
        assertThat(history()).isEqualTo(historyBefore);
    }

    @Test
    void ownerReadDoesNotGrantProfileDeletionOrReportPermissions() throws Exception {
        Student student = target();
        unassignOwner(student, false);
        String token = login("b.wei@abc.edu");
        mvc.perform(get(details(student)).header("Authorization", token)).andExpect(status().isOk());
        var before = account(student.getId());
        mvc.perform(put(details(student)).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete(details(student)).header("Authorization", token)).andExpect(status().isForbidden());
        for (String path : List.of("/evaluations/students/" + student.getId() + "?startWeek=2023-W32&endWeek=2023-W32",
                "/evaluations/students/" + student.getId() + "/week/2023-W32",
                "/evaluations/students/" + student.getId() + "/week/2023-W32/details",
                "/evaluations/evaluators/" + student.getId() + "/week/2023-W32",
                "/evaluations/sections/" + student.getSection().getSectionId() + "/week/2023-W32",
                "/sections/" + student.getSection().getSectionId())) {
            mvc.perform(get(BASE + path).header("Authorization", token)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value("No permission."));
        }
        mvc.perform(post(BASE + "/activities/search").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"studentId\":\"" + student.getId() + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content").isEmpty());
        // Course ownership already establishes course-instructor membership; no rubric rule is changed.
        mvc.perform(get(BASE + "/rubrics/" + student.getSection().getRubric().getRubricId()).header("Authorization", token))
                .andExpect(status().isOk());
        assertThat(account(student.getId())).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void foreignAdminAndUnassignedInstructorCannotReadOrDeactivate(boolean enabled) throws Exception {
        Student student = target(); student.setEnabled(enabled);
        Instructor bill = (Instructor) users.findByUsername("b.gates@abc.edu").orElseThrow();
        student.getSection().getInstructors().remove(bill); bill.getSections().remove(student.getSection()); em.flush();
        for (String caller : List.of("t.cook@abc.edu", "b.gates@abc.edu")) {
            String token = login(caller);
            mvc.perform(get(details(student)).param("sectionId", "3").header("Authorization", token))
                    .andExpect(status().isForbidden());
            for (int id : List.of(student.getId(), Integer.MAX_VALUE)) {
                mvc.perform(post(BASE + "/sections/" + student.getSection().getSectionId() + "/students/" + id + "/deactivation")
                        .header("Authorization", token)).andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.message").value("You are not authorized to deactivate this student."))
                        .andExpect(jsonPath("$.data").isEmpty());
            }
        }
        assertThat(student.isEnabled()).isEqualTo(enabled);
    }

    @Test
    void selfReadRemainsAllowedButSelfDeactivationIsDenied() throws Exception {
        Student student = target(); String token = login(student.getUsername());
        mvc.perform(get(details(student)).header("Authorization", token)).andExpect(status().isOk());
        mvc.perform(post(command(student)).header("Authorization", token)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("You are not authorized to deactivate this student."));
        assertThat(student.isEnabled()).isTrue();
    }

    @Test
    void scopedServiceLookupRefusesForeignStudentAndDeletedAuthorizedTarget() throws Exception {
        String token = login("b.gates@abc.edu");
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(decoder.decode(token.substring(7))));
        Student foreign = students.findAll().stream().filter(student -> student.getSection().getCourse().getCourseAdmin()
                .getUsername().equals("t.cook@abc.edu")).findFirst().orElseThrow();
        assertThatThrownBy(() -> service.findStudentById(foreign.getId())).isInstanceOf(ObjectNotFoundException.class);
    }

    @Test
    void authorizedDetailsTargetDeletedBeforeServiceReadIsNotFound() throws Exception {
        Student student = new Student("lifecycle-fixture@example.test", "Temporary", "Student",
                "lifecycle-fixture@example.test", "unused", true, "student");
        var section = target().getSection();
        section.addStudent(student);
        students.saveAndFlush(student);
        String token = login("b.gates@abc.edu");
        mvc.perform(get(details(student)).header("Authorization", token)).andExpect(status().isOk());
        int id = student.getId();
        section.getStudents().remove(student);
        students.delete(student); em.flush();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(decoder.decode(token.substring(7))));
        assertThatThrownBy(() -> service.findStudentById(id)).isInstanceOf(ObjectNotFoundException.class);
    }

    @Test
    void permissionsRevokedAfterDetailsReadAreCheckedAgain() throws Exception {
        Student student = target(); String token = login("b.gates@abc.edu");
        mvc.perform(get(details(student)).header("Authorization", token)).andExpect(status().isOk());
        Instructor bill = (Instructor) users.findByUsername("b.gates@abc.edu").orElseThrow();
        student.getSection().getInstructors().remove(bill); bill.getSections().remove(student.getSection()); em.flush();
        mvc.perform(post(command(student)).header("Authorization", token)).andExpect(status().isForbidden());
        assertThat(student.isEnabled()).isTrue();
    }

    @Test
    void authorizedForeignStudentAndDeletedTargetAreUniformlyAbsent() throws Exception {
        Student student = target(); String token = login("b.gates@abc.edu");
        Student foreign = students.findAll().stream().filter(candidate -> candidate.getSection().getCourse().getCourseAdmin()
                .getUsername().equals("t.cook@abc.edu")).findFirst().orElseThrow();
        for (int id : List.of(foreign.getId(), Integer.MAX_VALUE)) {
            mvc.perform(post(BASE + "/sections/" + student.getSection().getSectionId() + "/students/" + id + "/deactivation")
                    .header("Authorization", token)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Student no longer exists"));
        }
        assertThat(foreign.isEnabled()).isTrue();
    }

    @Test
    void studentWithoutTeamCanBeDeactivated() throws Exception {
        Student student = target(); student.setTeam(null); em.flush();
        mvc.perform(post(command(student)).header("Authorization", login("b.gates@abc.edu")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.student.teamId").isEmpty());
    }
}
