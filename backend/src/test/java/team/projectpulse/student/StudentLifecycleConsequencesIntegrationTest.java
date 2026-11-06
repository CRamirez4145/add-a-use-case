package team.projectpulse.student;

import jakarta.persistence.EntityManager;
import org.json.JSONObject;
import org.json.JSONArray;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import team.projectpulse.AbstractIntegrationTest;
import team.projectpulse.activity.*;
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
    @Autowired StudentRepository students;
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
                .header("Authorization", token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("DEACTIVATED"))
                .andExpect(jsonPath("$.data.student.enabled").value(false));
        em.flush();
        // Subsequent HTTP reads use a new persistence context in the running application.
        em.clear();
    }
    private Map<String, Object> history() {
        return Map.of("activities", jdbc.queryForList("select * from activity order by activity_id"),
                "evaluations", jdbc.queryForList("select * from peer_evaluation order by peer_evaluation_id"),
                "ratings", jdbc.queryForList("select * from rating order by rating_id"));
    }
    // T20/T21 history rules permit ONLY this student's status metadata to change.
    // Compare every other field, including other students' flags, ids, scores, comments and timestamps.
    private Object expectedAfterDeactivation(Object value, int studentId) {
        if (value instanceof JSONObject || value instanceof JSONArray)
            return expectedAfterDeactivation(json.readValue(value.toString(), Object.class), studentId);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                result.put((String) key, expectedAfterDeactivation(item, studentId));
            });
            if (Objects.equals(map.get("studentId"), studentId)) {
                if (map.containsKey("enabled")) result.put("enabled", false);
                if (map.containsKey("studentEnabled")) result.put("studentEnabled", false);
            }
            if (Objects.equals(map.get("evaluatorId"), studentId)) result.put("evaluatorEnabled", false);
            if (Objects.equals(map.get("evaluateeId"), studentId)) result.put("evaluateeEnabled", false);
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(item -> expectedAfterDeactivation(item, studentId)).toList();
        return value;
    }
    private Object document(JSONObject value) { return json.readValue(value.toString(), Object.class); }
    private List<JSONObject> rows(JSONArray array) throws Exception {
        List<JSONObject> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) result.add(array.getJSONObject(i));
        return result;
    }
    private JSONObject row(JSONArray array, String idField, int id) throws Exception {
        List<JSONObject> matching = new ArrayList<>();
        for (JSONObject row : rows(array)) if (row.getInt(idField) == id) matching.add(row);
        assertThat(matching).as("exactly one %s=%s", idField, id).hasSize(1);
        return matching.getFirst();
    }
    private PeerEvaluation evaluation(String week, Student evaluator, Student target, double score,
                                      String publicComment, String privateComment) {
        assertThat(evaluator.getSection().getRubric().getCriteria()).isNotEmpty();
        return evaluations.saveAndFlush(new PeerEvaluation(week, evaluator, target,
                evaluator.getSection().getRubric().getCriteria().stream().map(criterion -> new Rating(criterion, score)).toList(),
                publicComment, privateComment));
    }
    private Activity activity(Student author, String name, double planned, double actual) {
        Activity activity = new Activity(author, "2023-W31", author.getTeam(), ActivityCategory.DEVELOPMENT,
                name, "Retained description", planned, actual, ActivityStatus.COMPLETED);
        activity.addComment("Retained activity comment");
        em.persist(activity); em.flush();
        return activity;
    }
    private JSONObject activities(Map<String, String> criteria, String token) throws Exception {
        return new JSONObject(mvc.perform(post(BASE + "/activities/search").param("size", "200")
                        .header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(criteria)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private JSONObject read(String path, String token) throws Exception {
        return new JSONObject(mvc.perform(get(BASE + path).header("Authorization", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    @Test
    void historicalEvaluationReportsPreserveRecordsAndCurrentStatus() throws Exception {
        // T20 / BR-evaluation-deactivation-history: submitted AND received records must survive.
        Student john = student("j.smith@abc.edu"), target = fixtureStudent(john);
        String admin = login("b.wei@abc.edu"), johnToken = login(john.getUsername());
        int targetId = target.getId(), sectionId = target.getSection().getSectionId();
        int criteriaCount = target.getSection().getRubric().getCriteria().size();
        var received = evaluation("2023-W31", john, target, 2.0, "Received public history", "Received private history");
        var submitted = evaluation("2023-W31", target, john, 3.0, "Submitted public history", "Submitted private history");
        var self = evaluation("2023-W31", target, target, 4.0, "Self public history", "Self private history");
        List<String> paths = List.of("/evaluations/sections/" + sectionId + "/week/2023-W31",
                "/evaluations/students/" + targetId + "/week/2023-W31",
                "/evaluations/students/" + targetId + "?startWeek=2023-W31&endWeek=2023-W31",
                "/evaluations/students/" + targetId + "/week/2023-W31/details",
                "/evaluations/evaluators/" + targetId + "/week/2023-W31");
        List<JSONObject> before = new ArrayList<>();
        for (String path : paths) before.add(read(path, admin));
        JSONObject average = row(before.getFirst().getJSONObject("data").getJSONArray("peerEvaluationAverages"), "studentId", targetId);
        assertThat(average.getBoolean("enabled")).isTrue();
        assertThat(average.getDouble("averageTotalScore")).isEqualTo(3.0 * criteriaCount);
        assertThat(rows(average.getJSONArray("ratingAverages"))).hasSize(criteriaCount);
        for (JSONObject rating : rows(average.getJSONArray("ratingAverages")))
            assertThat(rating.getDouble("averageScore")).isEqualTo(3.0);
        assertThat(average.getJSONArray("publicComments").toString()).contains("Received public history", "Self public history");
        assertThat(average.getJSONArray("privateComments").toString()).contains("Received private history", "Self private history");
        assertThat(before.get(1).getJSONObject("data").getBoolean("enabled")).isTrue();
        assertThat(before.get(1).getJSONObject("data").isNull("privateComments")).isTrue();
        assertThat(rows(before.get(2).getJSONArray("data"))).hasSize(1);
        assertThat(row(before.get(2).getJSONArray("data"), "studentId", targetId).getBoolean("enabled")).isTrue();
        for (int index : List.of(3, 4)) {
            assertThat(rows(before.get(index).getJSONArray("data"))).hasSize(2);
            for (JSONObject record : rows(before.get(index).getJSONArray("data"))) {
                assertThat(record.getBoolean("evaluatorEnabled")).isTrue();
                assertThat(record.getBoolean("evaluateeEnabled")).isTrue();
                assertThat(rows(record.getJSONArray("ratings"))).hasSize(criteriaCount);
            }
        }
        JSONObject receivedRow = row(before.get(3).getJSONArray("data"), "evaluationId", received.getPeerEvaluationId());
        assertThat(receivedRow.getDouble("totalScore")).isEqualTo(2.0 * criteriaCount);
        assertThat(receivedRow.getString("privateComment")).isEqualTo("Received private history");
        JSONObject submittedRow = row(before.get(4).getJSONArray("data"), "evaluationId", submitted.getPeerEvaluationId());
        assertThat(submittedRow.getDouble("totalScore")).isEqualTo(3.0 * criteriaCount);
        assertThat(submittedRow.getString("publicComment")).isEqualTo("Submitted public history");
        row(before.get(3).getJSONArray("data"), "evaluationId", self.getPeerEvaluationId());
        String selfPath = "/evaluations/students/" + john.getId() + "?startWeek=2023-W31&endWeek=2023-W31";
        JSONObject selfBefore = read(selfPath, johnToken);
        assertThat(rows(selfBefore.getJSONArray("data"))).hasSize(1);
        assertThat(selfBefore.getJSONArray("data").getJSONObject(0).isNull("privateComments")).isTrue();
        Map<String, Object> databaseBefore = history();
        deactivate(target, admin);
        for (int i = 0; i < paths.size(); i++)
            assertThat(document(read(paths.get(i), admin))).as("preserved report %s", paths.get(i))
                    .isEqualTo(expectedAfterDeactivation(before.get(i), targetId));
        assertThat(document(read(selfPath, johnToken))).isEqualTo(document(selfBefore));
        assertThat(history()).isEqualTo(databaseBefore);
        mvc.perform(get(BASE + paths.get(1)).header("Authorization", johnToken)).andExpect(status().isForbidden());
        mvc.perform(get(BASE + paths.get(3)).header("Authorization", johnToken)).andExpect(status().isForbidden());
        mvc.perform(get(BASE + paths.get(3)).header("Authorization", login("t.cook@abc.edu"))).andExpect(status().isForbidden());
    }

    @Test
    void historicalWarReportsPreserveEveryActivityAndHoursWithCurrentAuthorStatus() throws Exception {
        // T21 / BR-war-deactivation-history: exact record hours, totals and active-author control.
        Student john = student("j.smith@abc.edu"), target = fixtureStudent(john);
        int targetId = target.getId();
        var first = activity(target, "Retained first work", 4.25, 3.5);
        var second = activity(target, "Retained second work", 1.75, 2.5);
        var activeControl = activity(john, "Active author control", 2.0, 1.0);
        String admin = login("b.wei@abc.edu"), johnToken = login(john.getUsername());
        List<Map<String, String>> scopes = List.of(Map.of("studentId", String.valueOf(targetId)),
                Map.of("teamId", target.getTeam().getTeamId().toString()), Map.of("week", "2023-W31"));
        List<JSONObject> before = new ArrayList<>();
        for (Map<String, String> scope : scopes) {
            JSONObject report = activities(scope, admin);
            JSONArray content = report.getJSONObject("data").getJSONArray("content");
            assertThat(rows(content)).isNotEmpty();
            assertThat(report.getJSONObject("data").getInt("totalElements")).isEqualTo(content.length());
            JSONObject firstRow = row(content, "activityId", first.getActivityId());
            JSONObject secondRow = row(content, "activityId", second.getActivityId());
            assertThat(firstRow.getBoolean("studentEnabled")).isTrue();
            assertThat(secondRow.getBoolean("studentEnabled")).isTrue();
            assertThat(firstRow.getDouble("plannedHours")).isEqualTo(4.25);
            assertThat(firstRow.getDouble("actualHours")).isEqualTo(3.5);
            assertThat(secondRow.getDouble("plannedHours")).isEqualTo(1.75);
            assertThat(secondRow.getDouble("actualHours")).isEqualTo(2.5);
            before.add(report);
        }
        JSONArray ownRows = before.getFirst().getJSONObject("data").getJSONArray("content");
        assertThat(rows(ownRows)).hasSize(2);
        assertThat(ownRows.getJSONObject(0).getDouble("plannedHours") + ownRows.getJSONObject(1).getDouble("plannedHours")).isEqualTo(6.0);
        assertThat(ownRows.getJSONObject(0).getDouble("actualHours") + ownRows.getJSONObject(1).getDouble("actualHours")).isEqualTo(6.0);
        assertThat(row(before.get(1).getJSONObject("data").getJSONArray("content"), "activityId", activeControl.getActivityId())
                .getBoolean("studentEnabled")).isTrue();
        List<JSONObject> detailsBefore = List.of(read("/activities/" + first.getActivityId(), admin), read("/activities/" + second.getActivityId(), admin));
        JSONObject teamBefore = activities(scopes.get(1), johnToken);
        Map<String, Object> databaseBefore = history();
        deactivate(target, admin);
        for (int i = 0; i < scopes.size(); i++)
            assertThat(document(activities(scopes.get(i), admin))).isEqualTo(expectedAfterDeactivation(before.get(i), targetId));
        for (JSONObject detail : detailsBefore) {
            int activityId = detail.getJSONObject("data").getInt("activityId");
            assertThat(document(read("/activities/" + activityId, admin))).isEqualTo(expectedAfterDeactivation(detail, targetId));
        }
        assertThat(document(activities(scopes.get(1), johnToken))).isEqualTo(expectedAfterDeactivation(teamBefore, targetId));
        assertThat(history()).isEqualTo(databaseBefore);
        mvc.perform(get(BASE + "/activities/" + first.getActivityId()).header("Authorization", login("w.allen@abc.edu")))
                .andExpect(status().isForbidden());
    }

    @Test
    void obligationsAndEmptyFutureParticipationExcludeInactiveStudentsWithoutDeletingHistory() throws Exception {
        // T22 / BR-submission-owed: a fresh target makes no future participation a fixture fact.
        Student anchor = student("j.smith@abc.edu"), john = fixtureStudent(anchor), eric = fixtureStudent(anchor);
        String currentWeek = WeekKeys.of(java.time.LocalDate.now(clock));
        String week = "2023-W36";
        assertThat(week).isGreaterThan(currentWeek);
        assertThat(john.getSection().getActiveWeeks()).contains(week);
        int targetId = john.getId(), teammateId = eric.getId();
        evaluation("2023-W31", anchor, john, 2.0, "Retained history", "Retained private history");
        activity(john, "Retained historical WAR", 4.25, 3.5);
        for (Student teammate : students.findByTeamTeamId(john.getTeam().getTeamId())) {
            if (teammate.isEnabled() && !teammate.getId().equals(targetId))
                evaluation(week, eric, teammate, 1.0, "Future active evaluation", "");
        }
        em.flush();
        assertThat(jdbc.queryForObject("select count(*) from peer_evaluation where week = ? and (evaluator_id = ? or evaluatee_id = ?)",
                Integer.class, week, targetId, targetId)).as("no future target participation").isZero();
        assertThat(jdbc.queryForObject("select count(*) from activity where week = ? and student_id = ?", Integer.class, week, targetId)).isZero();
        int sectionId = john.getSection().getSectionId();
        for (SubmittableItem item : SubmittableItem.values())
            assertThat(submissions.findOwing(sectionId, item, week)).extracting(Student::getId).contains(targetId, teammateId);
        String admin = login("b.wei@abc.edu");
        String futurePeriod = "/evaluations/students/" + targetId + "?startWeek=" + week + "&endWeek=" + week;
        assertThat(rows(read(futurePeriod, admin).getJSONArray("data"))).hasSize(1);
        JSONObject historicalBefore = read("/evaluations/students/" + targetId + "?startWeek=2023-W31&endWeek=2023-W31", admin);
        assertThat(rows(historicalBefore.getJSONArray("data"))).hasSize(1);
        assertThat(historicalBefore.getJSONArray("data").getJSONObject(0).getDouble("averageTotalScore")).isPositive();
        Map<String, Object> before = history();
        deactivate(john, admin);
        for (String checkWeek : List.of("2023-W31", week)) {
            for (SubmittableItem item : SubmittableItem.values())
                assertThat(submissions.findOwing(sectionId, item, checkWeek))
                        .extracting(Student::getId).doesNotContain(john.getId());
        }
        assertThat(submissions.findOwing(sectionId, SubmittableItem.PEER_EVALUATION, week))
                .extracting(Student::getId).doesNotContain(teammateId);
        // Positive control: excluding the inactive target must not empty every obligation list.
        assertThat(submissions.findOwing(sectionId, SubmittableItem.WEEKLY_ACTIVITY_REPORT, week))
                .extracting(Student::getId).contains(teammateId);
        JSONArray averages = read("/evaluations/sections/" + sectionId + "/week/" + week, admin)
                .getJSONObject("data").getJSONArray("peerEvaluationAverages");
        assertThat(rows(averages)).isNotEmpty();
        for (JSONObject average : rows(averages)) assertThat(average.getInt("studentId")).isNotEqualTo(targetId);
        assertThat(row(averages, "studentId", teammateId).getBoolean("enabled")).isTrue();
        assertThat(rows(read(futurePeriod, admin).getJSONArray("data"))).isEmpty();
        assertThat(rows(read("/evaluations/students/" + targetId + "/week/" + week + "/details", admin).getJSONArray("data"))).isEmpty();
        assertThat(rows(read("/evaluations/evaluators/" + targetId + "/week/" + week, admin).getJSONArray("data"))).isEmpty();
        assertThat(document(read("/evaluations/students/" + targetId + "?startWeek=2023-W31&endWeek=2023-W31", admin)))
                .isEqualTo(expectedAfterDeactivation(historicalBefore, targetId));
        assertThat(history()).isEqualTo(before);
    }
}
