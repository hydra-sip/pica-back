package com.hydra.pica.plataforma_pica.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class JwtAuthenticationFilterTest {

    private final JwtService jwtService = mock(JwtService.class);
    private final ControllerDePrueba controller = new ControllerDePrueba();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new JwtAuthenticationFilter(jwtService))
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void sinAuthorizationPasaLaCadenaSinAutenticar() throws Exception {
        mockMvc.perform(get("/prueba"))
                .andExpect(status().isOk());

        assertThat(controller.authentication).isNull();
    }

    @Test
    void bearerValidoAutenticaConElUsuarioYLosPermisosDelToken() throws Exception {
        Jws<Claims> jws = mock(Jws.class);
        Claims claims = mock(Claims.class);
        when(jwtService.validar("valido")).thenReturn(jws);
        when(jws.getPayload()).thenReturn(claims);
        when(claims.getSubject()).thenReturn("42");
        when(claims.get("permisos")).thenReturn(List.of("USUARIO_VER", "ROL_ASIGNAR"));

        mockMvc.perform(get("/prueba").header("Authorization", "Bearer valido"))
                .andExpect(status().isOk());

        assertThat(controller.authentication).isNotNull();
        assertThat(controller.authentication.getName()).isEqualTo("42");
        assertThat(controller.authentication.getAuthorities())
                .extracting("authority")
                .containsExactlyInAnyOrder("USUARIO_VER", "ROL_ASIGNAR");
    }

    @Test
    void bearerInvalidoPasaLaCadenaSinAutenticar() throws Exception {
        when(jwtService.validar("vencido")).thenThrow(new IllegalArgumentException("token inválido"));

        mockMvc.perform(get("/prueba").header("Authorization", "Bearer vencido"))
                .andExpect(status().isOk());

        assertThat(controller.authentication).isNull();
    }

    @RestController
    static class ControllerDePrueba {

        private Authentication authentication;

        @GetMapping("/prueba")
        String prueba() {
            authentication = SecurityContextHolder.getContext().getAuthentication();
            return "ok";
        }
    }
}
