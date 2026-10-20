package com.hydra.pica.plataforma_pica.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.audit.AuditConstants;
import com.hydra.pica.plataforma_pica.common.config.JpaAuditingConfig;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.security.SecurityContextCurrentUserProvider;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Rol;
import com.hydra.pica.plataforma_pica.user.domain.TipoDoc;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.domain.UsuarioRol;
import com.hydra.pica.plataforma_pica.user.dto.CambioPasswordRequest;
import com.hydra.pica.plataforma_pica.user.dto.Me;
import com.hydra.pica.plataforma_pica.user.dto.MeUpdateRequest;
import com.hydra.pica.plataforma_pica.user.dto.RolMinimo;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.repository.RolRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRolRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        JpaAuditingConfig.class,
        SecurityContextCurrentUserProvider.class,
        TestcontainersConfiguration.class,
        PerfilService.class,
        PersonaService.class,
        PermisoService.class,
        BCryptPasswordEncoder.class
})
class PerfilServiceIntegracionTest {

    private final PerfilService perfilService;
    private final PersonaRepository personaRepository;
    private final UsuarioRepository usuarioRepository;
    private final RolRepository rolRepository;
    private final UsuarioRolRepository usuarioRolRepository;
    private final EntityManager entityManager;
    private final PasswordEncoder passwordEncoder;

    @Autowired
    PerfilServiceIntegracionTest(
            PerfilService perfilService,
            PersonaRepository personaRepository,
            UsuarioRepository usuarioRepository,
            RolRepository rolRepository,
            UsuarioRolRepository usuarioRolRepository,
            EntityManager entityManager,
            PasswordEncoder passwordEncoder) {
        this.perfilService = perfilService;
        this.personaRepository = personaRepository;
        this.usuarioRepository = usuarioRepository;
        this.rolRepository = rolRepository;
        this.usuarioRolRepository = usuarioRolRepository;
        this.entityManager = entityManager;
        this.passwordEncoder = passwordEncoder;
    }

    @Test
    @DisplayName("Un usuario de Google completa sus datos y datosCompletos pasa a true")
    void usuarioDeGoogleCompletaSusDatos() {
        Usuario usuario = usuarioSinDocumento("perfil.google");
        entityManager.clear();

        Me antes = perfilService.obtener(usuario.getId());
        assertThat(antes.datosCompletos()).isFalse();
        assertThat(antes.usuario().tieneContrasena()).isFalse();
        assertThat(antes.persona().nroDoc()).isNull();

        Me despues = perfilService.actualizar(usuario.getId(), completo("PRF-1"));
        entityManager.flush();
        entityManager.clear();

        assertThat(despues.datosCompletos()).isTrue();
        assertThat(despues.persona().nroDoc()).isEqualTo("PRF-1");
        assertThat(perfilService.obtener(usuario.getId()).datosCompletos()).isTrue();
    }

    @Test
    @DisplayName("Un documento que ya es de otra persona, aunque esté dada de baja, da 409 DOCUMENTO_DUPLICADO")
    void documentoDeOtraPersonaEliminada() {
        Persona eliminada = persona("Otra", "Persona", "PRF-2");
        eliminada.setEliminadoEn(Instant.now());
        personaRepository.saveAndFlush(eliminada);
        Usuario usuario = usuarioSinDocumento("perfil.duplicado");
        entityManager.clear();

        assertThatThrownBy(() -> perfilService.actualizar(usuario.getId(), completo("PRF-2")))
                .isInstanceOfSatisfying(ConflictoException.class,
                        e -> assertThat(e.getCodigo()).isEqualTo(CodigoError.DOCUMENTO_DUPLICADO));
    }

    @Test
    @DisplayName("Con el documento ya cargado: el mismo se acepta y otro da 409 DOCUMENTO_NO_EDITABLE")
    void documentoYaCargado() {
        Usuario usuario = usuarioSinDocumento("perfil.cargado");
        perfilService.actualizar(usuario.getId(), completo("PRF-3"));
        entityManager.flush();
        entityManager.clear();

        assertThat(perfilService.actualizar(usuario.getId(), completo("PRF-3")).persona().nroDoc())
                .isEqualTo("PRF-3");
        assertThatThrownBy(() -> perfilService.actualizar(usuario.getId(), completo("PRF-4")))
                .isInstanceOfSatisfying(ConflictoException.class,
                        e -> assertThat(e.getCodigo()).isEqualTo(CodigoError.DOCUMENTO_NO_EDITABLE));
    }

    @Test
    @DisplayName("El Me trae los roles del usuario y los permisos que salen de ellos")
    void rolesYPermisos() {
        Usuario usuario = usuarioSinDocumento("perfil.admin");
        Rol administrador = rolRepository.findByNombre("ADMINISTRADOR").orElseThrow();
        UsuarioRol asignacion = new UsuarioRol(usuario, administrador);
        asignacion.setAsignadoEn(Instant.now());
        asignacion.setAsignadoPor(AuditConstants.SISTEMA);
        usuarioRolRepository.saveAndFlush(asignacion);
        entityManager.clear();

        Me me = perfilService.obtener(usuario.getId());

        assertThat(me.roles()).extracting(RolMinimo::nombre).containsExactly("ADMINISTRADOR");
        assertThat(me.permisos()).contains("USUARIO_VER", "PERSONA_VER");
    }

    @Test
    @DisplayName("Cambiar la contraseña: con la actual correcta se guarda el hash nuevo; con una incorrecta da 400 y no cambia")
    void cambiarPasswordConLaActual() {
        Usuario usuario = usuarioSinDocumento("perfil.clave");
        usuario.setPasswordHash(passwordEncoder.encode("Actual123"));
        usuarioRepository.saveAndFlush(usuario);
        entityManager.clear();

        assertThatThrownBy(() -> perfilService.cambiarPassword(usuario.getId(),
                new CambioPasswordRequest("Equivocada1", "Nueva1234")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCodigo()).isEqualTo(CodigoError.PASSWORD_ACTUAL_INCORRECTA));
        entityManager.clear();
        assertThat(passwordEncoder.matches("Actual123",
                usuarioRepository.findById(usuario.getId()).orElseThrow().getPasswordHash())).isTrue();
        entityManager.clear();

        perfilService.cambiarPassword(usuario.getId(), new CambioPasswordRequest("Actual123", "Nueva1234"));
        entityManager.flush();
        entityManager.clear();

        String hash = usuarioRepository.findById(usuario.getId()).orElseThrow().getPasswordHash();
        assertThat(hash).startsWith("$2").isNotEqualTo("Nueva1234");
        assertThat(passwordEncoder.matches("Nueva1234", hash)).isTrue();
        assertThat(passwordEncoder.matches("Actual123", hash)).isFalse();
    }

    @Test
    @DisplayName("Un usuario de Google sin contraseña define la primera sin la actual")
    void googleDefinePrimeraPassword() {
        Usuario usuario = usuarioSinDocumento("perfil.google.clave");
        assertThat(usuario.getPasswordHash()).isNull();
        entityManager.clear();

        perfilService.cambiarPassword(usuario.getId(), new CambioPasswordRequest(null, "Primera1234"));
        entityManager.flush();
        entityManager.clear();

        Usuario guardado = usuarioRepository.findById(usuario.getId()).orElseThrow();
        assertThat(passwordEncoder.matches("Primera1234", guardado.getPasswordHash())).isTrue();
        assertThat(perfilService.obtener(usuario.getId()).usuario().tieneContrasena()).isTrue();
    }

    private static MeUpdateRequest completo(String nroDoc) {
        return new MeUpdateRequest("Ana", "Gomez", LocalDate.of(1990, 5, 20), "Calle 123", "1155550000",
                TipoDoc.DNI, nroDoc);
    }

    private Persona persona(String nombres, String apellidos, String nroDoc) {
        Persona persona = new Persona();
        persona.setNombres(nombres);
        persona.setApellidos(apellidos);
        persona.setTipoDoc(nroDoc == null ? null : "DNI");
        persona.setNroDoc(nroDoc);
        persona.setEstado(EstadoGeneral.ACTIVO);
        return personaRepository.saveAndFlush(persona);
    }

    private Usuario usuarioSinDocumento(String username) {
        Usuario usuario = new Usuario();
        usuario.setPersona(persona("Nombre", "Apellido", null));
        usuario.setUsername(username);
        usuario.setEmail(username + "@example.com");
        usuario.setEstado(EstadoUsuario.ACTIVO);
        usuario.setEmailVerificado(true);
        return usuarioRepository.saveAndFlush(usuario);
    }
}
