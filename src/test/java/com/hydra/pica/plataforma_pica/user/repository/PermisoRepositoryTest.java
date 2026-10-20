package com.hydra.pica.plataforma_pica.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import jakarta.persistence.EntityManager;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.audit.AuditConstants;
import com.hydra.pica.plataforma_pica.common.config.JpaAuditingConfig;
import com.hydra.pica.plataforma_pica.common.security.SecurityContextCurrentUserProvider;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Modulo;
import com.hydra.pica.plataforma_pica.user.domain.Permiso;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Rol;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.domain.UsuarioRol;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

/**
 * Seed de V4 y cálculo de permisos por usuario contra Postgres real.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        JpaAuditingConfig.class,
        SecurityContextCurrentUserProvider.class,
        TestcontainersConfiguration.class
})
class PermisoRepositoryTest {

    @Autowired private PermisoRepository permisoRepository;
    @Autowired private RolRepository rolRepository;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private UsuarioRolRepository usuarioRolRepository;
    @Autowired private EntityManager entityManager;

    @Test
    void elSeedTieneLosTrecePermisosDelContratoAgrupadosPorModulo() {
        assertThat(permisoRepository.findAllByOrderByIdAsc())
                .extracting(Permiso::getCodigo)
                .containsExactly(
                        "USUARIO_VER", "USUARIO_CREAR", "USUARIO_EDITAR", "USUARIO_ELIMINAR",
                        "PERSONA_VER", "PERSONA_CREAR", "PERSONA_EDITAR", "PERSONA_ELIMINAR",
                        "ROL_VER", "ROL_CREAR", "ROL_EDITAR", "ROL_ELIMINAR", "ROL_ASIGNAR");
        assertThat(permisoRepository.findByCodigo("ROL_ASIGNAR")).get()
                .extracting(Permiso::getModulo).isEqualTo(Modulo.ROLES);
    }

    @Test
    void superUsuarioTieneTodoYAdministradorTodoMenosElCatalogoDeRoles() {
        assertThat(rolRepository.findByNombre("SUPER_USUARIO").orElseThrow().getPermisos()).hasSize(13);

        assertThat(rolRepository.findByNombre("ADMINISTRADOR").orElseThrow().getPermisos())
                .extracting(Permiso::getCodigo)
                .hasSize(10)
                .contains("ROL_VER", "ROL_ASIGNAR")
                .doesNotContain("ROL_CREAR", "ROL_EDITAR", "ROL_ELIMINAR");

        assertThat(rolRepository.findByNombre("PARTICIPANTE").orElseThrow().getPermisos()).isEmpty();
    }

    @Test
    void losPermisosDelUsuarioSonLaUnionDeSusRolesActivos() {
        Usuario usuario = usuarioCon("ADMINISTRADOR", "ARBITRO");

        assertThat(permisoRepository.findCodigosByUsuarioId(usuario.getId()))
                .hasSize(10)
                .contains("USUARIO_VER", "PERSONA_ELIMINAR", "ROL_ASIGNAR")
                .doesNotContain("ROL_EDITAR");
        assertThat(permisoRepository.findCodigosByUsuarioId(usuarioCon("PARTICIPANTE").getId())).isEmpty();
    }

    @Test
    void unRolInactivoNoAportaPermisos() {
        Usuario usuario = usuarioCon("ADMINISTRADOR");
        Rol administrador = rolRepository.findByNombre("ADMINISTRADOR").orElseThrow();
        administrador.setEstado(EstadoGeneral.INACTIVO);
        rolRepository.saveAndFlush(administrador);
        entityManager.clear();

        assertThat(permisoRepository.findCodigosByUsuarioId(usuario.getId())).isEmpty();
    }

    @Test
    void unaAsignacionEliminadaNoAportaPermisos() {
        Usuario usuario = usuarioCon("ADMINISTRADOR");
        UsuarioRol asignacion = usuarioRolRepository.findByUsuarioId(usuario.getId()).getFirst();
        asignacion.setEliminadoEn(Instant.now());
        usuarioRolRepository.saveAndFlush(asignacion);
        entityManager.clear();

        assertThat(permisoRepository.findCodigosByUsuarioId(usuario.getId())).isEmpty();
    }

    @Test
    void unRolEliminadoNoAportaPermisos() {
        Usuario usuario = usuarioCon("SOPORTE");
        Rol soporte = rolRepository.findByNombre("SOPORTE").orElseThrow();
        soporte.getPermisos().add(permisoRepository.findByCodigo("USUARIO_VER").orElseThrow());
        rolRepository.saveAndFlush(soporte);
        entityManager.clear();
        assertThat(permisoRepository.findCodigosByUsuarioId(usuario.getId())).containsExactly("USUARIO_VER");

        soporte = rolRepository.findByNombre("SOPORTE").orElseThrow();
        soporte.setEliminadoEn(Instant.now());
        rolRepository.saveAndFlush(soporte);
        entityManager.clear();

        assertThat(permisoRepository.findCodigosByUsuarioId(usuario.getId())).isEmpty();
    }

    @Test
    void unUsuarioBloqueadoNoTienePermisos() {
        Usuario usuario = usuarioCon("ADMINISTRADOR");
        assertThat(permisoRepository.findCodigosByUsuarioId(usuario.getId())).isNotEmpty();

        Usuario bloqueado = usuarioRepository.findById(usuario.getId()).orElseThrow();
        bloqueado.setEstado(EstadoUsuario.BLOQUEADO);
        usuarioRepository.saveAndFlush(bloqueado);
        entityManager.clear();

        assertThat(permisoRepository.findCodigosByUsuarioId(usuario.getId())).isEmpty();
    }

    @Test
    void unUsuarioEliminadoNoTienePermisos() {
        Usuario usuario = usuarioCon("ADMINISTRADOR");

        Usuario eliminado = usuarioRepository.findById(usuario.getId()).orElseThrow();
        eliminado.setEliminadoEn(Instant.now());
        usuarioRepository.saveAndFlush(eliminado);
        entityManager.clear();

        assertThat(permisoRepository.findCodigosByUsuarioId(usuario.getId())).isEmpty();
    }

    private Usuario usuarioCon(String... roles) {
        Persona persona = new Persona();
        persona.setNombres("Test");
        persona.setApellidos(String.join("-", roles));
        persona.setEstado(EstadoGeneral.ACTIVO);
        persona = personaRepository.saveAndFlush(persona);

        Usuario usuario = new Usuario();
        usuario.setUsername("u_" + String.join("_", roles).toLowerCase());
        usuario.setEmail(usuario.getUsername() + "@example.com");
        usuario.setEstado(EstadoUsuario.ACTIVO);
        usuario.setEmailVerificado(true);
        usuario.setPersona(persona);
        usuario = usuarioRepository.saveAndFlush(usuario);

        for (String nombre : roles) {
            UsuarioRol asignacion = new UsuarioRol(usuario, rolRepository.findByNombre(nombre).orElseThrow());
            asignacion.setAsignadoEn(Instant.now());
            asignacion.setAsignadoPor(AuditConstants.SISTEMA);
            usuarioRolRepository.saveAndFlush(asignacion);
        }
        entityManager.clear();
        return usuario;
    }
}
