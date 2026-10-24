package com.hydra.pica.plataforma_pica.rifa.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.groups.Tuple.tuple;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ErrorCampo;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;
import com.hydra.pica.plataforma_pica.rifa.dto.PremioRifaDetalle;
import com.hydra.pica.plataforma_pica.rifa.dto.PremioRifaRequest;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaDetalle;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaRequest;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaResumen;
import com.hydra.pica.plataforma_pica.rifa.repository.RifaRepository;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import org.assertj.core.api.MapAssert;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Alta, modificación, premios y listado de rifas sin torneo (PICA-226) contra Postgres, con V10: una rifa en
 * BORRADOR se guarda sin ventana ni términos, y fuera de BORRADOR la base los exige.
 * Cada test hace rollback al terminar.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@Transactional
class RifaServiceIntegracionTest {

    private static final Instant MANIANA = Instant.now().plus(1, ChronoUnit.DAYS);
    private static final Instant EN_UNA_SEMANA = Instant.now().plus(7, ChronoUnit.DAYS);

    @Autowired private RifaService rifaService;
    @Autowired private RifaRepository rifaRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private JdbcTemplate jdbc;

    private Long organizador;
    private Long otro;

    @BeforeEach
    void usuarios() {
        organizador = usuario("org_rifa").getId();
        otro = usuario("otro_rifa").getId();
    }

    // --- alta ---------------------------------------------------------------

    @Test
    void creaEnBorradorSinPremios() {
        RifaDetalle rifa = rifaService.crear(organizador, request(1, 100, MANIANA, EN_UNA_SEMANA));

        assertThat(rifa.id()).isNotNull();
        assertThat(rifa.estado()).isEqualTo(EstadoRifa.BORRADOR);
        assertThat(rifa.moneda()).isEqualTo("ARS");
        assertThat(rifa.terminosAceptadosEn()).isNull();
        assertThat(rifa.creadoEn()).isNotNull();
        assertThat(rifa.premios()).isEmpty();
    }

    @Test
    void enBorradorSeGuardaSinVentana() {
        RifaDetalle rifa = rifaService.crear(organizador, request(0, 99, null, null));

        assertThat(rifa.ventaDesde()).isNull();
        assertThat(rifa.ventaHasta()).isNull();
    }

    @Test
    void rangoInvertidoEsRangoInvalido() {
        assertThatValidacion(() -> rifaService.crear(organizador, request(50, 10, null, null)))
                .containsEntry("numeroHasta", ErrorCampo.Codigo.RANGO_INVALIDO);
    }

    @Test
    void ventanaConUnaSolaFechaPideLaOtra() {
        assertThatValidacion(() -> rifaService.crear(organizador, request(1, 10, MANIANA, null)))
                .containsEntry("ventaHasta", ErrorCampo.Codigo.REQUERIDO);
    }

    @Test
    void ventanaQueTerminaAntesDeEmpezarEsInvalida() {
        assertThatValidacion(() -> rifaService.crear(organizador, request(1, 10, EN_UNA_SEMANA, MANIANA)))
                .containsEntry("ventaHasta", ErrorCampo.Codigo.VENTANA_INVALIDA);
    }

    @Test
    void ventanaQueYaTerminoEsInvalida() {
        Instant haceUnaSemana = Instant.now().minus(7, ChronoUnit.DAYS);
        Instant ayer = Instant.now().minus(1, ChronoUnit.DAYS);

        assertThatValidacion(() -> rifaService.crear(organizador, request(1, 10, haceUnaSemana, ayer)))
                .containsEntry("ventaHasta", ErrorCampo.Codigo.VENTANA_INVALIDA);
    }

    @Test
    void juntaTodosLosErroresEnUnaRespuesta() {
        assertThatValidacion(() -> rifaService.crear(organizador, request(50, 10, EN_UNA_SEMANA, MANIANA)))
                .containsOnlyKeys("numeroHasta", "ventaHasta");
    }

    // --- modificación -------------------------------------------------------

    @Test
    void modificarCambiaLosDatosGeneralesSinTocarLosPremios() {
        RifaDetalle creada = rifaService.crear(organizador, request(1, 100, null, null));
        rifaService.reemplazarPremios(organizador, creada.id(), List.of(premio("60", "Primero"), premio("40", null)));

        RifaDetalle modificada = rifaService.modificar(organizador, creada.id(), new RifaRequest(
                "Rifa nueva", "  Con descripción  ", 0, 49, new BigDecimal("1000"), MANIANA, EN_UNA_SEMANA));

        assertThat(modificada.titulo()).isEqualTo("Rifa nueva");
        assertThat(modificada.descripcion()).isEqualTo("Con descripción");
        assertThat(modificada.numeroDesde()).isZero();
        assertThat(modificada.numeroHasta()).isEqualTo(49);
        assertThat(modificada.ventaHasta()).isEqualTo(EN_UNA_SEMANA);
        assertThat(modificada.premios()).extracting(PremioRifaDetalle::puesto, PremioRifaDetalle::descripcion)
                .containsExactly(tuple(1, "Primero"), tuple(2, null));
    }

    @Test
    void noSeAchicaElRangoPorDebajoDeLosPremiosCargados() {
        RifaDetalle creada = rifaService.crear(organizador, request(1, 10, null, null));
        rifaService.reemplazarPremios(organizador, creada.id(),
                List.of(premio("50", null), premio("30", null), premio("20", null)));

        assertThatValidacion(() -> rifaService.modificar(organizador, creada.id(), request(1, 2, null, null)))
                .containsEntry("numeroHasta", ErrorCampo.Codigo.RANGO_INVALIDO);
        assertThat(rifaService.modificar(organizador, creada.id(), request(1, 3, null, null)).numeroHasta())
                .isEqualTo(3);
    }

    @Test
    void laRifaDeOtroNoSeEncuentra() {
        RifaDetalle ajena = rifaService.crear(otro, request(1, 10, null, null));

        assertThatThrownBy(() -> rifaService.modificar(organizador, ajena.id(), request(1, 10, null, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getCodigo()).isEqualTo(CodigoError.RIFA_NO_ENCONTRADA);
                });
        assertThatThrownBy(() -> rifaService.reemplazarPremios(organizador, ajena.id(), List.of()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCodigo()).isEqualTo(CodigoError.RIFA_NO_ENCONTRADA));
        assertThatThrownBy(() -> rifaService.detalle(organizador, ajena.id()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCodigo()).isEqualTo(CodigoError.RIFA_NO_ENCONTRADA));
    }

    @Test
    void soloSeModificaEnBorrador() {
        RifaDetalle creada = rifaService.crear(organizador, request(1, 10, MANIANA, EN_UNA_SEMANA));
        Rifa rifa = rifaRepository.findById(creada.id()).orElseThrow();
        rifa.setTerminosAceptadosEn(Instant.now());
        rifa.setEstado(EstadoRifa.EN_VENTA);
        rifaRepository.saveAndFlush(rifa);

        assertThatThrownBy(() -> rifaService.modificar(organizador, creada.id(), request(1, 10, null, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getCodigo()).isEqualTo(CodigoError.RIFA_NO_EDITABLE);
                });
        assertThatThrownBy(() -> rifaService.reemplazarPremios(organizador, creada.id(), List.of(premio("100", null))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCodigo()).isEqualTo(CodigoError.RIFA_NO_EDITABLE));
    }

    // --- premios ------------------------------------------------------------

    @Test
    void reemplazarPremiosLosGuardaEnOrdenDePuesto() {
        RifaDetalle creada = rifaService.crear(organizador, request(1, 100, null, null));

        RifaDetalle rifa = rifaService.reemplazarPremios(organizador, creada.id(),
                List.of(premio("60", "Una bici"), premio("40", null)));

        assertThat(rifa.premios()).extracting(PremioRifaDetalle::puesto, PremioRifaDetalle::descripcion)
                .containsExactly(tuple(1, "Una bici"), tuple(2, null));
        assertThat(rifa.premios()).extracting(PremioRifaDetalle::pct)
                .containsExactly(60, 40);
    }

    @Test
    void reemplazarPremiosPisaLosAnterioresYVacioLosBorra() {
        RifaDetalle creada = rifaService.crear(organizador, request(1, 100, null, null));
        rifaService.reemplazarPremios(organizador, creada.id(),
                List.of(premio("50", "Primero"), premio("30", "Segundo"), premio("20", "Tercero")));

        RifaDetalle uno = rifaService.reemplazarPremios(organizador, creada.id(), List.of(premio("100", "Único")));
        assertThat(uno.premios()).extracting(PremioRifaDetalle::puesto, PremioRifaDetalle::descripcion)
                .containsExactly(tuple(1, "Único"));

        RifaDetalle ninguno = rifaService.reemplazarPremios(organizador, creada.id(), List.of());
        assertThat(ninguno.premios()).isEmpty();
        assertThat(rifaService.detalle(organizador, creada.id()).premios()).isEmpty();
    }

    @Test
    void enBorradorLosPremiosPuedenSumarMenosDeCien() {
        RifaDetalle creada = rifaService.crear(organizador, request(1, 100, null, null));

        assertThat(rifaService.reemplazarPremios(organizador, creada.id(), List.of(premio("30", null))).premios())
                .hasSize(1);
    }

    @Test
    void premiosQueSumanMasDeCienSonInvalidos() {
        RifaDetalle creada = rifaService.crear(organizador, request(1, 10, null, null));

        assertThatValidacion(() -> rifaService.reemplazarPremios(organizador, creada.id(),
                List.of(premio("70", null), premio("31", null))))
                .containsEntry("premios", ErrorCampo.Codigo.PREMIOS_INVALIDOS);
    }

    @Test
    void noPuedeHaberMasPremiosQueNumeros() {
        RifaDetalle creada = rifaService.crear(organizador, request(1, 2, null, null));

        assertThatValidacion(() -> rifaService.reemplazarPremios(organizador, creada.id(),
                List.of(premio("50", null), premio("30", null), premio("20", null))))
                .containsEntry("premios", ErrorCampo.Codigo.PREMIOS_INVALIDOS);
    }

    // --- listado ------------------------------------------------------------

    @Test
    void misRifasTraeSoloLasPropiasNoEliminadasLaMasNuevaPrimero() {
        RifaDetalle vieja = rifaService.crear(organizador, request(1, 10, null, null));
        RifaDetalle nueva = rifaService.crear(organizador, request(1, 10, null, null));
        RifaDetalle eliminada = rifaService.crear(organizador, request(1, 10, null, null));
        rifaService.crear(otro, request(1, 10, null, null));
        Rifa baja = rifaRepository.findById(eliminada.id()).orElseThrow();
        baja.setEliminadoEn(Instant.now());
        rifaRepository.saveAndFlush(baja);

        Page<RifaResumen> pagina = rifaService.listarMias(organizador, PageRequest.of(0, 20));

        assertThat(pagina.getContent()).extracting(RifaResumen::id).containsExactly(nueva.id(), vieja.id());
    }

    // --- V10 ----------------------------------------------------------------

    @Test
    void fueraDeBorradorLaBaseExigeVentanaYTerminos() {
        RifaDetalle rifa = rifaService.crear(organizador, request(1, 10, null, null));

        assertThatThrownBy(() -> jdbc.update("UPDATE rifa SET estado = 'EN_VENTA' WHERE id = ?", rifa.id()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_rifa_completa_fuera_de_borrador");
    }

    // --- ayudas -------------------------------------------------------------

    private static RifaRequest request(int desde, int hasta, Instant ventaDesde, Instant ventaHasta) {
        return new RifaRequest("Rifa de prueba", null, desde, hasta, new BigDecimal("500"), ventaDesde, ventaHasta);
    }

    private static PremioRifaRequest premio(String pct, String descripcion) {
        return new PremioRifaRequest(new BigDecimal(pct), descripcion);
    }

    /** Los errores del 400 como campo → código. */
    @SuppressWarnings("unchecked")
    private static MapAssert<String, ErrorCampo.Codigo> assertThatValidacion(ThrowingCallable llamada) {
        Throwable error = catchThrowable(llamada);
        assertThat(error).isInstanceOf(ApiException.class);
        ApiException api = (ApiException) error;
        assertThat(api.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(api.getCodigo()).isEqualTo(CodigoError.VALIDACION);
        List<ErrorCampo> errores = (List<ErrorCampo>) api.getPropiedades().get("errores");
        Map<String, ErrorCampo.Codigo> porCampo = new LinkedHashMap<>();
        errores.forEach(e -> porCampo.putIfAbsent(e.campo(), e.codigo()));
        return assertThat(porCampo);
    }

    private Usuario usuario(String nombre) {
        Persona persona = new Persona();
        persona.setNombres(nombre);
        persona.setApellidos("Test");
        persona.setEstado(EstadoGeneral.ACTIVO);
        persona = personaRepository.save(persona);

        Usuario usuario = new Usuario();
        usuario.setUsername(nombre);
        usuario.setEmail(nombre + "@example.com");
        usuario.setEstado(EstadoUsuario.ACTIVO);
        usuario.setEmailVerificado(true);
        usuario.setPersona(persona);
        return usuarioRepository.save(usuario);
    }
}
