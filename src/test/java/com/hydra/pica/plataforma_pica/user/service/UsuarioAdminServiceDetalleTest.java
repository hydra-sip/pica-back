package com.hydra.pica.plataforma_pica.user.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.config.AdminConfig;
import com.hydra.pica.plataforma_pica.common.config.JpaAuditingConfig;
import com.hydra.pica.plataforma_pica.common.security.SecurityContextCurrentUserProvider;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.dto.UsuarioDetalle;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@DataJpaTest(properties = "app.admin.username=admin")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        JpaAuditingConfig.class,
        SecurityContextCurrentUserProvider.class,
        TestcontainersConfiguration.class,
        AdminConfig.class,
        JacksonAutoConfiguration.class,
        UsuarioAdminService.class
})
class UsuarioAdminServiceDetalleTest {

    @MockitoBean
    private UsuarioService usuarioService;

    @MockitoBean
    private UsuarioRolService usuarioRolService;

    private final UsuarioAdminService servicio;
    private final PersonaRepository personaRepository;
    private final UsuarioRepository usuarioRepository;
    private final EntityManager entityManager;

    @Autowired
    UsuarioAdminServiceDetalleTest(
            UsuarioAdminService servicio,
            PersonaRepository personaRepository,
            UsuarioRepository usuarioRepository,
            EntityManager entityManager) {
        this.servicio = servicio;
        this.personaRepository = personaRepository;
        this.usuarioRepository = usuarioRepository;
        this.entityManager = entityManager;
    }

    @Test
    @DisplayName("La ficha de un usuario dado de baja abre aunque su persona también esté dada de baja")
    void detalleConUsuarioYPersonaEliminados() {
        Persona persona = crearPersona("Luis", "Perez", "DET-1");
        Usuario usuario = crearUsuario("detalle.baja", persona);

        Instant baja = Instant.now();
        usuario.setEliminadoEn(baja);
        usuarioRepository.saveAndFlush(usuario);
        persona.setEliminadoEn(baja);
        personaRepository.saveAndFlush(persona);
        entityManager.clear();

        // el punto de partida del bug: por el repositorio normal la persona no aparece
        assertThat(personaRepository.findById(persona.getId())).isEmpty();

        UsuarioDetalle detalle = servicio.obtenerDetalle(usuario.getId());

        assertThat(detalle.eliminado()).isTrue();
        assertThat(detalle.username()).isEqualTo("detalle.baja");
        assertThat(detalle.persona().id()).isEqualTo(persona.getId());
        assertThat(detalle.persona().nombres()).isEqualTo("Luis");
        assertThat(detalle.persona().apellidos()).isEqualTo("Perez");
    }

    @Test
    @DisplayName("La ficha de un usuario activo trae los datos de su persona")
    void detalleDeUnUsuarioActivo() {
        Persona persona = crearPersona("Ana", "Gomez", "DET-2");
        Usuario usuario = crearUsuario("detalle.activo", persona);
        entityManager.clear();

        UsuarioDetalle detalle = servicio.obtenerDetalle(usuario.getId());

        assertThat(detalle.eliminado()).isFalse();
        assertThat(detalle.persona().nombres()).isEqualTo("Ana");
        assertThat(detalle.roles()).isEmpty();
    }

    private Persona crearPersona(String nombres, String apellidos, String nroDoc) {
        Persona persona = new Persona();
        persona.setNombres(nombres);
        persona.setApellidos(apellidos);
        persona.setTipoDoc("DNI");
        persona.setNroDoc(nroDoc);
        persona.setEstado(EstadoGeneral.ACTIVO);
        return personaRepository.saveAndFlush(persona);
    }

    private Usuario crearUsuario(String username, Persona persona) {
        Usuario usuario = new Usuario();
        usuario.setPersona(persona);
        usuario.setUsername(username);
        usuario.setEmail(username + "@example.com");
        usuario.setEstado(EstadoUsuario.ACTIVO);
        usuario.setEmailVerificado(true);
        return usuarioRepository.saveAndFlush(usuario);
    }
}
