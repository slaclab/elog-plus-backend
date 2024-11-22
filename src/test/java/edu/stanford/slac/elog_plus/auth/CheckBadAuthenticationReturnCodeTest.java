package edu.stanford.slac.elog_plus.auth;

import edu.stanford.slac.ad.eed.baselib.auth.JWTHelper;
import edu.stanford.slac.ad.eed.baselib.config.AppProperties;
import edu.stanford.slac.ad.eed.baselib.exception.NotAuthenticated;
import edu.stanford.slac.elog_plus.v1.controller.TestControllerHelperService;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.PropertySource;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@SpringBootTest()
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@PropertySource("classpath:application.yml")
@ExtendWith(MockitoExtension.class)
@ActiveProfiles({"test"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class CheckBadAuthenticationReturnCodeTest {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AppProperties appProperties;
    @Autowired
    private JWTHelper jwtHelper;
    @Autowired
    private TestControllerHelperService testControllerHelperService;

    @Test
    public void testMeAuthCall() {
        MockHttpServletRequestBuilder getBuilder = get("/v1/users/me");
        getBuilder.header(appProperties.getUserHeaderName(), jwtHelper.generateJwt("user1@slac.stanford.edu"));
        MvcResult result = assertDoesNotThrow(
                ()->mockMvc.perform(getBuilder)
                        .andExpect(status().isOk())
                        .andReturn()
        );
        String resultString = assertDoesNotThrow(()->result.getResponse().getContentAsString());
        assertThat(resultString).isNotNull();
    }

    @Test
    public void testMeAuthCallWithCorruptedJWT() {
        MockHttpServletRequestBuilder getBuilder = get("/v1/users/me");
        getBuilder.header(appProperties.getUserHeaderName(), returnCorruptedJWT(jwtHelper.generateJwt("user1@slac.stanford.edu")));
        MvcResult result = assertDoesNotThrow(
                ()->mockMvc.perform(getBuilder)
                        .andExpect(status().isUnauthorized())
                        .andReturn()
        );
        assertThat(result).isNotNull();
    }

    @Test
    public void testMeAuthCallWithExpiredJWT() {
        MockHttpServletRequestBuilder getBuilder = get("/v1/users/me");
        getBuilder.header(appProperties.getUserHeaderName(), generateJwt("user1@slac.stanford.edu", LocalDateTime.now().minusDays(2), LocalDateTime.now().minusDays(1)));
        MvcResult result = assertDoesNotThrow(
                ()->mockMvc.perform(getBuilder)
                        .andExpect(status().isUnauthorized())
                        .andReturn()
        );
        assertThat(result).isNotNull();
    }

    @Test
    public void testNotFoundResource() {
        MockHttpServletRequestBuilder getBuilder = get("/v1/xyz/me");
        getBuilder.header(appProperties.getUserHeaderName(), jwtHelper.generateJwt("user1@slac.stanford.edu"));
        MvcResult result = assertDoesNotThrow(
                ()->mockMvc.perform(getBuilder)
                        .andExpect(status().isOk())
                        .andReturn()
        );
        String resultString = assertDoesNotThrow(()->result.getResponse().getContentAsString());
        assertThat(resultString).isNotNull().contains("Error - ELog Plus");
    }

    /**
     * Test that the server returns a 401 Unauthorized status code when the JWT is missing
     */
    String returnCorruptedJWT(String jwt) {
        // Split the JWT into parts
        String[] jwtParts = jwt.split("\\.");
        if (jwtParts.length != 3) {
            throw new IllegalArgumentException("Invalid JWT format");
        }

        // Corrupt the payload by appending some junk data
        String corruptedPayload = jwtParts[1] + "corrupt";

        // Reassemble the corrupted JWT
        return jwtParts[0] + "." + corruptedPayload + "." + jwtParts[2];
    }

    /**
     * Test that the server returns a 401 Unauthorized status code when the JWT is missing
     */
    public String generateJwt(String email, LocalDateTime nowDateTime, LocalDateTime expirationDateTime) {
        Date now = Date.from(nowDateTime.atZone(ZoneId.systemDefault()).toInstant());
        Date expiration = Date.from(expirationDateTime.atZone(ZoneId.systemDefault()).toInstant());
        Map<String, Object> claims = new HashMap();
        claims.put("email", email);
        return ((JwtBuilder)((JwtBuilder) Jwts.builder().setHeader(Map.of("typ", "JWT")).addClaims(claims).setIssuedAt(now)).setExpiration(expiration)).signWith(jwtHelper.getKey()).compact();
    }
}
