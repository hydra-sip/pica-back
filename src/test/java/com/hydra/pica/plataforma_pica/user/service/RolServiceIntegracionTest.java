package com.hydra.pica.plataforma_pica.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import jakarta.persistence.EntityManager;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.audit.AuditConstants;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Modulo;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.domain.UsuarioRol;
import com.hydra.pica.plataforma_pica.user.dto.FiltroRoles;
import com.hydra.pica.plataforma_pica.user.dto.ModuloPermisos;
import com.hydra.pica.plataforma_pica.user.dto.RolDetalle;
import com.hydra.pica.plataforma_pica.user.dto.RolRequest;
import com.hydra.pica.plataforma_pica.user.dto.RolResumen;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.repository.RolRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRolRepository;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * ABM de roles y sus permisos contra Postgres, con el seed de V2 y V4. Lo que más interesa acá es lo que un mock
 * no muestra: las consultas nativas que saltan el @SQLRestriction (listado, detalle y reactivación
 * de eliminados, nombre único contando eliminados) y que la baja de un rol se note en los permisos.
 * Cada test hace rollback al terminar.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class RolServiceIntegracionTest {

    private static final FiltroRoles SIN_FILTRO = new FiltroRoles(null, null, null);
    private static final PageRequest PRIMERA_PAGINA = PageRequest.of(0, 20, Sort.by("id"));

    @Autowired private RolService rolService;
    @Autowired private PermisoService permisoService;
    @Autowired private RolRepository rolRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private UsuarioRolRepository usuarioRolRepository;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private EntityManager entityManager;

    // --- listado ------------------------------------------------------------

    @Test
    void listaLosRolesDelSeedEnOrden() {
        Page<RolResumen> pagina = rolService.listar(SIN_FILTRO, PRIMERA_PAGINA);

        assertThat(pagina.getContent()).extracting(RolResumen::nombre)
                .containsExactly("SUPER_USUARIO", "ADMINISTRADOR", "ORGANIZADOR", "ARBITRO", "SOPORTE", "PARTICIPANTE");
        assertThat(pagina.getContent()).filteredOn(RolResumen::esSistema)
                .extracting(RolResumen::nombre).containsExactly("SUPER_USUARIO");
        assertThat(pagina.getContent()).noneMatch(RolResumen::eliminado);
    }

    @Test
    void cantidadUsuariosCuentaAsignacionesVigentesDeUsuariosNoEliminados() {
        RolDetalle veedor = rolService.crear(new RolRequest("VEEDOR", "Veedor", null, null));
        usuarioCon("activo", "VEEDOR");
        Usuario bloqueado = usuarioCon("bloqueado", "VEEDOR");
        Usuario eliminado = usuarioCon("eliminado", "VEEDOR");
        Usuario sinElRol = usuarioCon("revocado", "VEEDOR");

        bloqueado.setEstado(EstadoUsuario.BLOQUEADO);
        eliminado.setEliminadoEn(Instant.now());
        UsuarioRol asignacion = usuarioRolRepository.findByUsuarioId(sinElRol.getId()).getFirst();
        asignacion.setEliminadoEn(Instant.now());
        entityManager.flush();
        entityManager.clear();

        assertThat(rolService.listar(new FiltroRoles("VEEDOR", null, null), PRIMERA_PAGINA).getContent())
                .singleElement()
                .extracting(RolResumen::cantidadUsuarios).isEqualTo(2L);
        assertThat(rolService.detalle(veedor.id()).cantidadUsuarios()).isEqualTo(2L);
    }

    @Test
    void filtraPorTextoEnNombreONombreAmigableSinDistinguirMayusculas() {
        assertThat(nombres(new FiltroRoles("arbi", null, null))).containsExactly("ARBITRO");
        // solo el nombre amigable lleva tilde
        assertThat(nombres(new FiltroRoles("Árbi", null, null))).containsExactly("ARBITRO");
        assertThat(nombres(new FiltroRoles("super usu", null, null))).containsExactly("SUPER_USUARIO");
        assertThat(nombres(new FiltroRoles("   ", null, null))).hasSize(6);
        // un % no es comodín
        assertThat(nombres(new FiltroRoles("%", null, null))).isEmpty();
    }

    @Test
    void filtraPorEstadoYSoloMuestraEliminadosSiSePide() {
        rolService.crear(new RolRequest("VEEDOR", "Veedor", null, EstadoGeneral.INACTIVO));
        RolDetalle observador = rolService.crear(new RolRequest("OBSERVADOR", "Observador", null, null));
        rolService.eliminar(observador.id());
        entityManager.flush();

        assertThat(nombres(new FiltroRoles(null, EstadoGeneral.INACTIVO, null))).containsExactly("VEEDOR");
        assertThat(nombres(SIN_FILTRO)).doesNotContain("OBSERVADOR");
        assertThat(rolService.listar(new FiltroRoles("OBSERVADOR", null, true), PRIMERA_PAGINA).getContent())
                .singleElement()
                .satisfies(rol -> assertThat(rol.eliminado()).isTrue());
    }

    @Test
    void paginaYOrdenaPorLosCamposDelContrato() {
        Page<RolResumen> pagina = rolService.listar(SIN_FILTRO, PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "nombre")));

        assertThat(pagina.getContent()).extracting(RolResumen::nombre).containsExactly("SUPER_USUARIO", "SOPORTE");
        assertThat(pagina.getTotalElements()).isEqualTo(6);
        assertThat(pagina.getTotalPages()).isEqualTo(3);

        assertThat(rolService.listar(SIN_FILTRO, PageRequest.of(0, 1, Sort.by("nombreAmigable"))).getContent())
                .extracting(RolResumen::nombre).containsExactly("ADMINISTRADOR");
    }

    @Test
    void ordenarPorUnCampoQueNoEstaEnElContratoDa400() {
        // nombre_amigable es la columna: tampoco pasa, solo se aceptan los nombres del contrato
        for (String campo : new String[] {"descripcion", "nombre_amigable", "cantidadUsuarios"}) {
            assertCodigo(() -> rolService.listar(SIN_FILTRO, PageRequest.of(0, 20, Sort.by(campo))),
                    HttpStatus.BAD_REQUEST, CodigoError.VALIDACION);
        }
    }

    // --- detalle ------------------------------------------------------------

    @Test
    void detalleTraeLosPermisosEnElOrdenDelCatalogo() {
        RolDetalle administrador = rolService.detalle(idDe("ADMINISTRADOR"));

        assertThat(administrador.permisos()).containsExactly(
                "USUARIO_VER", "USUARIO_CREAR", "USUARIO_EDITAR", "USUARIO_ELIMINAR",
                "PERSONA_VER", "PERSONA_CREAR", "PERSONA_EDITAR", "PERSONA_ELIMINAR",
                "ROL_VER", "ROL_ASIGNAR");
        assertThat(administrador.creadoEn()).isNotNull();
        assertThat(administrador.eliminado()).isFalse();
        assertThat(administrador.eliminadoEn()).isNull();
    }

    @Test
    void detalleDeUnRolEliminadoLoMuestraComoEliminado() {
        Long id = idDe("SOPORTE");
        rolService.eliminar(id);
        entityManager.flush();
        entityManager.clear();

        RolDetalle soporte = rolService.detalle(id);
        assertThat(soporte.eliminado()).isTrue();
        assertThat(soporte.eliminadoEn()).isNotNull();
    }

    @Test
    void detalleDeUnRolQueNoExisteDa404() {
        assertCodigo(() -> rolService.detalle(999_999L), HttpStatus.NOT_FOUND, CodigoError.ROL_NO_ENCONTRADO);
    }

    // --- alta y modificación --------------------------------------------------

    @Test
    void crearArmaUnRolActivoSinPermisos() {
        RolDetalle veedor = rolService.crear(new RolRequest("VEEDOR", "  Veedor ", "   ", null));

        assertThat(veedor.id()).isNotNull();
        assertThat(veedor.nombreAmigable()).isEqualTo("Veedor");
        assertThat(veedor.descripcion()).isNull();
        assertThat(veedor.estado()).isEqualTo(EstadoGeneral.ACTIVO);
        assertThat(veedor.esSistema()).isFalse();
        assertThat(veedor.permisos()).isEmpty();
        assertThat(veedor.cantidadUsuarios()).isZero();
        assertThat(veedor.creadoEn()).isNotNull();
    }

    @Test
    void crearConElNombreDeOtroRolDa409AunqueEsteDadoDeBaja() {
        assertCodigo(() -> rolService.crear(new RolRequest("ARBITRO", "Otro árbitro", null, null)),
                HttpStatus.CONFLICT, CodigoError.ROL_DUPLICADO);

        rolService.eliminar(idDe("SOPORTE"));
        entityManager.flush();
        entityManager.clear();

        assertThatThrownBy(() -> rolService.crear(new RolRequest("SOPORTE", "Soporte nuevo", null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("reactivalo")
                .extracting("codigo").isEqualTo(CodigoError.ROL_DUPLICADO);
    }

    @Test
    void modificarCambiaTodosLosCampos() {
        RolDetalle veedor = rolService.crear(new RolRequest("VEEDOR", "Veedor", "Mira", null));

        RolDetalle modificado = rolService.modificar(veedor.id(),
                new RolRequest("OBSERVADOR", "Observador", null, EstadoGeneral.INACTIVO));

        assertThat(modificado.nombre()).isEqualTo("OBSERVADOR");
        assertThat(modificado.nombreAmigable()).isEqualTo("Observador");
        assertThat(modificado.descripcion()).isNull();
        assertThat(modificado.estado()).isEqualTo(EstadoGeneral.INACTIVO);
        assertThat(modificado.modificadoEn()).isAfterOrEqualTo(veedor.modificadoEn());
    }

    @Test
    void modificarSinEstadoMantieneElQueTenia() {
        RolDetalle veedor = rolService.crear(new RolRequest("VEEDOR", "Veedor", null, EstadoGeneral.INACTIVO));

        assertThat(rolService.modificar(veedor.id(), new RolRequest("VEEDOR", "Veedor 2", null, null)).estado())
                .isEqualTo(EstadoGeneral.INACTIVO);
    }

    @Test
    void renombrarAlNombreDeOtroRolDa409() {
        RolDetalle veedor = rolService.crear(new RolRequest("VEEDOR", "Veedor", null, null));

        assertCodigo(() -> rolService.modificar(veedor.id(), new RolRequest("ARBITRO", "Veedor", null, null)),
                HttpStatus.CONFLICT, CodigoError.ROL_DUPLICADO);
    }

    @Test
    void unRolDadoDeBajaNoSeModificaNiSeVuelveABajar() {
        Long id = idDe("SOPORTE");
        rolService.eliminar(id);
        entityManager.flush();
        entityManager.clear();

        assertCodigo(() -> rolService.modificar(id, new RolRequest("SOPORTE", "Soporte", null, null)),
                HttpStatus.NOT_FOUND, CodigoError.ROL_NO_ENCONTRADO);
        assertCodigo(() -> rolService.eliminar(id), HttpStatus.NOT_FOUND, CodigoError.ROL_NO_ENCONTRADO);
    }

    // --- protecciones -------------------------------------------------------

    @Test
    void superUsuarioNoSeModificaNiSeDaDeBaja() {
        Long id = idDe("SUPER_USUARIO");

        assertCodigo(() -> rolService.modificar(id, new RolRequest("SUPER_USUARIO", "Súper", null, null)),
                HttpStatus.FORBIDDEN, CodigoError.ROL_PROTEGIDO);
        assertCodigo(() -> rolService.eliminar(id), HttpStatus.FORBIDDEN, CodigoError.ROL_PROTEGIDO);
    }

    @Test
    void participanteNoSeRenombraNiSeInactivaNiSeDaDeBaja() {
        Long id = idDe("PARTICIPANTE");

        assertCodigo(() -> rolService.modificar(id, new RolRequest("JUGADOR", "Participante", null, null)),
                HttpStatus.FORBIDDEN, CodigoError.ROL_PROTEGIDO);
        assertCodigo(() -> rolService.modificar(id,
                        new RolRequest("PARTICIPANTE", "Participante", null, EstadoGeneral.INACTIVO)),
                HttpStatus.FORBIDDEN, CodigoError.ROL_PROTEGIDO);
        assertCodigo(() -> rolService.eliminar(id), HttpStatus.FORBIDDEN, CodigoError.ROL_PROTEGIDO);
    }

    @Test
    void aParticipanteSeLePuedenCambiarLosTextos() {
        RolDetalle participante = rolService.modificar(idDe("PARTICIPANTE"),
                new RolRequest("PARTICIPANTE", "Jugador", "Se inscribe en torneos", null));

        assertThat(participante.nombreAmigable()).isEqualTo("Jugador");
        assertThat(participante.descripcion()).isEqualTo("Se inscribe en torneos");
        assertThat(participante.estado()).isEqualTo(EstadoGeneral.ACTIVO);
    }

    // --- baja y reactivación ------------------------------------------------

    @Test
    void laBajaLeSacaLosPermisosASusUsuariosYReactivarSeLosDevuelve() {
        Usuario usuario = usuarioCon("admin2", "ADMINISTRADOR");
        Long id = idDe("ADMINISTRADOR");
        assertThat(permisoService.permisosDe(usuario.getId())).hasSize(10);

        rolService.eliminar(id);
        entityManager.flush();
        entityManager.clear();
        assertThat(permisoService.permisosDe(usuario.getId())).isEmpty();
        assertThat(nombres(SIN_FILTRO)).doesNotContain("ADMINISTRADOR");

        RolDetalle reactivado = rolService.reactivar(id);
        entityManager.clear();
        assertThat(reactivado.eliminado()).isFalse();
        assertThat(reactivado.cantidadUsuarios()).isEqualTo(1L);
        assertThat(permisoService.permisosDe(usuario.getId())).hasSize(10);
        assertThat(nombres(SIN_FILTRO)).contains("ADMINISTRADOR");
    }

    @Test
    void losRolesDeUnUsuarioNoIncluyenLosDadosDeBajaHastaQueSeReactivan() {
        Usuario usuario = usuarioCon("arbitro1", "ARBITRO");
        Long id = idDe("ARBITRO");
        rolService.eliminar(id);
        entityManager.flush();
        entityManager.clear();

        // antes tiraba EntityNotFoundException al leer el rol dado de baja
        assertThat(usuarioRepository.findById(usuario.getId()).orElseThrow().getRoles()).isEmpty();

        rolService.reactivar(id);
        entityManager.clear();
        assertThat(usuarioRepository.findById(usuario.getId()).orElseThrow().getRoles())
                .extracting(asignacion -> asignacion.getRol().getNombre())
                .containsExactly("ARBITRO");
    }

    @Test
    void reactivarUnRolQueNoEstabaDadoDeBajaLoDevuelveIgual() {
        Long id = idDe("ARBITRO");

        RolDetalle arbitro = rolService.reactivar(id);

        assertThat(arbitro.eliminado()).isFalse();
        assertThat(arbitro.nombre()).isEqualTo("ARBITRO");
    }

    @Test
    void reactivarUnRolQueNoExisteDa404() {
        assertCodigo(() -> rolService.reactivar(999_999L), HttpStatus.NOT_FOUND, CodigoError.ROL_NO_ENCONTRADO);
    }

    // --- permisos (PICA-126) -------------------------------------------------

    @Test
    void cambiarLosPermisosDeUnRolCambiaLoQuePuedenHacerSusUsuarios() {
        Usuario usuario = usuarioCon("soporte1", "SOPORTE");
        Long id = idDe("SOPORTE");
        assertThat(permisoService.permisosDe(usuario.getId())).isEmpty();

        RolDetalle soporte = rolService.reemplazarPermisos(id, List.of("PERSONA_VER", "USUARIO_VER"));
        entityManager.clear();

        // en el orden del catálogo, no en el que llegaron
        assertThat(soporte.permisos()).containsExactly("USUARIO_VER", "PERSONA_VER");
        assertThat(permisoService.permisosDe(usuario.getId())).containsExactlyInAnyOrder("USUARIO_VER", "PERSONA_VER");

        // reemplaza, no suma
        rolService.reemplazarPermisos(id, List.of("ROL_VER"));
        entityManager.clear();
        assertThat(permisoService.permisosDe(usuario.getId())).containsExactly("ROL_VER");
    }

    @Test
    void unaListaVaciaDejaAlRolSinPermisosYLosRepetidosCuentanUnaVez() {
        Long id = idDe("ADMINISTRADOR");

        assertThat(rolService.reemplazarPermisos(id, List.of()).permisos()).isEmpty();
        assertThat(rolService.reemplazarPermisos(id, List.of("USUARIO_VER", "USUARIO_VER")).permisos())
                .containsExactly("USUARIO_VER");
    }

    @Test
    void unPermisoQueNoExisteDa404ConLosQueFaltanYNoCambiaNada() {
        Long id = idDe("SOPORTE");

        assertThatThrownBy(() -> rolService.reemplazarPermisos(id,
                        List.of("USUARIO_VER", "PROYECTO_VER", "CONVOCATORIA_VER")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).getCodigo()).isEqualTo(CodigoError.PERMISO_NO_ENCONTRADO);
                    // aparte del detail, para que la pantalla marque esos checkboxes sin parsear texto
                    assertThat(((ApiException) e).getPropiedades())
                            .containsEntry("invalidos", List.of("PROYECTO_VER", "CONVOCATORIA_VER"));
                });

        entityManager.clear();
        assertThat(rolService.detalle(id).permisos()).isEmpty();
    }

    @Test
    void aSuperUsuarioNoSeLeCambianLosPermisos() {
        assertCodigo(() -> rolService.reemplazarPermisos(idDe("SUPER_USUARIO"), List.of("USUARIO_VER")),
                HttpStatus.FORBIDDEN, CodigoError.ROL_PROTEGIDO);
    }

    @Test
    void aUnRolInactivoNoSeLeCambianLosPermisos() {
        RolDetalle veedor = rolService.crear(new RolRequest("VEEDOR", "Veedor", null, EstadoGeneral.INACTIVO));

        assertCodigo(() -> rolService.reemplazarPermisos(veedor.id(), List.of("USUARIO_VER")),
                HttpStatus.CONFLICT, CodigoError.ROL_INACTIVO);
    }

    @Test
    void aUnRolDadoDeBajaNoSeLeCambianLosPermisos() {
        Long id = idDe("SOPORTE");
        rolService.eliminar(id);
        entityManager.flush();
        entityManager.clear();

        assertCodigo(() -> rolService.reemplazarPermisos(id, List.of("USUARIO_VER")),
                HttpStatus.NOT_FOUND, CodigoError.ROL_NO_ENCONTRADO);
    }

    @Test
    void aParticipanteSeLePuedenCambiarLosPermisos() {
        assertThat(rolService.reemplazarPermisos(idDe("PARTICIPANTE"), List.of("PERSONA_VER")).permisos())
                .containsExactly("PERSONA_VER");
    }

    @Test
    void cambiarLosPermisosQuedaEnLaAuditoriaDelRol() {
        Long id = idDe("SOPORTE");
        Instant antes = rolService.detalle(id).modificadoEn();
        entityManager.clear();

        rolService.reemplazarPermisos(id, List.of("USUARIO_VER"));
        entityManager.clear();

        // se lee de la base: la colección sola no marca al rol como modificado
        assertThat(rolService.detalle(id).modificadoEn()).isAfter(antes);
    }

    @Test
    void elCatalogoAgrupaPorModuloEnElOrdenDelSeed() {
        List<ModuloPermisos> catalogo = permisoService.catalogo();

        assertThat(catalogo).extracting(ModuloPermisos::modulo)
                .containsExactly(Modulo.USUARIOS, Modulo.PERSONAS, Modulo.ROLES);
        assertThat(catalogo.getLast().permisos()).extracting(ModuloPermisos.Item::codigo)
                .containsExactly("ROL_VER", "ROL_CREAR", "ROL_EDITAR", "ROL_ELIMINAR", "ROL_ASIGNAR");
        assertThat(catalogo).flatExtracting(ModuloPermisos::permisos)
                .hasSize(13)
                .allSatisfy(item -> assertThat(item.descripcion()).isNotBlank());
    }

    // --- ayudas -------------------------------------------------------------

    private List<String> nombres(FiltroRoles filtro) {
        return rolService.listar(filtro, PRIMERA_PAGINA).getContent().stream().map(RolResumen::nombre).toList();
    }

    private Long idDe(String nombre) {
        return rolRepository.findByNombre(nombre).orElseThrow().getId();
    }

    private static void assertCodigo(ThrowingCallable llamada,
                                     HttpStatus status, CodigoError codigo) {
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
        return usuario;
    }
}
