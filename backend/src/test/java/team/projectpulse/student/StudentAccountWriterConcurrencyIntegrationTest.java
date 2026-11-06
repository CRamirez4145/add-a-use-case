package team.projectpulse.student;

import jakarta.persistence.EntityManager;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.json.JSONObject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import team.projectpulse.AbstractIntegrationTest;
import team.projectpulse.team.Team;
import team.projectpulse.team.TeamRepository;
import team.projectpulse.team.TeamService;
import team.projectpulse.user.UserRepository;
import team.projectpulse.user.UserService;
import team.projectpulse.user.resetpassword.ResetPasswordToken;
import team.projectpulse.user.resetpassword.ResetPasswordTokenRepository;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real committed MySQL transactions; SQL capture observes, but does not alter, Hibernate statements. */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.session_factory.statement_inspector="
        + "team.projectpulse.student.StudentAccountWriterConcurrencyIntegrationTest$SqlCapture")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class StudentAccountWriterConcurrencyIntegrationTest extends AbstractIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired StudentRepository students;
    @Autowired UserRepository users;
    @Autowired TeamRepository teams;
    @Autowired ResetPasswordTokenRepository tokens;
    @Autowired StudentService studentService;
    @Autowired TeamService teamService;
    @Autowired UserService userService;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtDecoder decoder;
    @Autowired JwtAuthenticationConverter converter;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;

    public static class SqlCapture implements StatementInspector {
        private static final ThreadLocal<List<String>> statements = new ThreadLocal<>();
        @Override public String inspect(String sql) {
            var capture = statements.get();
            if (capture != null && sql.toLowerCase(Locale.ROOT).startsWith("update peer_evaluation_user ")) {
                capture.add(sql);
            }
            return sql;
        }
    }

    private <T> T tx(Supplier<T> action) {
        return new TransactionTemplate(transactions).execute(status -> action.get());
    }
    private void await(CountDownLatch latch) {
        try { assertThat(latch.await(30, TimeUnit.SECONDS)).as("transaction rendezvous").isTrue(); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
    }
    private String login(String username) throws Exception {
        var response = mvc.perform(post("/api/v1/users/login").with(httpBasic(username, "123456")))
                .andExpect(status().isOk()).andReturn().getResponse();
        return new JSONObject(response.getContentAsString()).getJSONObject("data").getString("token");
    }

    @ParameterizedTest(name = "{0}, deactivation commits first={1}")
    @CsvSource({"assign,true", "transfer,true", "remove,true", "password,true",
            "assign,false", "transfer,false", "remove,false", "password,false"})
    void unrelatedWriterCannotRestoreAccountStatus(String operation, boolean deactivationFirst) throws Exception {
        String email = "writer-" + UUID.randomUUID() + "@example.test";
        int[] fixture = tx(() -> {
            Student john = (Student) users.findByUsername("j.smith@abc.edu").orElseThrow();
            Team destination = new Team("Writer race", "isolated fixture", null);
            destination.setSection(john.getSection());
            teams.saveAndFlush(destination);
            Student target = new Student(email, "Writer", "Race", email, passwords.encode("123456"), true, "student");
            target.setSection(john.getSection());
            if (!operation.equals("assign")) target.setTeam(john.getTeam());
            students.saveAndFlush(target);
            return new int[]{target.getId(), john.getSection().getSectionId(), destination.getTeamId(), john.getTeam().getTeamId()};
        });
        int id = fixture[0];
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch loaded = new CountDownLatch(1), release = new CountDownLatch(1);
        try {
            String jwt = login(email);
            String admin = login("b.wei@abc.edu");
            mvc.perform(get("/api/v1/students/" + id).header("Authorization", "Bearer " + jwt))
                    .andExpect(status().isOk());
            ResetPasswordToken reset = new ResetPasswordToken(email);
            tx(() -> tokens.saveAndFlush(reset));
            List<String> writerSql = new ArrayList<>();
            Future<Long> writer = pool.submit(() -> {
                SqlCapture.statements.set(writerSql);
                try {
                    return tx(() -> {
                        // Preload through the same repository used by the production writer.
                        assertThat(users.findByEmail(email).orElseThrow().isEnabled()).isTrue();
                        long connection = jdbc.queryForObject("select connection_id()", Long.class);
                        loaded.countDown();
                        await(release);
                        switch (operation) {
                            case "assign", "transfer" -> teamService.assignStudentToTeam(fixture[2], id);
                            case "remove" -> teamService.removeStudentFromTeam(fixture[3], id);
                            case "password" -> userService.resetPassword(Map.of("email", email,
                                    "token", reset.getToken(), "newPassword", "Changed123"));
                            default -> throw new IllegalArgumentException(operation);
                        }
                        em.flush();
                        return connection;
                    }); // Future completes only after commit.
                } finally { SqlCapture.statements.remove(); }
            });
            await(loaded);
            if (!deactivationFirst) { release.countDown(); writer.get(30, TimeUnit.SECONDS); }
            long deactivationConnection;
            List<String> deactivationSql = new ArrayList<>();
            SecurityContextHolder.getContext().setAuthentication(converter.convert(decoder.decode(admin)));
            SqlCapture.statements.set(deactivationSql);
            try {
                deactivationConnection = tx(() -> {
                    long connection = jdbc.queryForObject("select connection_id()", Long.class);
                    assertThat(studentService.deactivateStudent(fixture[1], id).outcome())
                            .isEqualTo(StudentDeactivation.Outcome.DEACTIVATED);
                    return connection;
                });
            } finally { SqlCapture.statements.remove(); SecurityContextHolder.clearContext(); }
            assertThat(jdbc.queryForObject("select enabled from peer_evaluation_user where id=?", Boolean.class, id)).isFalse();
            mvc.perform(get("/api/v1/students/" + id).header("Authorization", "Bearer " + jwt))
                    .andExpect(status().isUnauthorized());
            release.countDown();
            long writerConnection = writer.get(30, TimeUnit.SECONDS);
            // A sequential control may legitimately reuse a pooled connection after commit.
            if (deactivationFirst) assertThat(writerConnection).isNotEqualTo(deactivationConnection);

            var stored = jdbc.queryForMap("select enabled,password,team_team_id from peer_evaluation_user where id=?", id);
            int jwtStatus = mvc.perform(get("/api/v1/students/" + id).header("Authorization", "Bearer " + jwt))
                    .andReturn().getResponse().getStatus();
            assertSoftly(soft -> {
                soft.assertThat(stored.get("enabled")).as("persisted disabled account after both commits").isEqualTo(false);
                soft.assertThat(jwtStatus).as("previously issued JWT after both commits").isEqualTo(401);
                Object expectedTeam = switch (operation) {
                    case "assign", "transfer" -> fixture[2];
                    case "remove" -> null;
                    default -> fixture[3];
                };
                soft.assertThat(stored.get("team_team_id")).isEqualTo(expectedTeam);
                soft.assertThat(passwords.matches(operation.equals("password") ? "Changed123" : "123456",
                        (String) stored.get("password"))).as("password change preserved").isTrue();
                soft.assertThat(writerSql).as("actual writer account update").hasSize(1);
                soft.assertThat(writerSql).allSatisfy(sql -> {
                    soft.assertThat(sql).doesNotContain("enabled");
                    soft.assertThat(sql).contains(operation.equals("password") ? "password=?" : "team_team_id=?");
                });
                soft.assertThat(deactivationSql).as("deactivation updates status only").hasSize(1)
                        .allSatisfy(sql -> soft.assertThat(sql.substring(sql.indexOf(" set "), sql.indexOf(" where ")))
                                .isEqualTo(" set enabled=?"));
                if (operation.equals("password")) soft.assertThat(tokens.existsById(email)).isFalse();
            });
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            tx(() -> { tokens.deleteById(email); students.deleteById(id); students.flush(); teams.deleteById(fixture[2]); return null; });
        }
    }
}
