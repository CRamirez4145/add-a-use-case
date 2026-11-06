package team.projectpulse.security;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import team.projectpulse.AbstractIntegrationTest;
import team.projectpulse.student.Student;
import team.projectpulse.student.StudentController;
import team.projectpulse.user.UserRepository;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** UC-STU-deactivate-student T14/T15: actual login, RSA decoding, and filter-chain refusals. */
class ActiveAccountAuthenticationIntegrationTest extends AbstractIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JwtEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean StudentController studentController;
    private static final String BASE = "/api/v1";

    private String login(String username) throws Exception {
        var response = mvc.perform(post(BASE + "/users/login").with(httpBasic(username, "123456")))
                .andExpect(status().isOk()).andReturn().getResponse();
        return new JSONObject(response.getContentAsString()).getJSONObject("data").getString("token");
    }
    private String fixture(Object id, boolean expired, String authorities) {
        var claims = JwtClaimsSet.builder().subject("j.smith@abc.edu")
                .issuedAt(Instant.now().minusSeconds(600))
                .expiresAt(Instant.now().plusSeconds(expired ? -300 : 3600))
                .claim("authorities", authorities);
        if (id != null) claims.claim("userId", id);
        return encoder.encode(JwtEncoderParameters.from(claims.build())).getTokenValue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"j.smith@abc.edu", "b.gates@abc.edu", "b.wei@abc.edu"})
    void persistedAccountStateRevokesExistingTokenForEveryAccountType(String username) throws Exception {
        var user = users.findByUsername(username).orElseThrow();
        String token = login(username);
        mvc.perform(get(BASE + "/users/exists/unused@example.test").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        if (username.equals("b.wei@abc.edu")) {
            String reordered = fixture(user.getId(), false, "ROLE_instructor ROLE_admin");
            mvc.perform(post(BASE + "/courses/search").contentType(MediaType.APPLICATION_JSON).content("{}")
                    .header("Authorization", "Bearer " + reordered)).andExpect(status().isOk());
        }
        user.setEnabled(false); users.saveAndFlush(user);
        clearInvocations(studentController);
        mvc.perform(get(BASE + "/students/4").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        verifyNoInteractions(studentController);
        mvc.perform(post(BASE + "/users/login").with(httpBasic(username, "123456"))).andExpect(status().isUnauthorized());
    }

    @Test
    void commandRevokesStudentTokenAcrossProtectedReadAndWriteSurfaces() throws Exception {
        Student student = (Student) users.findByUsername("j.smith@abc.edu").orElseThrow();
        String token = "Bearer " + login(student.getUsername());
        String instructorToken = "Bearer " + login("b.gates@abc.edu");
        mvc.perform(get(BASE + "/students/" + student.getId()).header("Authorization", token)).andExpect(status().isOk());
        var before = List.of(jdbc.queryForList("select * from activity"), jdbc.queryForList("select * from peer_evaluation"),
                jdbc.queryForList("select * from rating"));
        mvc.perform(post(BASE + "/sections/" + student.getSection().getSectionId() + "/students/" + student.getId() + "/deactivation")
                .header("Authorization", instructorToken)).andExpect(status().isOk());
        clearInvocations(studentController);
        for (String path : List.of("/students/" + student.getId(), "/activities/1", "/evaluations/students/" + student.getId() + "/week/2023-W32",
                "/teams/" + student.getTeam().getTeamId() + "/documents/1")) {
            mvc.perform(get(BASE + path).header("Authorization", token)).andExpect(status().isUnauthorized());
        }
        for (String path : List.of("/activities", "/evaluations", "/teams/" + student.getTeam().getTeamId() + "/documents")) {
            mvc.perform(post(BASE + path).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(put(BASE + "/students/" + student.getId()).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content("{}" )).andExpect(status().isUnauthorized());
        verifyNoInteractions(studentController);
        assertThat(List.of(jdbc.queryForList("select * from activity"), jdbc.queryForList("select * from peer_evaluation"),
                jdbc.queryForList("select * from rating"))).isEqualTo(before);
    }

    @Test
    void signaturesExpiryAndMissingAccountsStillRejectBeforeControllers() throws Exception {
        var user = users.findByUsername("j.smith@abc.edu").orElseThrow();
        String valid = fixture(user.getId(), false, "ROLE_student");
        mvc.perform(get(BASE + "/students/" + user.getId()).header("Authorization", "Bearer " + valid)).andExpect(status().isOk());
        clearInvocations(studentController);
        String[] parts = valid.split("\\.");
        String tampered = parts[0] + "." + parts[1] + "." + (parts[2].startsWith("A") ? "B" : "A") + parts[2].substring(1);
        for (String invalid : List.of(fixture(user.getId(), true, "ROLE_student"), tampered,
                fixture(Integer.MAX_VALUE, false, "ROLE_student"), fixture(null, false, "ROLE_student"),
                fixture(4.5, false, "ROLE_student"), fixture(2147483648L, false, "ROLE_student"),
                fixture("bad", false, "ROLE_student"))) {
            mvc.perform(get(BASE + "/students/" + user.getId()).header("Authorization", "Bearer " + invalid))
                    .andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(studentController);
    }
}
