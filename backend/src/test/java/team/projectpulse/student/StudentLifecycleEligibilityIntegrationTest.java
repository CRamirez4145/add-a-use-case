package team.projectpulse.student;

import jakarta.persistence.EntityManager;
import org.json.JSONObject;
import org.json.JSONArray;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import team.projectpulse.AbstractIntegrationTest;
import team.projectpulse.evaluation.*;
import team.projectpulse.notification.*;
import team.projectpulse.system.WeekKeys;
import team.projectpulse.user.UserRepository;
import java.time.Clock;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** UC-STU-deactivate-student T16/T17. */
class StudentLifecycleEligibilityIntegrationTest extends AbstractIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PeerEvaluationRepository evaluations;
    @Autowired SubmissionStatusService submissions;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired Clock clock;
    @Autowired JsonMapper json;
    private static final String BASE = "/api/v1";
    private Student student(String name) { return (Student) users.findByUsername(name).orElseThrow(); }
    private Student fixtureStudent(Student teammate) {
        String username = "consequence-" + UUID.randomUUID() + "@example.test";
        Student fixture = new Student(username, "Fixture", "Student", username, "unused", true, "student");
        fixture.setSection(teammate.getSection()); fixture.setTeam(teammate.getTeam());
        return (Student) users.saveAndFlush(fixture);
    }
    private String login(String name) throws Exception {
        return "Bearer " + new JSONObject(mvc.perform(post(BASE + "/users/login").with(httpBasic(name, "123456")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).getJSONObject("data").getString("token");
    }
    private void deactivate(Student target, String token) throws Exception {
        mvc.perform(post(BASE + "/sections/" + target.getSection().getSectionId() + "/students/" + target.getId() + "/deactivation")
                .header("Authorization", token)).andExpect(status().isOk());
        em.flush();
        // Subsequent HTTP reads use a new persistence context in the running application.
        em.clear();
    }
    private Map<String, Object> history() {
        return Map.of("activities", jdbc.queryForList("select * from activity order by activity_id"),
                "evaluations", jdbc.queryForList("select * from peer_evaluation order by peer_evaluation_id"),
                "ratings", jdbc.queryForList("select * from rating order by rating_id"));
    }
    private JSONObject evaluationBody(Student evaluator, Student target) throws org.json.JSONException {
        JSONArray ratings = new JSONArray();
        for (var criterion : evaluator.getSection().getRubric().getCriteria())
            ratings.put(new JSONObject().put("criterionId", criterion.getCriterionId()).put("actualScore", 1.0));
        return new JSONObject().put("week", WeekKeys.previousWeek(clock)).put("evaluatorId", evaluator.getId())
                .put("evaluateeId", target.getId()).put("ratings", ratings)
                .put("publicComment", "changed public").put("privateComment", "changed private")
                .put("evaluateeEnabled", true).put("evaluatorEnabled", true);
    }
    @Test
    void inactiveEvaluateeRejectsCreateAndEditWithoutChangingAnyHistory() throws Exception {
        Student john = student("j.smith@abc.edu"), eric = fixtureStudent(john);
        String johnToken = login(john.getUsername()), admin = login("b.wei@abc.edu");
        JSONObject body = evaluationBody(john, eric);
        var created = mvc.perform(post(BASE + "/evaluations").header("Authorization", johnToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isOk()).andReturn().getResponse();
        int id = new JSONObject(created.getContentAsString()).getJSONObject("data").getInt("evaluationId");
        deactivate(eric, admin);
        Map<String, Object> before = history();
        // Eligibility comes from the stored target on edit, even if the payload names an active one.
        body.put("evaluateeId", john.getId());
        mvc.perform(put(BASE + "/evaluations/" + id).header("Authorization", johnToken)
                .contentType(MediaType.APPLICATION_JSON).content(body.toString())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("A deactivated student cannot be evaluated."));
        body.put("evaluateeId", eric.getId());
        mvc.perform(post(BASE + "/evaluations").header("Authorization", johnToken)
                .contentType(MediaType.APPLICATION_JSON).content(body.toString())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("A deactivated student cannot be evaluated."));
        em.flush();
        assertThat(history()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void profileStatusChangeInEitherDirectionIsAtomic(boolean enabled) throws Exception {
        Student john = student("j.smith@abc.edu"); john.setEnabled(enabled); em.flush();
        String admin = login("b.wei@abc.edu");
        JSONObject body = new JSONObject(mvc.perform(get(BASE + "/students/" + john.getId()).header("Authorization", admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).getJSONObject("data");
        body.put("enabled", !enabled).put("firstName", "Must not commit");
        mvc.perform(put(BASE + "/students/" + john.getId()).header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON).content(body.toString())).andExpect(status().isBadRequest());
        em.flush();
        Student persisted = em.find(Student.class, john.getId());
        assertThat(persisted.getFirstName()).isEqualTo("John"); assertThat(persisted.isEnabled()).isEqualTo(enabled);
        body.put("enabled", enabled).put("firstName", "Permitted profile edit");
        mvc.perform(put(BASE + "/students/" + john.getId()).header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON).content(body.toString())).andExpect(status().isOk());
    }

    @Test
    void staleProfileLoadedBeforeDeactivationCannotReactivateAccount() throws Exception {
        Student john = student("j.smith@abc.edu"); String admin = login("b.wei@abc.edu");
        JSONObject stale = new JSONObject(mvc.perform(get(BASE + "/students/" + john.getId()).header("Authorization", admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).getJSONObject("data");
        deactivate(john, admin); stale.put("firstName", "Stale form must not commit");
        mvc.perform(put(BASE + "/students/" + john.getId()).header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON).content(stale.toString())).andExpect(status().isBadRequest());
        em.flush();
        Student persisted = em.find(Student.class, john.getId());
        assertThat(persisted.isEnabled()).isFalse(); assertThat(persisted.getFirstName()).isEqualTo("John");
    }

}
