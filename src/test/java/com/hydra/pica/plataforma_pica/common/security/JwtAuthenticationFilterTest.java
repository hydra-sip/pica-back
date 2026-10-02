package com.hydra.pica.plataforma_pica.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.security.SignatureException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

class JwtAuthenticationFilterTest {

    private final JwtService jwtService = mock(JwtService.class);
    private final VersionesDeSesion versionesDeSesion = mock(VersionesDeSesion.class);
    private final ControllerDePrueba controller = new ControllerDePrueba();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new JwtAuthenticationFilter(jwtService, versionesDeSesion))
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
        Claims claims = tokenFirmado("valido", 3);
        when(claims.get("permisos")).thenReturn(List.of("USUARIO_VER", "ROL_ASIGNAR"));
        when(versionesDeSesion.versionDeSesion(42L)).thenReturn(Optional.of(3));

        mockMvc.perform(get("/prueba").header("Authorization", "Bearer valido"))
                .andExpect(status().isOk());

        assertThat(controller.authentication).isNotNull();
        assertThat(controller.authentication.getName()).isEqualTo("42");
        assertThat(controller.authentication.getAuthorities())
                .extracting("authority")
                .containsExactlyInAnyOrder("USUARIO_VER", "ROL_ASIGNAR");
    }

    @Test
    void bearerConFirmaInvalidaPasaLaCadenaSinAutenticar() throws Exception {
        when(jwtService.validar("vencido")).thenThrow(new SignatureException("firma inválida"));

        mockMvc.perform(get("/prueba").header("Authorization", "Bearer vencido"))
                .andExpect(status().isOk());

        assertThat(controller.authentication).isNull();
    }

    @Test
    void bearerConVersionDeSesionViejaNoAutenticaYMarcaSesionRevocada() throws Exception {
        // robo detectado, baja, bloqueo o cambio de roles/clave: la versión del usuario subió
        tokenFirmado("viejo", 3);
        when(versionesDeSesion.versionDeSesion(42L)).thenReturn(Optional.of(4));

        mockMvc.perform(get("/prueba").header("Authorization", "Bearer viejo"))
                .andExpect(status().isOk());

        assertThat(controller.authentication).isNull();
        assertThat(controller.jwtError).isEqualTo("SESION_REVOCADA");
    }

    @Test
    void bearerDeUsuarioDadoDeBajaNoAutenticaYMarcaSesionRevocada() throws Exception {
        tokenFirmado("de-baja", 0);
        when(versionesDeSesion.versionDeSesion(42L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/prueba").header("Authorization", "Bearer de-baja"))
                .andExpect(status().isOk());

        assertThat(controller.authentication).isNull();
        assertThat(controller.jwtError).isEqualTo("SESION_REVOCADA");
    }

    @Test
    void bearerSinVersionDeSesionNoAutenticaNiConsultaLaBase() throws Exception {
        tokenFirmado("anterior-a-ce2-2", null);

        mockMvc.perform(get("/prueba").header("Authorization", "Bearer anterior-a-ce2-2"))
                .andExpect(status().isOk());

        assertThat(controller.authentication).isNull();
        assertThat(controller.jwtError).isEqualTo("SESION_REVOCADA");
        verifyNoInteractions(versionesDeSesion);
    }

    private Claims tokenFirmado(String token, Integer versionSesion) {
        Jws<Claims> jws = mock(Jws.class);
        Claims claims = mock(Claims.class);
        when(jwtService.validar(token)).thenReturn(jws);
        when(jws.getPayload()).thenReturn(claims);
        when(claims.getSubject()).thenReturn("42");
        when(claims.get(JwtService.CLAIM_VERSION_SESION, Integer.class)).thenReturn(versionSesion);
        return claims;
    }

    @RestController
    static class ControllerDePrueba {

        private Authentication authentication;
        private Object jwtError;

        @GetMapping("/prueba")
        String prueba(HttpServletRequest request) {
            authentication = SecurityContextHolder.getContext().getAuthentication();
            jwtError = request.getAttribute("jwt-error");
            return "ok";
        }
    }
}
