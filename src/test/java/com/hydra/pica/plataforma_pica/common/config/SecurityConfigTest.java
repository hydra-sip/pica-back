package com.hydra.pica.plataforma_pica.common.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Date;

import com.hydra.pica.plataforma_pica.common.security.JwtAuthenticationFilter;
import com.hydra.pica.plataforma_pica.common.security.JwtService;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = SecurityConfigTest.ControllerDePrueba.class)
@Import({
        SecurityConfig.class,
        WebConfig.class,
        SecurityConfigTest.ControllerDePrueba.class,
        SecurityConfigTest.JwtTestConfiguration.class
})
class SecurityConfigTest {

    private static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private KeyPair keyPair;

    @BeforeEach
    void limpiarContexto() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void sinHeaderResponde401NoAutenticado() throws Exception {
        mockMvc.perform(get("/prueba/protegido"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"));
    }

    @Test
    void tokenVencidoResponde401TokenVencido() throws Exception {
        String token = Jwts.builder()
                .subject("42")
                .issuedAt(Date.from(Instant.now().minusSeconds(120)))
                .expiration(Date.from(Instant.now().minusSeconds(60)))
                .signWith(keyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();

        mockMvc.perform(get("/prueba/protegido").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("TOKEN_VENCIDO"));
    }

    @Test
    void tokenConFirmaInvalidaResponde401TokenInvalido() throws Exception {
        KeyPair otraClave = generarKeyPair();
        String token = Jwts.builder()
                .subject("42")
                .signWith(otraClave.getPrivate(), Jwts.SIG.RS256)
                .compact();

        mockMvc.perform(get("/prueba/protegido").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("TOKEN_INVALIDO"));
    }

    private KeyPair generarKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    @TestConfiguration
    static class JwtTestConfiguration {

        @Bean
        KeyPair jwtTestKeyPair() throws Exception {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        }

        @Bean
        JwtService jwtService(KeyPair keyPair) {
            return new JwtService(keyPair);
        }

        @Bean
        JwtAuthenticationFilter jwtAuthenticationFilter(JwtService jwtService) {
            return new JwtAuthenticationFilter(jwtService);
        }
    }

    @RestController
    static class ControllerDePrueba {

        @GetMapping("/prueba/protegido")
        String protegido() {
            return "ok";
        }
    }
}
