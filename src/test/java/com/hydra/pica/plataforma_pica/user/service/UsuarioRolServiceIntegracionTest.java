package com.hydra.pica.plataforma_pica.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.audit.AuditConstants;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Permiso;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Rol;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.domain.UsuarioRol;
import com.hydra.pica.plataforma_pica.user.dto.RolDetalle;
import com.hydra.pica.plataforma_pica.user.dto.RolMinimo;
import com.hydra.pica.plataforma_pica.user.dto.RolRequest;
import com.hydra.pica.plataforma_pica.user.dto.UsuarioDetalle;
import com.hydra.pica.plataforma_pica.user.event.RolesDeUsuarioCambiados;
import com.hydra.pica.plataforma_pica.user.repository.PermisoRepository;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.repository.RolRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRolRepository;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roles de un usuario contra Postgres, con el seed de V2 y V4. Quien llama se simula con el mock de
 * {@link CurrentUserProvider}: por defecto tiene todos los permisos y no es el usuario que se edita.
 * Los {@code clear()} separan "requests": en la app cada una es su propia transacción.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@Transactional
@RecordApplicationEvents
class UsuarioRolServiceIntegracionTest {

    @Autowired private UsuarioRolService usuarioRolService;
    @Autowired private UsuarioAdminService usuarioAdminService;
    @Autowired private RolService rolService;
    @Autowired private RolRepository rolRepository;
    @Autowired private PermisoRepository permisoRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private UsuarioRolRepository usuarioRolRepository;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private ApplicationEvents eventos;

    @MockitoBean private CurrentUserProvider currentUserProvider;

    private Set<String> todosLosPermisos;

    @BeforeEach
    void quienLlamaTieneTodosLosPermisos() {
        todosLosPermisos = permisoRepository.findAllByOrderByIdAsc().stream()
                .map(Permiso::getCodigo)
                .collect(Collectors.toSet());
        when(currentUserProvider.getPermisos()).thenReturn(todosLosPermisos);
    }

    // --- reemplazo ------------------------------------------------------------

    @Test
    void reemplazaLosRolesYDaDeBajaLosQueSaca() {
        Usuario usuario = usuarioCon("juan", "PARTICIPANTE", "ARBITRO");

        reemplazar(usuario, "ARBITRO", "SOPORTE");

        assertThat(nombresDeRoles(usuario)).containsExactlyInAnyOrder("ARBITRO", "SOPORTE");
        assertThat(filaDe(usuario, "PARTICIPANTE"))
                .satisfies(fila -> {
                    assertThat(fila[0]).as("eliminado_en").isNotNull();
                    assertThat(fila[1]).as("eliminado_por").isEqualTo(AuditConstants.SISTEMA);
                });
        assertThat(eventos.stream(RolesDeUsuarioCambiados.class))
                .containsExactly(new RolesDeUsuarioCambiados(usuario.getId()));
    }

    @Test
    void listaVaciaLoDejaSinRolesYLosRepetidosCuentanUnaVez() {
        Usuario usuario = usuarioCon("juan", "PARTICIPANTE");
        Long soporte = idDe("SOPORTE");

        usuarioRolService.reemplazarRoles(usuario.getId(), List.of(soporte, soporte));
        assertThat(nombresDeRoles(usuario)).containsExactly("SOPORTE");

        usuarioRolService.reemplazarRoles(usuario.getId(), List.of());
        assertThat(nombresDeRoles(usuario)).isEmpty();
    }

    @Test
    void volverADarUnRolQuitadoReviveLaMismaFila() {
        Usuario usuario = usuarioCon("juan", "PARTICIPANTE", "SOPORTE");
        reemplazar(usuario, "PARTICIPANTE");
        entityManager.clear();

        reemplazar(usuario, "PARTICIPANTE", "SOPORTE");

        assertThat(nombresDeRoles(usuario)).containsExactlyInAnyOrder("PARTICIPANTE", "SOPORTE");
        assertThat(filaDe(usuario, "SOPORTE")[0]).as("eliminado_en").isNull();
    }

    @Test
    void quitarYVolverADarEnLaMismaSesionNoDejaLaEntidadVieja() {
        Usuario usuario = usuarioCon("juan", "PARTICIPANTE", "SOPORTE");
        reemplazar(usuario, "PARTICIPANTE");

        Usuario resultado = reemplazar(usuario, "PARTICIPANTE", "SOPORTE");

        assertThat(resultado.getRoles()).extracting(asignacion -> asignacion.getRol().getNombre())
                .containsExactlyInAnyOrder("PARTICIPANTE", "SOPORTE");
        assertThat(resultado.getRoles()).allSatisfy(asignacion -> assertThat(asignacion.getEliminadoEn()).isNull());
    }

    @Test
    void marcaAlUsuarioComoModificadoSoloSiCambiaAlgo() {
        Usuario usuario = usuarioCon("juan", "PARTICIPANTE");
        Instant antes = usuarioRepository.findById(usuario.getId()).orElseThrow().getModificadoEn();

        reemplazar(usuario, "PARTICIPANTE");
        assertThat(usuarioRepository.findById(usuario.getId()).orElseThrow().getModificadoEn()).isEqualTo(antes);
        assertThat(eventos.stream(RolesDeUsuarioCambiados.class)).isEmpty();

        reemplazar(usuario, "PARTICIPANTE", "SOPORTE");
        assertThat(usuarioRepository.findById(usuario.getId()).orElseThrow().getModificadoEn()).isAfter(antes);
    }

    @Test
    void lasAsignacionesARolesDadosDeBajaNoSeTocanYVuelvenAlReactivar() {
        RolDetalle veedor = rolService.crear(new RolRequest("VEEDOR", "Veedor", null, null));
        Usuario usuario = usuarioCon("juan", "PARTICIPANTE", "VEEDOR");
        rolService.eliminar(veedor.id());
        entityManager.flush();
        entityManager.clear();

        reemplazar(usuario, "SOPORTE");
        rolService.reactivar(veedor.id());
        entityManager.flush();
        entityManager.clear();

        assertThat(nombresDeRoles(usuario)).containsExactlyInAnyOrder("SOPORTE", "VEEDOR");
    }

    @Test
    void elEndpointDevuelveElDetalleConLosRolesNuevos() {
        Usuario admin = usuarioCon("admin", "SUPER_USUARIO");

        UsuarioDetalle detalle = usuarioAdminService.reemplazarRoles(
                admin.getId(), List.of(idDe("SUPER_USUARIO"), idDe("SOPORTE")));

        assertThat(detalle.id()).isEqualTo(admin.getId());
        assertThat(detalle.protegido()).isTrue();
        assertThat(detalle.persona().apellidos()).isEqualTo("admin");
        assertThat(detalle.roles()).extracting(RolMinimo::nombre).containsExactly("SOPORTE", "SUPER_USUARIO");
        assertThat(detalle.modificadoEn()).isNotNull();
    }

    // --- errores --------------------------------------------------------------

    @Test
    void usuarioInexistenteODadoDeBajaDa404() {
        Usuario eliminado = usuarioCon("juan", "PARTICIPANTE");
        usuarioRepository.findById(eliminado.getId()).orElseThrow().setEliminadoEn(Instant.now());
        entityManager.flush();
        entityManager.clear();

        assertError(() -> usuarioRolService.reemplazarRoles(999_999L, List.of()),
                HttpStatus.NOT_FOUND, CodigoError.USUARIO_NO_ENCONTRADO);
        assertError(() -> usuarioRolService.reemplazarRoles(eliminado.getId(), List.of()),
                HttpStatus.NOT_FOUND, CodigoError.USUARIO_NO_ENCONTRADO);
    }

    @Test
    void unRolInexistenteDa404YNoCambiaNada() {
        Usuario usuario = usuarioCon("juan", "PARTICIPANTE");

        assertError(() -> usuarioRolService.reemplazarRoles(usuario.getId(), List.of(idDe("SOPORTE"), 999_999L)),
                HttpStatus.NOT_FOUND, CodigoError.ROL_NO_ENCONTRADO);
        assertThat(nombresDeRoles(usuario)).containsExactly("PARTICIPANTE");
    }

    @Test
    void usuarioQueNoEstaActivoDa409() {
        Usuario bloqueado = usuarioCon("juan", "PARTICIPANTE");
        usuarioRepository.findById(bloqueado.getId()).orElseThrow().setEstado(EstadoUsuario.BLOQUEADO);
        Usuario pendiente = usuarioCon("ana", "PARTICIPANTE");
        usuarioRepository.findById(pendiente.getId()).orElseThrow().setEstado(EstadoUsuario.PENDIENTE_VERIFICACION);
        entityManager.flush();
        entityManager.clear();

        assertError(() -> reemplazar(bloqueado, "SOPORTE"), HttpStatus.CONFLICT, CodigoError.USUARIO_NO_ACTIVO);
        assertError(() -> reemplazar(pendiente, "SOPORTE"), HttpStatus.CONFLICT, CodigoError.USUARIO_NO_ACTIVO);
    }

    @Test
    void agregarUnRolInactivoDa409PeroSiYaLoTeniaSeConserva() {
        Usuario usuario = usuarioCon("juan", "PARTICIPANTE", "SOPORTE");
        rolRepository.findByNombre("SOPORTE").orElseThrow().setEstado(EstadoGeneral.INACTIVO);
        rolRepository.findByNombre("ARBITRO").orElseThrow().setEstado(EstadoGeneral.INACTIVO);
        entityManager.flush();
        entityManager.clear();

        assertError(() -> reemplazar(usuario, "PARTICIPANTE", "SOPORTE", "ARBITRO"),
                HttpStatus.CONFLICT, CodigoError.ROL_INACTIVO);

        reemplazar(usuario, "SOPORTE", "ORGANIZADOR");
        assertThat(nombresDeRoles(usuario)).containsExactlyInAnyOrder("SOPORTE", "ORGANIZADOR");
    }

    // --- protecciones ---------------------------------------------------------

    @Test
    void nadiePuedeDarNiQuitarUnRolConPermisosQueNoTiene() {
        when(currentUserProvider.getPermisos()).thenReturn(permisosDe("ADMINISTRADOR"));
        Usuario juan = usuarioCon("juan", "PARTICIPANTE");
        Usuario superUsuario = usuarioCon("ana", "SUPER_USUARIO");

        assertThatThrownBy(() -> reemplazar(juan, "PARTICIPANTE", "SUPER_USUARIO"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException error = (ApiException) e;
                    assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(error.getCodigo()).isEqualTo(CodigoError.SIN_PERMISO);
                    assertThat(error.getPropiedades()).containsEntry("roles", List.of("SUPER_USUARIO"));
                });
        assertError(() -> reemplazar(superUsuario), HttpStatus.FORBIDDEN, CodigoError.SIN_PERMISO);

        // los permisos del Administrador son un subconjunto de los suyos: ese sí lo puede dar
        reemplazar(juan, "PARTICIPANTE", "ADMINISTRADOR");
        assertThat(nombresDeRoles(juan)).containsExactlyInAnyOrder("PARTICIPANTE", "ADMINISTRADOR");
    }

    @Test
    void alAdminDelSistemaNoSeLeQuitaSuperUsuarioPeroSeLeAgreganOtros() {
        Usuario admin = usuarioCon("admin", "SUPER_USUARIO");

        assertError(() -> reemplazar(admin), HttpStatus.FORBIDDEN, CodigoError.USUARIO_PROTEGIDO);
        assertError(() -> reemplazar(admin, "ADMINISTRADOR"), HttpStatus.FORBIDDEN, CodigoError.USUARIO_PROTEGIDO);

        reemplazar(admin, "SUPER_USUARIO", "SOPORTE");
        assertThat(nombresDeRoles(admin)).containsExactlyInAnyOrder("SUPER_USUARIO", "SOPORTE");
    }

    @Test
    void unoNoSePuedeQuitarElUltimoRolConRolAsignar() {
        Usuario yo = usuarioCon("juan", "ADMINISTRADOR", "PARTICIPANTE");
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(yo.getId()));

        assertError(() -> reemplazar(yo, "PARTICIPANTE"), HttpStatus.FORBIDDEN, CodigoError.ULTIMO_ASIGNADOR);

        // cambiarlo por otro rol que también asigna sí se puede
        reemplazar(yo, "SUPER_USUARIO");
        assertThat(nombresDeRoles(yo)).containsExactly("SUPER_USUARIO");
    }

    @Test
    void aOtroUsuarioSiSeLeQuitaElUltimoRolConRolAsignar() {
        Usuario yo = usuarioCon("juan", "ADMINISTRADOR");
        Usuario otro = usuarioCon("ana", "ADMINISTRADOR");
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(yo.getId()));

        reemplazar(otro, "PARTICIPANTE");

        assertThat(nombresDeRoles(otro)).containsExactly("PARTICIPANTE");
    }

    // --- helpers --------------------------------------------------------------

    private Usuario reemplazar(Usuario usuario, String... roles) {
        return usuarioRolService.reemplazarRoles(usuario.getId(), List.of(roles).stream().map(this::idDe).toList());
    }

    private List<String> nombresDeRoles(Usuario usuario) {
        entityManager.flush();
        entityManager.clear();
        return usuarioRepository.findById(usuario.getId()).orElseThrow().getRoles().stream()
                .map(asignacion -> asignacion.getRol().getNombre())
                .toList();
    }

    /** eliminado_en y eliminado_por de la fila, esté o no dada de baja (la consulta nativa no filtra). */
    private Object[] filaDe(Usuario usuario, String rol) {
        entityManager.flush();
        return (Object[]) entityManager.createNativeQuery(
                        "SELECT eliminado_en, eliminado_por FROM usuario_rol WHERE usuario_id = ?1 AND rol_id = ?2")
                .setParameter(1, usuario.getId())
                .setParameter(2, idDe(rol))
                .getSingleResult();
    }

    private Long idDe(String rol) {
        return rolRepository.findByNombre(rol).map(Rol::getId).orElseThrow();
    }

    private Set<String> permisosDe(String rol) {
        return rolRepository.findByNombre(rol).orElseThrow().getPermisos().stream()
                .map(Permiso::getCodigo)
                .collect(Collectors.toSet());
    }

    private static void assertError(ThrowingCallable llamada, HttpStatus status, CodigoError codigo) {
        assertThatThrownBy(llamada)
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).getStatus()).isEqualTo(status);
                    assertThat(((ApiException) e).getCodigo()).isEqualTo(codigo);
                });
    }

    private Usuario usuarioCon(String username, String... roles) {
        Persona persona = new Persona();
        persona.setNombres("Test");
        persona.setApellidos(username);
        persona.setEstado(EstadoGeneral.ACTIVO);
        persona = personaRepository.saveAndFlush(persona);

        Usuario usuario = new Usuario();
        usuario.setUsername(username);
        usuario.setEmail(username + "@example.com");
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
