package com.hydra.pica.plataforma_pica.user.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import jakarta.persistence.EntityManager;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.audit.AuditConstants;
import com.hydra.pica.plataforma_pica.common.config.JpaAuditingConfig;
import com.hydra.pica.plataforma_pica.common.security.SecurityContextCurrentUserProvider;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Rol;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.domain.UsuarioRol;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        JpaAuditingConfig.class,
        SecurityContextCurrentUserProvider.class,
        TestcontainersConfiguration.class
})
class UserRepositoriesTest {

    private final PersonaRepository personaRepository;
    private final UsuarioRepository usuarioRepository;
    private final RolRepository rolRepository;
    private final UsuarioRolRepository usuarioRolRepository;
    private final EntityManager entityManager;

    @Autowired
    UserRepositoriesTest(
            PersonaRepository personaRepository,
            UsuarioRepository usuarioRepository,
            RolRepository rolRepository,
            UsuarioRolRepository usuarioRolRepository,
            EntityManager entityManager) {
        this.personaRepository = personaRepository;
        this.usuarioRepository = usuarioRepository;
        this.rolRepository = rolRepository;
        this.usuarioRolRepository = usuarioRolRepository;
        this.entityManager = entityManager;
    }

    @Test
    void usuarioPuedeBuscarsePorEmailUsernameYGoogleSubIgnorandoMayusculas() {
        Persona persona = personaRepository.saveAndFlush(nuevaPersona("usuario-busqueda"));
        Usuario usuario = usuarioRepository.saveAndFlush(
                nuevoUsuario(persona, "UsuarioBusqueda", "Usuario.Busqueda@Example.com", "google-sub-busqueda"));
        Long id = usuario.getId();
        entityManager.flush();
        entityManager.clear();

        assertThat(usuarioRepository.findByEmailIgnoreCase("USUARIO.BUSQUEDA@EXAMPLE.COM"))
                .map(Usuario::getId)
                .contains(id);
        assertThat(usuarioRepository.findByUsernameIgnoreCase("USUARIOBUSQUEDA"))
                .map(Usuario::getId)
                .contains(id);
        assertThat(usuarioRepository.findByGoogleSub("google-sub-busqueda"))
                .map(Usuario::getId)
                .contains(id);
        assertThat(usuarioRepository.existsByEmailIgnoreCase("usuario.busqueda@example.com")).isTrue();
        assertThat(usuarioRepository.existsByUsernameIgnoreCase("usuariobusqueda")).isTrue();
        assertThat(usuarioRepository.existsByGoogleSub("google-sub-busqueda")).isTrue();
    }

    @Test
    void usuarioConBajaLogicaNoApareceEnLasBusquedas() {
        Persona persona = personaRepository.saveAndFlush(nuevaPersona("usuario-baja"));
        Usuario usuario = nuevoUsuario(persona, "UsuarioBaja", "usuario.baja@example.com", "google-sub-baja");
        usuarioRepository.saveAndFlush(usuario);

        usuario.setEliminadoEn(Instant.now());
        usuarioRepository.saveAndFlush(usuario);
        entityManager.clear();

        assertThat(usuarioRepository.findByEmailIgnoreCase("usuario.baja@example.com")).isEmpty();
        assertThat(usuarioRepository.findByUsernameIgnoreCase("UsuarioBaja")).isEmpty();
        assertThat(usuarioRepository.findByGoogleSub("google-sub-baja")).isEmpty();
        assertThat(usuarioRepository.existsByEmailIgnoreCase("usuario.baja@example.com")).isFalse();
        assertThat(usuarioRepository.existsByUsernameIgnoreCase("UsuarioBaja")).isFalse();
        assertThat(usuarioRepository.existsByGoogleSub("google-sub-baja")).isFalse();
    }

    @Test
    void personaPuedeBuscarsePorDocumentoYVerificarExistencia() {
        Persona persona = personaRepository.saveAndFlush(nuevaPersona("persona-documento"));
        Long id = persona.getId();
        entityManager.flush();
        entityManager.clear();

        assertThat(personaRepository.findByTipoDocAndNroDoc("DNI", persona.getNroDoc()))
                .map(Persona::getId)
                .contains(id);
        assertThat(personaRepository.existsByTipoDocAndNroDoc("DNI", persona.getNroDoc())).isTrue();
    }

    @Test
    void personaMantieneAuditoriaAlCrearYModificar() throws InterruptedException {
        Persona persona = personaRepository.saveAndFlush(nuevaPersona("persona-auditoria"));
        Long id = persona.getId();
        Instant creadoEn = persona.getCreadoEn();
        Instant modificadoEn = persona.getModificadoEn();

        assertThat(persona.getCreadoPor()).isEqualTo(AuditConstants.SISTEMA);
        assertThat(persona.getModificadoPor()).isEqualTo(AuditConstants.SISTEMA);
        assertThat(creadoEn).isNotNull();
        assertThat(modificadoEn).isNotNull();

        Thread.sleep(10);
        persona.setNombres("Nombre actualizado");
        personaRepository.saveAndFlush(persona);
        entityManager.clear();

        Persona personaRecargada = personaRepository.findById(id).orElseThrow();
        assertThat(personaRecargada.getNombres()).isEqualTo("Nombre actualizado");
        assertThat(personaRecargada.getCreadoEn().truncatedTo(ChronoUnit.MICROS))
                .isEqualTo(creadoEn.truncatedTo(ChronoUnit.MICROS));
        assertThat(personaRecargada.getModificadoEn()).isAfter(modificadoEn);
    }

    @Test
    void usuarioRolPuedeCrearseConsultarseYDarseDeBajaLogica() {
        Persona persona = personaRepository.saveAndFlush(nuevaPersona("usuario-rol"));
        Usuario usuario = usuarioRepository.saveAndFlush(
                nuevoUsuario(persona, "UsuarioRol", "usuario.rol@example.com", "google-sub-rol"));
        Rol rol = rolRepository.findByNombre("PARTICIPANTE").orElseThrow();
        entityManager.flush();

        UsuarioRol usuarioRol = new UsuarioRol(usuario, rol);
        usuarioRol.setAsignadoEn(Instant.now());
        usuarioRol.setAsignadoPor(AuditConstants.SISTEMA);
        usuarioRolRepository.saveAndFlush(usuarioRol);
        entityManager.clear();

        UsuarioRol asignacionPersistida = usuarioRolRepository.findByUsuarioId(usuario.getId())
                .stream()
                .findFirst()
                .orElseThrow();
        assertThat(asignacionPersistida.getId().getUsuarioId()).isEqualTo(usuario.getId());
        assertThat(asignacionPersistida.getId().getRolId()).isEqualTo(rol.getId());
        assertThat(usuarioRolRepository.existsByUsuarioIdAndRolId(usuario.getId(), rol.getId())).isTrue();

        asignacionPersistida.setEliminadoEn(Instant.now());
        asignacionPersistida.setEliminadoPor(AuditConstants.SISTEMA);
        usuarioRolRepository.saveAndFlush(asignacionPersistida);
        entityManager.clear();

        assertThat(usuarioRolRepository.findByUsuarioId(usuario.getId())).isEmpty();
        assertThat(usuarioRolRepository.existsByUsuarioIdAndRolId(usuario.getId(), rol.getId())).isFalse();
    }

    @Test
    void usuarioRolRechazaUsuarioNoPersistido() {
        Persona persona = nuevaPersona("usuario-rol-sin-id");
        Usuario usuarioSinId = nuevoUsuario(persona, "UsuarioSinId", "usuario.sin.id@example.com", "google-sub-sin-id");
        Rol rol = rolRepository.findByNombre("PARTICIPANTE").orElseThrow();
        Rol rolSinId = new Rol();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new UsuarioRol(usuarioSinId, rol));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new UsuarioRol(usuarioSinId, rolSinId));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new UsuarioRol(null, rol));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new UsuarioRol(usuarioSinId, null));
    }

    private Persona nuevaPersona(String identificador) {
        Persona persona = new Persona();
        persona.setNombres("Nombre " + identificador);
        persona.setApellidos("Apellido " + identificador);
        persona.setTipoDoc("DNI");
        persona.setNroDoc("DOC-" + identificador);
        persona.setEstado(EstadoGeneral.ACTIVO);
        return persona;
    }

    private Usuario nuevoUsuario(
            Persona persona,
            String username,
            String email,
            String googleSub) {
        Usuario usuario = new Usuario();
        usuario.setPersona(persona);
        usuario.setUsername(username);
        usuario.setEmail(email);
        usuario.setGoogleSub(googleSub);
        usuario.setEstado(EstadoUsuario.ACTIVO);
        usuario.setEmailVerificado(false);
        return usuario;
    }
}
