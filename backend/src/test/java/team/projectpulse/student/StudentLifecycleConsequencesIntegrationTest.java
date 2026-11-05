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

/** UC-STU-deactivate-student T20/T21/T22. */
class StudentLifecycleConsequencesIntegrationTest extends AbstractIntegrationTest {
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
    @SuppressWarnings("unchecked")
    private Object historicalValues(Object value) {
        if (value instanceof JSONObject || value instanceof JSONArray)
            return historicalValues(json.readValue(value.toString(), Object.class));
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                if (!Set.of("enabled", "evaluatorEnabled", "evaluateeEnabled", "studentEnabled").contains(key))
                    result.put((String) key, historicalValues(item));
            });
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(this::historicalValues).toList();
        return value;
    }
    private JSONObject read(String path, String token) throws Exception {
        return new JSONObject(mvc.perform(get(BASE + path).header("Authorization", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    @Test
    void historicalReportsRetainAllScoresCommentsHoursAndPrivacyWithCurrentStatus() throws Exception {
        Student john = student("j.smith@abc.edu"); String admin = login("b.wei@abc.edu");
        int sectionId = john.getSection().getSectionId();
        List<String> paths = List.of("/evaluations/sections/" + sectionId + "/week/2023-W31",
                "/evaluations/students/" + john.getId() + "/week/2023-W31",
                "/evaluations/students/" + john.getId() + "?startWeek=2023-W31&endWeek=2023-W31",
                "/evaluations/students/" + john.getId() + "/week/2023-W31/details",
                "/evaluations/evaluators/" + john.getId() + "/week/2023-W31");
        List<Object> before = new ArrayList<>();
        for (String path : paths) before.add(historicalValues(read(path, admin)));
        var request = post(BASE + "/activities/search").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"studentId\":\"" + john.getId() + "\"}");
        JSONObject activitiesBefore = new JSONObject(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        int activityId = activitiesBefore.getJSONObject("data").getJSONArray("content").getJSONObject(0).getInt("activityId");
        Object activityBefore = historicalValues(read("/activities/" + activityId, admin));
        Map<String, Object> databaseBefore = history();
        deactivate(john, admin);
        for (int i = 0; i < paths.size(); i++) assertThat(historicalValues(read(paths.get(i), admin))).isEqualTo(before.get(i));
        assertThat(read(paths.get(1), admin).getJSONObject("data").getBoolean("enabled")).isFalse();
        assertThat(read(paths.get(1), admin).getJSONObject("data").isNull("privateComments")).isTrue();
        JSONObject after = new JSONObject(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(historicalValues(after)).isEqualTo(historicalValues(activitiesBefore));
        assertThat(after.getJSONObject("data").getJSONArray("content").getJSONObject(0).getBoolean("studentEnabled")).isFalse();
        assertThat(historicalValues(read("/activities/" + activityId, admin))).isEqualTo(activityBefore);
        assertThat(history()).isEqualTo(databaseBefore);
        // Existing cross-scope rules still refuse the foreign course admin.
        mvc.perform(get(BASE + paths.get(3)).header("Authorization", login("t.cook@abc.edu"))).andExpect(status().isForbidden());
    }

    @Test
    void obligationsAndEmptyFutureParticipationExcludeInactiveStudentsWithoutDeletingHistory() throws Exception {
        Student john = student("j.smith@abc.edu"), eric = fixtureStudent(john);
        String currentWeek = WeekKeys.of(java.time.LocalDate.now(clock));
        String week = john.getSection().getActiveWeeks().stream()
                .filter(candidate -> candidate.compareTo(currentWeek) > 0)
                .filter(candidate -> !evaluations.hasHistoricalParticipation(candidate, john.getId()))
                .findFirst().orElseThrow();
        Set<Student> teammates = new LinkedHashSet<>(john.getTeam().getStudents());
        teammates.add(eric);
        for (Student teammate : teammates) {
            if (!teammate.getId().equals(john.getId())) evaluations.save(new PeerEvaluation(week, eric, teammate,
                    john.getSection().getRubric().getCriteria().stream().map(criterion -> new Rating(criterion, 1.0)).toList(), "", ""));
        }
        em.flush();
        int sectionId = john.getSection().getSectionId();
        assertThat(submissions.findOwing(sectionId, SubmittableItem.PEER_EVALUATION, week))
                .extracting(Student::getId).contains(eric.getId());
        Map<String, Object> before = history();
        String admin = login("b.wei@abc.edu"); deactivate(john, admin);
        for (String checkWeek : List.of("2023-W31", week)) {
            for (SubmittableItem item : SubmittableItem.values())
                assertThat(submissions.findOwing(sectionId, item, checkWeek))
                        .extracting(Student::getId).doesNotContain(john.getId());
        }
        assertThat(submissions.findOwing(sectionId, SubmittableItem.PEER_EVALUATION, week))
                .extracting(Student::getId).doesNotContain(eric.getId());
        List<?> averages = json.readValue(read("/evaluations/sections/" + sectionId + "/week/" + week, admin)
                .getJSONObject("data").getJSONArray("peerEvaluationAverages").toString(), List.class);
        assertThat(averages).noneMatch(row -> ((Map<?, ?>) row).get("studentId").equals(john.getId()));
        assertThat(history()).isEqualTo(before);
    }
}
