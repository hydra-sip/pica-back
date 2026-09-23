package com.hydra.pica.plataforma_pica.user.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.audit.AuditConstants;
import com.hydra.pica.plataforma_pica.common.config.AdminConfig;
import com.hydra.pica.plataforma_pica.common.config.JpaAuditingConfig;
import com.hydra.pica.plataforma_pica.common.security.SecurityContextCurrentUserProvider;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Rol;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.domain.UsuarioRol;
import com.hydra.pica.plataforma_pica.user.dto.UsuarioResumen;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.repository.RolRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRolRepository;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

@DataJpaTest(properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "app.admin.username=admin"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        JpaAuditingConfig.class,
        SecurityContextCurrentUserProvider.class,
        TestcontainersConfiguration.class,
        AdminConfig.class,
        JacksonAutoConfiguration.class,
        UsuarioAdminService.class
})
class UsuarioAdminServiceListadoTest {

    private static final int USUARIOS = 8;

    private final UsuarioAdminService servicio;
    private final PersonaRepository personaRepository;
    private final UsuarioRepository usuarioRepository;
    private final RolRepository rolRepository;
    private final UsuarioRolRepository usuarioRolRepository;
    private final EntityManager entityManager;

    @Autowired
    UsuarioAdminServiceListadoTest(
            UsuarioAdminService servicio,
            PersonaRepository personaRepository,
            UsuarioRepository usuarioRepository,
            RolRepository rolRepository,
            UsuarioRolRepository usuarioRolRepository,
            EntityManager entityManager) {
        this.servicio = servicio;
        this.personaRepository = personaRepository;
        this.usuarioRepository = usuarioRepository;
        this.rolRepository = rolRepository;
        this.usuarioRolRepository = usuarioRolRepository;
        this.entityManager = entityManager;
    }

    @Test
    @DisplayName("Listar una página no dispara una consulta por usuario (sin N+1)")
    void listarUsaUnaCantidadFijaDeConsultas() {
        Rol participante = rolRepository.findByNombre("PARTICIPANTE").orElseThrow();
        Rol administrador = rolRepository.findByNombre("ADMINISTRADOR").orElseThrow();
        for (int i = 0; i < USUARIOS; i++) {
            crearUsuario("usuario" + i, i, List.of(participante, administrador));
        }
        entityManager.flush();
        entityManager.clear();

        Statistics estadisticas = entityManager.getEntityManagerFactory()
                .unwrap(SessionFactory.class).getStatistics();
        estadisticas.clear();

        Page<UsuarioResumen> pagina = servicio.listar(null, null, null, false, PageRequest.of(0, 20));

        assertThat(pagina.getContent()).hasSize(USUARIOS);
        assertThat(pagina.getContent()).allSatisfy(u -> {
            assertThat(u.persona().nombreCompleto()).isNotBlank();
            assertThat(u.roles()).hasSize(2);
        });
        // página con persona + roles con su rol (+ el conteo, si hiciera falta): no crece con USUARIOS
        assertThat(estadisticas.getPrepareStatementCount()).isLessThanOrEqualTo(3);
    }

    private void crearUsuario(String username, int n, List<Rol> roles) {
        Persona persona = new Persona();
        persona.setNombres("Nombre" + n);
        persona.setApellidos("Apellido" + n);
        persona.setTipoDoc("DNI");
        persona.setNroDoc("9000" + n);
        persona.setEstado(EstadoGeneral.ACTIVO);
        personaRepository.saveAndFlush(persona);

        Usuario usuario = new Usuario();
        usuario.setPersona(persona);
        usuario.setUsername(username);
        usuario.setEmail(username + "@example.com");
        usuario.setEstado(EstadoUsuario.ACTIVO);
        usuario.setEmailVerificado(true);
        usuarioRepository.saveAndFlush(usuario);

        for (Rol rol : roles) {
            UsuarioRol usuarioRol = new UsuarioRol(usuario, rol);
            usuarioRol.setAsignadoEn(Instant.now());
            usuarioRol.setAsignadoPor(AuditConstants.SISTEMA);
            usuarioRolRepository.saveAndFlush(usuarioRol);
        }
    }
}
