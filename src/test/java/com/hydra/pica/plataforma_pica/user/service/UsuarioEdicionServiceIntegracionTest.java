package com.hydra.pica.plataforma_pica.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.config.AdminConfig;
import com.hydra.pica.plataforma_pica.common.config.JpaAuditingConfig;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.security.SecurityContextCurrentUserProvider;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.dto.UsuarioDetalle;
import com.hydra.pica.plataforma_pica.user.dto.UsuarioUpdateRequest;
import com.hydra.pica.plataforma_pica.user.dto.UsuarioUpdateRequest.EstadoEditable;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import jakarta.persistence.EntityManager;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Modificar, baja y reactivar de un usuario (PICA-116) contra Postgres real. Lo que con mocks no se
 * ve: el {@code @SQLRestriction} escondiendo o no a usuarios y personas eliminados, y que los nombres
 * de los índices únicos sean los que {@code UsuarioEdicionService} traduce a 409.
 */
@DataJpaTest(properties = "app.admin.username=admin")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        JpaAuditingConfig.class,
        SecurityContextCurrentUserProvider.class,
        TestcontainersConfiguration.class,
        AdminConfig.class,
        UsuarioEdicionService.class
})
class UsuarioEdicionServiceIntegracionTest {

    @MockitoBean
    private PasswordEncoder passwordEncoder;

    private final UsuarioEdicionService servicio;
    private final PersonaRepository personaRepository;
    private final UsuarioRepository usuarioRepository;
    private final EntityManager entityManager;

    @Autowired
    UsuarioEdicionServiceIntegracionTest(
            UsuarioEdicionService servicio,
            PersonaRepository personaRepository,
            UsuarioRepository usuarioRepository,
            EntityManager entityManager) {
        this.servicio = servicio;
        this.personaRepository = personaRepository;
        this.usuarioRepository = usuarioRepository;
        this.entityManager = entityManager;
    }

    // --- reactivar y baja --------------------------------------------------------

    @Test
    @DisplayName("reactivar: trae de vuelta al usuario y a su persona, que estaban dados de baja")
    void reactivarUsuarioYPersonaEliminados() {
        Persona persona = persona("Ana", "Gómez", "70000001");
        Usuario usuario = usuario("edicion.reactivar", persona, EstadoUsuario.BLOQUEADO);
        persona.setEliminadoEn(Instant.now());
        usuario.setEliminadoEn(Instant.now());
        personaRepository.saveAndFlush(persona);
        usuarioRepository.saveAndFlush(usuario);
        entityManager.clear();
        assertThat(usuarioRepository.findById(usuario.getId())).isEmpty();
        assertThat(personaRepository.findById(persona.getId())).isEmpty();

        UsuarioDetalle detalle = servicio.reactivar(usuario.getId());
        entityManager.flush();
        entityManager.clear();

        assertThat(detalle.eliminado()).isFalse();
        assertThat(detalle.estado()).isEqualTo(EstadoUsuario.BLOQUEADO);
        assertThat(usuarioRepository.findById(usuario.getId())).isPresent();
        assertThat(personaRepository.findById(persona.getId())).isPresent();
    }

    @Test
    @DisplayName("eliminar: es idempotente y después un PUT sobre el usuario da 404")
    void eliminarDosVeces() {
        Usuario usuario = usuario("edicion.baja", persona("Luis", "Ruiz", "70000002"), EstadoUsuario.ACTIVO);
        entityManager.clear();

        servicio.eliminar(usuario.getId());
        entityManager.flush();
        entityManager.clear();
        servicio.eliminar(usuario.getId());

        assertThat(usuarioRepository.findById(usuario.getId())).isEmpty();
        assertThat(usuarioRepository.findByIdIncluyendoEliminados(usuario.getId()))
                .get().extracting(Usuario::getEliminadoEn).isNotNull();
        assertThatThrownBy(() -> servicio.modificar(usuario.getId(), pedido("edicion.baja", "edicion.baja@example.com",
                usuario.getPersona().getId())))
                .hasMessageContaining("No existe el usuario");
    }

    // --- modificar ---------------------------------------------------------------

    @Test
    @DisplayName("modificar: username o email de un usuario eliminado siguen ocupados (409)")
    void usernameYEmailDeEliminadosOcupados() {
        Usuario eliminado = usuario("edicion.viejo", persona("Eli", "Minado", "70000003"), EstadoUsuario.ACTIVO);
        eliminado.setEliminadoEn(Instant.now());
        usuarioRepository.saveAndFlush(eliminado);
        Usuario vivo = usuario("edicion.vivo", persona("Vivo", "Vivo", "70000004"), EstadoUsuario.ACTIVO);
        entityManager.clear();
        Long personaId = vivo.getPersona().getId();

        assertThatThrownBy(() -> servicio.modificar(vivo.getId(),
                pedido("edicion.viejo", "edicion.vivo@example.com", personaId)))
                .isInstanceOfSatisfying(ConflictoException.class,
                        e -> assertThat(e.getCodigo()).isEqualTo(CodigoError.USERNAME_DUPLICADO));
        assertThatThrownBy(() -> servicio.modificar(vivo.getId(),
                pedido("edicion.vivo", "edicion.viejo@example.com", personaId)))
                .isInstanceOfSatisfying(ConflictoException.class,
                        e -> assertThat(e.getCodigo()).isEqualTo(CodigoError.EMAIL_DUPLICADO));
    }

    @Test
    @DisplayName("modificar: cambiar el email deja al usuario PENDIENTE_VERIFICACION y sin verificar")
    void cambiarElEmailLoDejaPendiente() {
        Usuario usuario = usuario("edicion.email", persona("Ema", "Il", "70000005"), EstadoUsuario.ACTIVO);
        entityManager.clear();

        UsuarioDetalle detalle = servicio.modificar(usuario.getId(),
                pedido("edicion.email", "edicion.nuevo@example.com", usuario.getPersona().getId()));
        entityManager.flush();
        entityManager.clear();

        assertThat(detalle.estado()).isEqualTo(EstadoUsuario.PENDIENTE_VERIFICACION);
        Usuario guardado = usuarioRepository.findById(usuario.getId()).orElseThrow();
        assertThat(guardado.getEmail()).isEqualTo("edicion.nuevo@example.com");
        assertThat(guardado.isEmailVerificado()).isFalse();
        assertThat(guardado.getEstado()).isEqualTo(EstadoUsuario.PENDIENTE_VERIFICACION);
    }

    // --- índices únicos ----------------------------------------------------------

    @Test
    @DisplayName("El índice único de username se llama uq_usuario_username_lower y no distingue mayúsculas")
    void indiceDeUsername() {
        usuario("edicion.dup", persona("Uno", "Uno", "70000006"), EstadoUsuario.ACTIVO);
        Usuario otro = nuevoUsuario("EDICION.DUP", persona("Dos", "Dos", "70000007"), EstadoUsuario.ACTIVO);

        assertThat(constraintDe(otro)).isEqualTo("uq_usuario_username_lower");
    }

    @Test
    @DisplayName("El índice único de email se llama uq_usuario_email_lower")
    void indiceDeEmail() {
        usuario("edicion.mail1", persona("Uno", "Uno", "70000008"), EstadoUsuario.ACTIVO);
        Usuario otro = nuevoUsuario("edicion.mail2", persona("Dos", "Dos", "70000009"), EstadoUsuario.ACTIVO);
        otro.setEmail("EDICION.MAIL1@example.com");

        assertThat(constraintDe(otro)).isEqualTo("uq_usuario_email_lower");
    }

    @Test
    @DisplayName("El índice único de persona se llama uq_usuario_persona_id")
    void indiceDePersona() {
        Persona persona = persona("Uno", "Uno", "70000010");
        usuario("edicion.pers1", persona, EstadoUsuario.ACTIVO);
        Usuario otro = nuevoUsuario("edicion.pers2", persona, EstadoUsuario.ACTIVO);

        assertThat(constraintDe(otro)).isEqualTo("uq_usuario_persona_id");
    }

    // --- helpers -----------------------------------------------------------------

    /** Guarda y devuelve el nombre de la restricción que saltó, como lo lee el servicio. */
    private String constraintDe(Usuario usuario) {
        try {
            usuarioRepository.saveAndFlush(usuario);
        } catch (DataIntegrityViolationException e) {
            Throwable causa = e;
            while (causa != null && !(causa instanceof ConstraintViolationException)) {
                causa = causa.getCause();
            }
            assertThat(causa).as("causa ConstraintViolationException").isNotNull();
            return ((ConstraintViolationException) causa).getConstraintName();
        }
        throw new AssertionError("Se esperaba una violación de un índice único");
    }

    private static UsuarioUpdateRequest pedido(String username, String email, Long personaId) {
        return new UsuarioUpdateRequest(username, email, null, EstadoEditable.ACTIVO, personaId);
    }

    private Persona persona(String nombres, String apellidos, String nroDoc) {
        Persona persona = new Persona();
        persona.setNombres(nombres);
        persona.setApellidos(apellidos);
        persona.setTipoDoc("DNI");
        persona.setNroDoc(nroDoc);
        persona.setEstado(EstadoGeneral.ACTIVO);
        return personaRepository.saveAndFlush(persona);
    }

    private Usuario usuario(String username, Persona persona, EstadoUsuario estado) {
        return usuarioRepository.saveAndFlush(nuevoUsuario(username, persona, estado));
    }

    private static Usuario nuevoUsuario(String username, Persona persona, EstadoUsuario estado) {
        Usuario usuario = new Usuario();
        usuario.setPersona(persona);
        usuario.setUsername(username);
        usuario.setEmail(username.toLowerCase() + "@example.com");
        usuario.setEstado(estado);
        usuario.setEmailVerificado(true);
        return usuario;
    }
}
