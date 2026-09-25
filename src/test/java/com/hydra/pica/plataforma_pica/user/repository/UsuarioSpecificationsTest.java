package com.hydra.pica.plataforma_pica.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

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
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        JpaAuditingConfig.class,
        SecurityContextCurrentUserProvider.class,
        TestcontainersConfiguration.class
})
class UsuarioSpecificationsTest {

    private final PersonaRepository personaRepository;
    private final UsuarioRepository usuarioRepository;
    private final RolRepository rolRepository;
    private final UsuarioRolRepository usuarioRolRepository;
    private final EntityManager entityManager;

    private Long participanteId;
    private Long administradorId;

    @Autowired
    UsuarioSpecificationsTest(
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

    @BeforeEach
    void crearUsuariosDePrueba() {
        Rol participante = rolRepository.findByNombre("PARTICIPANTE").orElseThrow();
        Rol administrador = rolRepository.findByNombre("ADMINISTRADOR").orElseThrow();
        participanteId = participante.getId();
        administradorId = administrador.getId();

        crearUsuarioConRol(
                "agomez", "ana.gomez@example.com", EstadoUsuario.ACTIVO,
                "Ana", "Gomez", "111", List.of(participante));
        crearUsuarioConRol(
                "lperez", "luis.perez@example.com", EstadoUsuario.BLOQUEADO,
                "Luis", "Perez", "222", List.of(administrador));
        crearUsuarioConRol(
                "mdiaz", "marta.diaz@example.com", EstadoUsuario.ACTIVO,
                "Marta", "Diaz", "333", List.of(participante, administrador));

        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void conTextoBuscaEnUsernameEmailApellidoYDocumento() {
        assertThat(buscar(UsuarioSpecifications.conTexto("GOMEZ")))
                .extracting(Usuario::getUsername)
                .containsExactly("agomez");
        assertThat(buscar(UsuarioSpecifications.conTexto("lperez")))
                .extracting(Usuario::getUsername)
                .containsExactly("lperez");
        assertThat(buscar(UsuarioSpecifications.conTexto("marta.diaz@example.com")))
                .extracting(Usuario::getUsername)
                .containsExactly("mdiaz");
        assertThat(buscar(UsuarioSpecifications.conTexto("333")))
                .extracting(Usuario::getUsername)
                .containsExactly("mdiaz");
        assertThat(buscar(UsuarioSpecifications.conTexto("no-existe"))).isEmpty();
    }

    @Test
    void conEstadoFiltraPorEstadoExacto() {
        assertThat(buscar(UsuarioSpecifications.conEstado(EstadoUsuario.ACTIVO)))
                .extracting(Usuario::getUsername)
                .containsExactlyInAnyOrder("agomez", "mdiaz");
        assertThat(buscar(UsuarioSpecifications.conEstado(EstadoUsuario.BLOQUEADO)))
                .extracting(Usuario::getUsername)
                .containsExactly("lperez");
    }

    @Test
    void conRolFiltraSinDuplicarUsuariosConVariosRoles() {
        assertThat(buscar(UsuarioSpecifications.conRol(participanteId)))
                .extracting(Usuario::getUsername)
                .containsExactlyInAnyOrder("agomez", "mdiaz");
        assertThat(buscar(UsuarioSpecifications.conRol(administradorId)))
                .extracting(Usuario::getUsername)
                .containsExactlyInAnyOrder("lperez", "mdiaz");
    }

    @Test
    void losFiltrosSeCombinanConAnd() {
        Specification<Usuario> filtro = UsuarioSpecifications.conTexto("diaz")
                .and(UsuarioSpecifications.conEstado(EstadoUsuario.ACTIVO))
                .and(UsuarioSpecifications.conRol(administradorId));

        assertThat(buscar(filtro)).extracting(Usuario::getUsername).containsExactly("mdiaz");
    }

    @Test
    void sinFiltrosDevuelveTodosLosUsuariosDePrueba() {
        Specification<Usuario> sinFiltros = Specification.where(UsuarioSpecifications.conTexto(null))
                .and(UsuarioSpecifications.conEstado(null))
                .and(UsuarioSpecifications.conRol(null));

        assertThat(buscar(sinFiltros)).hasSize(3);
    }

    @Test
    void buscarIncluyendoEliminadosDevuelveUsuariosDadosDeBaja() {
        Usuario eliminado = usuarioRepository.findByUsernameIgnoreCase("lperez").orElseThrow();
        eliminado.setEliminadoEn(Instant.now());
        entityManager.flush();
        entityManager.clear();

        Page<UsuarioAdminRepositoryCustom.UsuarioAdminRow> pagina =
                usuarioRepository.buscarIncluyendoEliminados(null, null, null, PageRequest.of(0, 10));

        assertThat(pagina.getContent())
                .extracting(UsuarioAdminRepositoryCustom.UsuarioAdminRow::username)
                .contains("lperez");
        assertThat(pagina.getContent())
                .filteredOn(row -> row.username().equals("lperez"))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.eliminadoEn()).isNotNull();
                    assertThat(row.rolesJson()).contains("ADMINISTRADOR");
                });
    }

    @Test
    void buscarIncluyendoEliminadosIgnoraLosRolesDadosDeBaja() {
        Rol veedor = new Rol();
        veedor.setNombre("VEEDOR");
        veedor.setNombreAmigable("Veedor");
        veedor.setEstado(EstadoGeneral.ACTIVO);
        rolRepository.saveAndFlush(veedor);
        Usuario agomez = usuarioRepository.findByUsernameIgnoreCase("agomez").orElseThrow();
        UsuarioRol usuarioRol = new UsuarioRol(agomez, veedor);
        usuarioRol.setAsignadoEn(Instant.now());
        usuarioRol.setAsignadoPor(AuditConstants.SISTEMA);
        usuarioRolRepository.saveAndFlush(usuarioRol);
        veedor.setEliminadoEn(Instant.now());
        entityManager.flush();
        entityManager.clear();

        assertThat(usuarioRepository.buscarIncluyendoEliminados("agomez", null, null, PageRequest.of(0, 10)))
                .singleElement()
                .satisfies(row -> assertThat(row.rolesJson())
                        .contains("PARTICIPANTE")
                        .doesNotContain("VEEDOR"));
        assertThat(usuarioRepository.buscarIncluyendoEliminados(
                null, null, veedor.getId(), PageRequest.of(0, 10))).isEmpty();
    }

    private List<Usuario> buscar(Specification<Usuario> specification) {
        Page<Usuario> pagina = usuarioRepository.findAll(specification, PageRequest.of(0, 10));
        return pagina.getContent();
    }

    private void crearUsuarioConRol(
            String username,
            String email,
            EstadoUsuario estado,
            String nombres,
            String apellidos,
            String nroDoc,
            List<Rol> roles) {
        Persona persona = new Persona();
        persona.setNombres(nombres);
        persona.setApellidos(apellidos);
        persona.setTipoDoc("DNI");
        persona.setNroDoc(nroDoc);
        persona.setEstado(EstadoGeneral.ACTIVO);
        personaRepository.saveAndFlush(persona);

        Usuario usuario = new Usuario();
        usuario.setPersona(persona);
        usuario.setUsername(username);
        usuario.setEmail(email);
        usuario.setEstado(estado);
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
