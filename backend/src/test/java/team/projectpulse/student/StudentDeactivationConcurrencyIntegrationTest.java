package team.projectpulse.student;

import jakarta.persistence.EntityManager;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import team.projectpulse.AbstractIntegrationTest;
import team.projectpulse.SharedContainers;
import team.projectpulse.evaluation.*;
import team.projectpulse.system.WeekKeys;
import team.projectpulse.system.exception.PeerEvaluationIllegalArgumentException;
import team.projectpulse.user.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** T6/T18: production services acquire locks; a separate observer proves actual MySQL contention. */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class StudentDeactivationConcurrencyIntegrationTest extends AbstractIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JwtDecoder decoder;
    @Autowired JwtAuthenticationConverter authenticationConverter;
    @Autowired PlatformTransactionManager transactions;
    @Autowired UserRepository users;
    @Autowired StudentRepository students;
    @Autowired StudentService studentService;
    @Autowired EvaluationService evaluationService;
    @Autowired PeerEvaluationRepository evaluations;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;
    private <T> T tx(Supplier<T> action) { return new TransactionTemplate(transactions).execute(status -> action.get()); }
    private Jwt login(String username) throws Exception {
        var response = mvc.perform(post("/api/v1/users/login").with(httpBasic(username, "123456"))).andReturn().getResponse();
        return decoder.decode(new JSONObject(response.getContentAsString()).getJSONObject("data").getString("token"));
    }
    private <T> T run(Jwt identity, Supplier<T> action) {
        SecurityContextHolder.getContext().setAuthentication(authenticationConverter.convert(identity));
        try { return tx(action); } finally { SecurityContextHolder.clearContext(); }
    }
    private void await(CountDownLatch latch) {
        try { assertThat(latch.await(30, TimeUnit.SECONDS)).as("transaction latch").isTrue(); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
    }
    private long connectionId() { return jdbc.queryForObject("select connection_id()", Long.class); }

    private void assertBlockedBy(long waitingConnection, long blockingConnection, Future<?> competing) throws Exception {
        assertThat(waitingConnection).as("independent transaction connections").isNotEqualTo(blockingConnection);
        // Observer-only root connection: the application user cannot read performance_schema.
        // This query neither acquires a target lock nor changes the services' return values.
        try (var observer = DriverManager.getConnection(SharedContainers.MYSQL.getJdbcUrl(),
                "root", SharedContainers.MYSQL.getPassword());
             var query = observer.prepareStatement("""
                     select count(*) from performance_schema.data_lock_waits waits
                     join performance_schema.threads requester on requester.THREAD_ID = waits.REQUESTING_THREAD_ID
                     join performance_schema.threads blocker on blocker.THREAD_ID = waits.BLOCKING_THREAD_ID
                     where requester.PROCESSLIST_ID = ? and blocker.PROCESSLIST_ID = ?
                     """)) {
            query.setLong(1, waitingConnection);
            query.setLong(2, blockingConnection);
            org.awaitility.Awaitility.await().alias("competing service waits on the first service's production lock")
                    .atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                        assertThat(competing.isDone()).as("competitor must not finish before first commit").isFalse();
                        try (var rows = query.executeQuery()) {
                            assertThat(rows.next()).isTrue();
                            assertThat(rows.getInt(1)).as("observed MySQL lock-wait edges").isPositive();
                        }
                    });
        }
    }

    private void stopWorkers(ExecutorService pool) throws InterruptedException {
        pool.shutdownNow();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).as("workers stopped before fixture cleanup").isTrue();
    }
    private Map<String, Object> history() {
        return Map.of("activities", jdbc.queryForList("select * from activity order by activity_id"),
                "evaluations", jdbc.queryForList("select * from peer_evaluation order by peer_evaluation_id"),
                "ratings", jdbc.queryForList("select * from rating order by rating_id"));
    }
    private int newTarget() {
        return tx(() -> {
            Student john = (Student) users.findByUsername("j.smith@abc.edu").orElseThrow();
            String username = "race-" + UUID.randomUUID() + "@example.test";
            Student target = new Student(username, "Race", "Student", username, "unused", true, "student");
            target.setSection(john.getSection()); target.setTeam(john.getTeam());
            return students.saveAndFlush(target).getId();
        });
    }
    @Test
    void simultaneousDeactivationsCommitExactlyOneTransition() throws Exception {
        Jwt admin = login("b.wei@abc.edu"); int id = newTarget();
        int sectionId = tx(() -> students.findById(id).orElseThrow().getSection().getSectionId());
        var before = history();
        CountDownLatch held = new CountDownLatch(1), secondStarted = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Long> firstConnection = new AtomicReference<>(), secondConnection = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<StudentDeactivation.Outcome> first = pool.submit(() -> run(admin, () -> {
                firstConnection.set(connectionId());
                var outcome = studentService.deactivateStudent(sectionId, id).outcome();
                em.flush();
                held.countDown(); await(release); // Hold only the commit, after the real service acquired its lock.
                return outcome;
            }));
            await(held);
            Future<StudentDeactivation.Outcome> second = pool.submit(() -> run(admin, () -> {
                assertThat(students.findById(id).orElseThrow().isEnabled()).isTrue();
                secondConnection.set(connectionId());
                secondStarted.countDown(); return studentService.deactivateStudent(sectionId, id).outcome();
            }));
            await(secondStarted);
            assertBlockedBy(secondConnection.get(), firstConnection.get(), second);
            release.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(StudentDeactivation.Outcome.DEACTIVATED);
            assertThat(second.get(20, TimeUnit.SECONDS)).isEqualTo(StudentDeactivation.Outcome.ALREADY_DEACTIVATED);
            assertThat(tx(() -> students.findById(id).orElseThrow().isEnabled())).isFalse();
            assertThat(history()).isEqualTo(before);
        } finally {
            release.countDown(); stopWorkers(pool);
            tx(() -> { students.deleteById(id); return null; });
        }
    }

    private PeerEvaluation payload(int evaluatorId, int targetId, double score) {
        Student evaluator = students.findById(evaluatorId).orElseThrow(), target = students.findById(targetId).orElseThrow();
        return new PeerEvaluation(WeekKeys.previousWeek(clock), evaluator, target,
                evaluator.getSection().getRubric().getCriteria().stream().map(criterion -> new Rating(criterion, score)).toList(),
                "race public " + score, "race private " + score);
    }
    @ParameterizedTest
    @CsvSource({"true,false", "true,true", "false,false", "false,true"})
    void evaluateeLockSerializesCreateOrEditWithDeactivation(boolean deactivationFirst, boolean edit) throws Exception {
        Jwt admin = login("b.wei@abc.edu"), john = login("j.smith@abc.edu");
        int evaluatorId = users.findByUsername("j.smith@abc.edu").orElseThrow().getId();
        int targetId = newTarget();
        int sectionId = tx(() -> students.findById(targetId).orElseThrow().getSection().getSectionId());
        AtomicReference<Integer> fixtureId = new AtomicReference<>();
        tx(() -> {
            assertThat(evaluations.findByWeekAndEvaluatorIdAndEvaluateeId(WeekKeys.previousWeek(clock), evaluatorId, targetId)).isEmpty();
            if (edit) fixtureId.set(evaluations.saveAndFlush(payload(evaluatorId, targetId, 1)).getPeerEvaluationId());
            return null;
        });
        AtomicReference<Map<String, Object>> committedHistory = new AtomicReference<>(history());
        Supplier<Object> evaluate = () -> {
            PeerEvaluation update = payload(evaluatorId, targetId, 2);
            PeerEvaluation saved = edit ? evaluationService.updatePeerEvaluation(fixtureId.get(), update)
                    : evaluationService.addPeerEvaluation(update);
            fixtureId.set(saved.getPeerEvaluationId()); em.flush(); committedHistory.set(history());
            return saved.getPeerEvaluationId();
        };
        Supplier<Object> deactivate = () -> studentService.deactivateStudent(sectionId, targetId).outcome();
        CountDownLatch held = new CountDownLatch(1), secondReady = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Long> firstConnection = new AtomicReference<>(), secondConnection = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = pool.submit(() -> run(deactivationFirst ? admin : john, () -> {
                firstConnection.set(connectionId());
                Object result = (deactivationFirst ? deactivate : evaluate).get();
                em.flush();
                held.countDown(); await(release);
                return result;
            }));
            await(held);
            Future<Object> second = pool.submit(() -> run(deactivationFirst ? john : admin, () -> {
                // A consistent read sees enabled=true while the first transaction holds the write lock.
                // The later service must replace this cached instance with a current locked read.
                assertThat(students.findById(targetId).orElseThrow().isEnabled()).isTrue();
                secondConnection.set(connectionId());
                secondReady.countDown(); return (deactivationFirst ? evaluate : deactivate).get();
            }));
            await(secondReady);
            assertBlockedBy(secondConnection.get(), firstConnection.get(), second);
            release.countDown();
            Object firstResult = first.get(20, TimeUnit.SECONDS);
            if (deactivationFirst) assertThat(firstResult).isEqualTo(StudentDeactivation.Outcome.DEACTIVATED);
            if (deactivationFirst) assertThatThrownBy(() -> second.get(20, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(PeerEvaluationIllegalArgumentException.class)
                    .cause().hasMessage("A deactivated student cannot be evaluated.");
            else assertThat(second.get(20, TimeUnit.SECONDS)).isEqualTo(StudentDeactivation.Outcome.DEACTIVATED);
            assertThat(tx(() -> students.findById(targetId).orElseThrow().isEnabled())).isFalse();
            assertThat(history()).isEqualTo(committedHistory.get());
            tx(() -> {
                var stored = evaluations.findByWeekAndEvaluatorIdAndEvaluateeId(
                        WeekKeys.previousWeek(clock), evaluatorId, targetId);
                if (deactivationFirst && !edit) {
                    assertThat(stored).as("rejected create writes no evaluation").isEmpty();
                } else {
                    var evaluation = stored.orElseThrow();
                    double expectedScore = deactivationFirst ? 1.0 : 2.0;
                    assertThat(evaluation.getRatings()).isNotEmpty().allSatisfy(rating ->
                            assertThat(rating.getActualScore()).isEqualTo(expectedScore));
                    assertThat(evaluation.getTotalScore()).isEqualTo(expectedScore * evaluation.getRatings().size());
                    assertThat(evaluation.getPublicComment()).isEqualTo("race public " + expectedScore);
                    assertThat(evaluation.getPrivateComment()).isEqualTo("race private " + expectedScore);
                }
                return null;
            });
        } finally {
            release.countDown(); stopWorkers(pool);
            tx(() -> {
                if (fixtureId.get() != null) evaluations.deleteById(fixtureId.get());
                students.deleteById(targetId); return null;
            });
        }
    }
}
