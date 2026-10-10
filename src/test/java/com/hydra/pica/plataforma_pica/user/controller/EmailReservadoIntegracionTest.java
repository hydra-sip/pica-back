package com.hydra.pica.plataforma_pica.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.email.EmailService;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import com.hydra.pica.plataforma_pica.user.service.AuthService;
import com.hydra.pica.plataforma_pica.user.service.NuevoUsuario;
import com.hydra.pica.plataforma_pica.user.service.UsuarioService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.MockMvc;

/**
 * CE2-5: el email solo lo reserva una cuenta que se verificó alguna vez. De punta a punta (HTTP,
 * listener de verificación después del commit y Postgres), sin {@code @Transactional}: el link sale
 * recién cuando el registro se confirma. Cada test usa sus propios mails y documentos.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
class EmailReservadoIntegracionTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AuthService authService;
    @Autowired private UsuarioService usuarioService;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean private EmailService emailService;

    @Test
    void registrarDosVecesElMismoMailSinVerificarDejaSoloElSegundoLink() throws Exception {
        String sufijo = sufijo();
        String email = "repetido-" + sufijo + "@example.com";
        String documento = documento();

        // el dueño real vuelve a registrarse con el mismo username y documento: también quedan libres
        Long primero = registrar("rep" + sufijo, email, "Intruso2026", documento).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString().transform(this::id);
        Long personaDelPrimero = personaDe(primero);
        Long segundo = registrar("rep" + sufijo, email, "Duenio2026", documento).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString().transform(this::id);

        assertThat(segundo).isNotEqualTo(primero);
        assertThat(contar("SELECT count(*) FROM usuario WHERE id = ?", primero)).isZero();
        assertThat(contar("SELECT count(*) FROM persona WHERE id = ?", personaDelPrimero)).isZero();

        List<String> links = linksEnviadosA(email);
        assertThat(links).hasSize(2);
        verificar(links.get(0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("TOKEN_INVALIDO"));
        verificar(links.get(1)).andExpect(status().isNoContent());

        login(email, "Intruso2026").andExpect(status().isUnauthorized());
        login(email, "Duenio2026").andExpect(status().isOk());
    }

    @Test
    void conUnRegistroPendienteElLoginConGoogleCreaOtraCuentaYLaClaveDelRegistroYaNoEntra() throws Exception {
        String sufijo = sufijo();
        String email = "google-" + sufijo + "@example.com";
        Long pendiente = registrar("goo" + sufijo, email, "Intruso2026", documento())
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString().transform(this::id);

        Usuario deGoogle = authService.procesarLoginGoogle("sub-" + sufijo, email, true, "Dueño", "Real");

        assertThat(deGoogle.getId()).isNotEqualTo(pendiente);
        assertThat(deGoogle.getPasswordHash()).isNull();
        assertThat(usuarioRepository.findById(deGoogle.getId()).orElseThrow().getPrimeraVerificacionEn()).isNotNull();
        assertThat(contar("SELECT count(*) FROM usuario WHERE id = ?", pendiente)).isZero();
        login(email, "Intruso2026")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("CREDENCIALES_INVALIDAS"));
        // y el link del registro descartado tampoco activa nada
        verificar(linksEnviadosA(email).get(0)).andExpect(status().isBadRequest());
    }

    @Test
    void unRegistroVerificadoSeVinculaConGoogleYConservaSuClave() throws Exception {
        String sufijo = sufijo();
        String email = "verificado-" + sufijo + "@example.com";
        Long registrado = registrar("ver" + sufijo, email, "Duenio2026", documento())
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString().transform(this::id);
        verificar(linksEnviadosA(email).get(0)).andExpect(status().isNoContent());

        Usuario vinculado = authService.procesarLoginGoogle("sub-" + sufijo, email, true, "Dueño", "Real");

        assertThat(vinculado.getId()).isEqualTo(registrado);
        assertThat(vinculado.getGoogleSub()).isEqualTo("sub-" + sufijo);
        login(email, "Duenio2026").andExpect(status().isOk());
    }

    @Test
    void laPersonaQueCargoUnAdminNoSeBorraConElRegistroDescartado() throws Exception {
        String sufijo = sufijo();
        String email = "conpersona-" + sufijo + "@example.com";
        String documento = documento();
        Persona cargada = new Persona();
        cargada.setNombres("Carga");
        cargada.setApellidos("Admin");
        cargada.setTipoDoc("PASAPORTE");
        cargada.setNroDoc(documento);
        cargada.setEstado(EstadoGeneral.ACTIVO);
        Long personaId = personaRepository.save(cargada).getId();
        // como si la hubiera cargado el admin 1 desde el ABM de personas
        jdbc.update("UPDATE persona SET creado_por = '1', modificado_por = '1' WHERE id = ?", personaId);

        Long primero = registrar("per" + sufijo, email, "Intruso2026", documento)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString().transform(this::id);
        assertThat(personaDe(primero)).isEqualTo(personaId);

        Long segundo = registrar("per2" + sufijo, email, "Duenio2026", documento)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString().transform(this::id);

        assertThat(personaDe(segundo)).isEqualTo(personaId);
    }

    @Test
    void unaCuentaRealConElMailCambiadoSinVerificarSigueReservandoloYNoSeVinculaConGoogle() throws Exception {
        String sufijo = sufijo();
        String email = "cambiado-" + sufijo + "@example.com";
        Persona persona = new Persona();
        persona.setNombres("Cuenta");
        persona.setApellidos("Real");
        persona.setEstado(EstadoGeneral.ACTIVO);
        Long personaId = personaRepository.save(persona).getId();
        Usuario real = usuarioService.crear(NuevoUsuario.porAdmin("real" + sufijo, "viejo-" + email, "Pica2026",
                null, null, personaId, Set.of()));
        // lo que deja UsuarioEdicionService cuando un admin le cambia el email
        jdbc.update("UPDATE usuario SET email = ?, email_verificado = false, estado = 'PENDIENTE_VERIFICACION' "
                + "WHERE id = ?", email, real.getId());

        registrar("otro" + sufijo, email, "Intruso2026", documento())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("EMAIL_DUPLICADO"));
        assertThatThrownBy(() -> authService.procesarLoginGoogle("sub-" + sufijo, email, true, "Otro", "Dueño"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCodigo()).isEqualTo(CodigoError.EMAIL_NO_VERIFICADO));

        Usuario sigue = usuarioRepository.findById(real.getId()).orElseThrow();
        assertThat(sigue.getEstado()).isEqualTo(EstadoUsuario.PENDIENTE_VERIFICACION);
        assertThat(sigue.getGoogleSub()).isNull();
    }

    private ResultActions registrar(String username, String email, String password, String documento)
            throws Exception {
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("username", username);
        cuerpo.put("email", email);
        cuerpo.put("password", password);
        cuerpo.put("nombres", "Juan");
        cuerpo.put("apellidos", "Pérez");
        cuerpo.put("tipoDoc", "PASAPORTE");
        cuerpo.put("nroDoc", documento);
        cuerpo.put("fechaNacimiento", LocalDate.of(1990, 5, 17).toString());
        return mockMvc.perform(post("/api/v1/auth/registro")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(cuerpo)));
    }

    private ResultActions verificar(String link) throws Exception {
        return mockMvc.perform(get("/api/v1/auth/verificar").param("token", link.substring(link.indexOf("token=") + 6)));
    }

    private ResultActions login(String identificador, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("identificador", identificador, "password", password))));
    }

    private List<String> linksEnviadosA(String email) {
        ArgumentCaptor<String> links = ArgumentCaptor.forClass(String.class);
        verify(emailService, atLeastOnce()).enviarVerificacion(eq(email), links.capture());
        return links.getAllValues();
    }

    private Long id(String respuesta) {
        try {
            return objectMapper.readTree(respuesta).get("id").asLong();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Long personaDe(Long usuarioId) {
        return jdbc.queryForObject("SELECT persona_id FROM usuario WHERE id = ?", Long.class, usuarioId);
    }

    private long contar(String sql, Long id) {
        return jdbc.queryForObject(sql, Long.class, id);
    }

    private static String sufijo() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private static String documento() {
        return ("CE25" + sufijo()).toUpperCase(Locale.ROOT);
    }
}
