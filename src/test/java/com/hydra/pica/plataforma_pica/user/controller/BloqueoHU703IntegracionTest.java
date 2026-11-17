package com.hydra.pica.plataforma_pica.user.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import com.hydra.pica.plataforma_pica.user.service.NuevoUsuario;
import com.hydra.pica.plataforma_pica.user.service.UsuarioService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * HU-703 de punta a punta: HTTP con tokens reales de cada rol, filtro JWT y Postgres. Cada test usa
 * sus propios usuarios, así no se pisan entre sí.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
class BloqueoHU703IntegracionTest {

    private static final String CLAVE = "Pica2026";
    private static final AtomicInteger SECUENCIA = new AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UsuarioService usuarioService;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UsuarioRepository usuarioRepository;

    // ---- pruebas de aceptación de HU-703

    @Test
    void soporteBloqueaAUnUsuarioYQuedaBloqueado() throws Exception {
        String soporte = tokenDe(usuarioCon("SOPORTE"));
        Long victima = idDe(usuarioCon("PARTICIPANTE"));

        bloquear(soporte, victima)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("BLOQUEADO"));
    }

    @Test
    void elAdministradorTambienBloquea() throws Exception {
        String admin = tokenDe(usuarioCon("ADMINISTRADOR"));
        Long victima = idDe(usuarioCon("PARTICIPANTE"));

        bloquear(admin, victima).andExpect(status().isOk());
    }

    @Test
    void bloquearAUnUsuarioInexistenteDa404() throws Exception {
        String soporte = tokenDe(usuarioCon("SOPORTE"));

        bloquear(soporte, 987_654_321L)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("USUARIO_NO_ENCONTRADO"));
    }

    @Test
    void bloquearAUnoYaBloqueadoDa409() throws Exception {
        String soporte = tokenDe(usuarioCon("SOPORTE"));
        Long victima = idDe(usuarioCon("PARTICIPANTE"));
        bloquear(soporte, victima).andExpect(status().isOk());

        bloquear(soporte, victima)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("USUARIO_YA_BLOQUEADO"));
    }

    @Test
    void sinElRolDeSoporteNoSePuedeBloquear() throws Exception {
        String participante = tokenDe(usuarioCon("PARTICIPANTE"));
        Long victima = idDe(usuarioCon("PARTICIPANTE"));

        bloquear(participante, victima).andExpect(status().isForbidden());
    }

    // ---- lo que Soporte puede y no puede hacer en el admin de usuarios

    @Test
    void soporteVeLaListaDeUsuarios() throws Exception {
        String soporte = tokenDe(usuarioCon("SOPORTE"));

        mockMvc.perform(get("/api/v1/admin/usuarios").header("Authorization", "Bearer " + soporte))
                .andExpect(status().isOk());
    }

    @Test
    void soporteDesbloquea() throws Exception {
        String soporte = tokenDe(usuarioCon("SOPORTE"));
        Long victima = idDe(usuarioCon("PARTICIPANTE"));
        bloquear(soporte, victima).andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/admin/usuarios/{id}/bloqueo", victima)
                        .header("Authorization", "Bearer " + soporte))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("ACTIVO"));
    }

    @Test
    void soporteNoReseteaContrasenias() throws Exception {
        String soporte = tokenDe(usuarioCon("SOPORTE"));
        Long victima = idDe(usuarioCon("PARTICIPANTE"));

        mockMvc.perform(put("/api/v1/admin/usuarios/{id}/password", victima)
                        .header("Authorization", "Bearer " + soporte)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("password", "Otra2026x"))))
                .andExpect(status().isForbidden());
    }

    // ---- el organizador bloqueado pierde el acceso en el momento

    @Test
    void elOrganizadorBloqueadoNoPuedePublicarConSuTokenViejo() throws Exception {
        String organizador = usuarioCon("ORGANIZADOR");
        String suToken = tokenDe(organizador);
        bloquear(tokenDe(usuarioCon("SOPORTE")), idDe(organizador)).andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/torneos/{id}/publicar", 1L)
                        .header("Authorization", "Bearer " + suToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"terminosAceptados\":true}"))
                .andExpect(status().isUnauthorized());
    }

    // ---- el bloqueo queda en el libro de auditoría

    @Test
    void bloquearQuedaEnLaAuditoriaConQuienMotivoYFechaDeFin() throws Exception {
        String soporte = usuarioCon("SOPORTE");
        Long victima = idDe(usuarioCon("PARTICIPANTE"));
        String hasta = "2099-01-01T00:00:00Z";

        mockMvc.perform(post("/api/v1/admin/usuarios/{id}/bloqueo", victima)
                        .header("Authorization", "Bearer " + tokenDe(soporte))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("motivo", "Insultos en el chat", "hasta", hasta))))
                .andExpect(status().isOk());

        auditoriaDe(victima, "BLOQUEO")
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].username").value(soporte))
                .andExpect(jsonPath("$.content[0].detalle.motivo").value("Insultos en el chat"))
                .andExpect(jsonPath("$.content[0].detalle.hasta").value(hasta));
    }

    @Test
    void unBloqueoSinFechaDeFinQuedaComoIndefinido() throws Exception {
        Long victima = idDe(usuarioCon("PARTICIPANTE"));
        bloquear(tokenDe(usuarioCon("SOPORTE")), victima).andExpect(status().isOk());

        auditoriaDe(victima, "BLOQUEO")
                .andExpect(jsonPath("$.content[0].detalle.hasta").value("indefinido"));
    }

    @Test
    void desbloquearQuedaEnLaAuditoria() throws Exception {
        String soporte = usuarioCon("SOPORTE");
        String token = tokenDe(soporte);
        Long victima = idDe(usuarioCon("PARTICIPANTE"));
        bloquear(token, victima).andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/admin/usuarios/{id}/bloqueo", victima).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        auditoriaDe(victima, "DESBLOQUEO")
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].username").value(soporte))
                .andExpect(jsonPath("$.content[0].detalle.motivo").value("Incumple las condiciones de uso"));
    }

    @Test
    void elBloqueoQueVenceAlEntrarQuedaComoDesbloqueoDelSistema() throws Exception {
        String victima = usuarioCon("PARTICIPANTE");
        Long id = idDe(victima);
        bloquear(tokenDe(usuarioCon("SOPORTE")), id).andExpect(status().isOk());
        jdbc.update("UPDATE bloqueo SET desde = now() - interval '2 days', hasta = now() - interval '1 day' "
                + "WHERE usuario_id = ?", id);

        tokenDe(victima);

        auditoriaDe(id, "DESBLOQUEO")
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].usuarioId").doesNotExist())
                .andExpect(jsonPath("$.content[0].detalle.vencido").value(true));
    }

    // ---- un solo camino para el bloqueo: /bloqueo, con permiso, motivo y auditoría

    @Test
    void editarAUnUsuarioNoLoBloquea() throws Exception {
        String username = usuarioCon("PARTICIPANTE");

        editar(username, "BLOQUEADO")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("BLOQUEO_POR_ENDPOINT_PROPIO"));
    }

    @Test
    void editarAUnBloqueadoNoLoDesbloquea() throws Exception {
        String username = usuarioCon("PARTICIPANTE");
        bloquear(tokenDe(usuarioCon("SOPORTE")), idDe(username)).andExpect(status().isOk());

        editar(username, "ACTIVO")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("BLOQUEO_POR_ENDPOINT_PROPIO"));
    }

    @Test
    void editarMandandoElMismoEstadoSigueAndando() throws Exception {
        String username = usuarioCon("PARTICIPANTE");
        bloquear(tokenDe(usuarioCon("SOPORTE")), idDe(username)).andExpect(status().isOk());

        editar(username, "BLOQUEADO")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("BLOQUEADO"));
    }

    @Test
    void elAltaNoAceptaUnUsuarioBloqueado() throws Exception {
        Persona persona = persona();
        String username = "hu703alta" + SECUENCIA.incrementAndGet();

        mockMvc.perform(post("/api/v1/admin/usuarios")
                        .header("Authorization", "Bearer " + tokenDe(usuarioCon("ADMINISTRADOR")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username, "email", username + "@example.com",
                                "passwordTemporal", CLAVE, "estado", "BLOQUEADO",
                                "personaId", persona.getId(), "roles", List.of()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("BLOQUEO_POR_ENDPOINT_PROPIO"));
    }

    // ---- ayudas

    private ResultActions bloquear(String token, Long usuarioId) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/usuarios/{id}/bloqueo", usuarioId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("motivo", "Incumple las condiciones de uso"))));
    }

    private ResultActions auditoriaDe(Long usuarioId, String accion) throws Exception {
        return mockMvc.perform(get("/api/v1/admin/auditoria")
                        .param("entidad", "USUARIO")
                        .param("entidadId", usuarioId.toString())
                        .param("accion", accion)
                        .header("Authorization", "Bearer " + tokenDe(usuarioCon("ADMINISTRADOR"))))
                .andExpect(status().isOk());
    }

    private ResultActions editar(String username, String estado) throws Exception {
        Long id = idDe(username);
        Long personaId = usuarioRepository.findById(id).orElseThrow().getPersona().getId();
        return mockMvc.perform(put("/api/v1/admin/usuarios/{id}", id)
                .header("Authorization", "Bearer " + tokenDe(usuarioCon("ADMINISTRADOR")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "username", username, "email", username + "@example.com",
                        "estado", estado, "personaId", personaId))));
    }

    private Persona persona() {
        Persona persona = new Persona();
        persona.setNombres("Bloqueo");
        persona.setApellidos("Prueba");
        persona.setEstado(EstadoGeneral.ACTIVO);
        return personaRepository.save(persona);
    }

    private String usuarioCon(String rol) {
        String username = "hu703u" + SECUENCIA.incrementAndGet();
        Persona persona = persona();
        usuarioService.crear(NuevoUsuario.porAdmin(username, username + "@example.com", CLAVE, null, null,
                persona.getId(), Set.of()));
        // el alta por admin valida los roles contra los permisos de quien la hace: acá no hay nadie
        jdbc.update("INSERT INTO usuario_rol (usuario_id, rol_id) SELECT u.id, r.id FROM usuario u, rol r "
                + "WHERE u.username = ? AND r.nombre = ?", username, rol);
        return username;
    }

    private Long idDe(String username) {
        return usuarioRepository.findByUsernameIgnoreCase(username).orElseThrow().getId();
    }

    private String tokenDe(String username) throws Exception {
        String respuesta = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("identificador", username, "password", CLAVE))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(respuesta).get("accessToken").asText();
    }
}
