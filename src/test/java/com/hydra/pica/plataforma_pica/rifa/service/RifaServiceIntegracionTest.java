package com.hydra.pica.plataforma_pica.rifa.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.groups.Tuple.tuple;

import java.math.BigDecimal;
import java.sql.Timestamp;
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
import com.hydra.pica.plataforma_pica.rifa.dto.FaltantePublicacion;
import com.hydra.pica.plataforma_pica.rifa.dto.PremioRifaDetalle;
import com.hydra.pica.plataforma_pica.rifa.dto.PremioRifaRequest;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaDetalle;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaRequest;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaResumen;
import com.hydra.pica.plataforma_pica.rifa.repository.RifaRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Modalidad;
import com.hydra.pica.plataforma_pica.torneo.domain.TipoParticipante;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import jakarta.persistence.EntityManager;
import org.assertj.core.api.ListAssert;
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
 * Alta, modificación, premios y listado de rifas sin torneo (PICA-226) y con torneo (PICA-218) contra Postgres,
 * con V10: una rifa en BORRADOR se guarda sin ventana ni términos, y fuera de BORRADOR la base los exige.
 * Cada test hace rollback al terminar.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@Transactional
class RifaServiceIntegracionTest {

    private static final Instant MANIANA = Instant.now().plus(1, ChronoUnit.DAYS);
    private static final Instant EN_UNA_SEMANA = Instant.now().plus(7, ChronoUnit.DAYS);
    private static final Instant SORTEO = EN_UNA_SEMANA.plus(1, ChronoUnit.DAYS);

    @Autowired private RifaService rifaService;
    @Autowired private VentaRifaService ventaRifaService;
    @Autowired private RifaRepository rifaRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private TorneoRepository torneoRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

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
                "Rifa nueva", "  Con descripción  ", 0, 49, new BigDecimal("1000"), MANIANA, EN_UNA_SEMANA, null,
                null, null));

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
        rifa.setLoteriaDeclaradaEn(Instant.now());
        rifa.setSorteadaEn(EN_UNA_SEMANA.plus(1, ChronoUnit.DAYS));
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

    // --- fecha del sorteo y publicación (PICA-219) ---------------------------

    @Test
    void elSorteoEsDespuesDeQueTerminaLaVenta() {
        assertThatValidacion(() -> rifaService.crear(organizador,
                requestConSorteo(MANIANA, EN_UNA_SEMANA, EN_UNA_SEMANA.minus(1, ChronoUnit.HOURS))))
                .containsEntry("fechaSorteo", ErrorCampo.Codigo.VENTANA_INVALIDA);
    }

    @Test
    void elSorteoNoPuedeSerEnElPasado() {
        assertThatValidacion(() -> rifaService.crear(organizador,
                requestConSorteo(null, null, Instant.now().minus(1, ChronoUnit.DAYS))))
                .containsEntry("fechaSorteo", ErrorCampo.Codigo.VENTANA_INVALIDA);
    }

    @Test
    void publicaUnaRifaCompletaYQuedaEnVenta() {
        Long id = rifaLista(premio("60", null), premio("40", null));

        RifaDetalle publicada = rifaService.publicar(organizador, id);

        assertThat(publicada.estado()).isEqualTo(EstadoRifa.EN_VENTA);
        assertThat(publicada.terminosAceptadosEn()).isNotNull();
        assertThat(publicada.loteriaDeclaradaEn()).isNotNull();
        // Postgres guarda microsegundos
        assertThat(publicada.fechaSorteo()).isCloseTo(SORTEO, within(1, ChronoUnit.MILLIS));
        assertThat(publicada.premios()).hasSize(2);
        // los gastos administrativos vigentes (V26) quedan fijos en la rifa (PICA-222)
        assertThat(rifaRepository.findById(id).orElseThrow().getPctGastosAdministrativos()).isEqualByComparingTo("10");
    }

    @Test
    void unaRifaIncompletaNoSePublicaYDiceTodoLoQueFalta() {
        RifaDetalle borrador = rifaService.crear(organizador, request(1, 10, null, null));

        assertThatIncompleta(() -> rifaService.publicar(organizador, borrador.id()))
                .containsExactly(FaltantePublicacion.VENTANA, FaltantePublicacion.FECHA_SORTEO,
                        FaltantePublicacion.SIN_PREMIOS);
    }

    @Test
    void losPremiosTienenQueSumarCien() {
        Long id = rifaLista(premio("60", null));

        Throwable error = catchThrowable(() -> rifaService.publicar(organizador, id));

        assertThat(error).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getCodigo()).isEqualTo(CodigoError.RIFA_INCOMPLETA);
            assertThat(e.getPropiedades().get("faltantes")).isEqualTo(List.of(FaltantePublicacion.PREMIOS_NO_SUMAN_100));
            assertThat(e.getPropiedades().get("sumaPremios")).isEqualTo(60);
        });
    }

    @Test
    void conElInicioDeLaVentaPasadoSePublicaYLaVentaArrancaYa() {
        RifaDetalle creada = rifaService.crear(organizador,
                requestConSorteo(Instant.now().minus(1, ChronoUnit.HOURS), EN_UNA_SEMANA, SORTEO));
        rifaService.reemplazarPremios(organizador, creada.id(), List.of(premio("100", null)));

        assertThat(rifaService.publicar(organizador, creada.id()).estado()).isEqualTo(EstadoRifa.EN_VENTA);
    }

    @Test
    void conElFinDeLaVentaPasadoNoSePublica() {
        Long id = rifaLista(premio("100", null));
        jdbc.update("UPDATE rifa SET venta_desde = now() - interval '3 days', venta_hasta = now() - interval '1 day'"
                + " WHERE id = ?", id);
        entityManager.clear();

        assertThatIncompleta(() -> rifaService.publicar(organizador, id))
                .containsExactly(FaltantePublicacion.VENTA_TERMINADA);
    }

    // --- volver a borrador y eliminar (PICA-287) -------------------------------------------------------

    @Test
    void unaRifaPublicadaSinComprasVuelveABorradorYSePuedeModificar() {
        Long id = rifaLista(premio("100", null));
        rifaService.publicar(organizador, id);
        abrirLaVenta(id);

        RifaDetalle borrador = rifaService.volverABorrador(organizador, id);

        assertThat(borrador.estado()).isEqualTo(EstadoRifa.BORRADOR);
        assertThat(borrador.terminosAceptadosEn()).isNull();
        assertThat(borrador.loteriaDeclaradaEn()).isNull();
        assertThat(rifaRepository.findById(id).orElseThrow().getPctGastosAdministrativos()).isNull();
        assertThat(rifaService.modificar(organizador, id, requestConSorteo(MANIANA, EN_UNA_SEMANA, SORTEO)).estado())
                .isEqualTo(EstadoRifa.BORRADOR);
    }

    @Test
    void conNumerosVendidosNoVuelveABorradorNiSeElimina() {
        Long id = rifaLista(premio("100", null));
        rifaService.publicar(organizador, id);
        abrirLaVenta(id);
        ventaRifaService.reservar(otro, id, List.of(5));
        ventaRifaService.comprar(otro, id, List.of(5), "compra-1");

        assertThat(rifaService.detalle(organizador, id).numerosVendidos()).isEqualTo(1);
        assertThatThrownBy(() -> rifaService.volverABorrador(organizador, id)).isInstanceOfSatisfying(
                ApiException.class, e -> assertThat(e.getCodigo()).isEqualTo(CodigoError.RIFA_CON_VENTAS));
        assertThatThrownBy(() -> rifaService.eliminar(organizador, id)).isInstanceOfSatisfying(
                ApiException.class, e -> assertThat(e.getCodigo()).isEqualTo(CodigoError.RIFA_CON_VENTAS));
    }

    @Test
    void conAlguienPagandoNoVuelveABorradorPeroConLaReservaVencidaSi() {
        Long id = rifaLista(premio("100", null));
        rifaService.publicar(organizador, id);
        abrirLaVenta(id);
        ventaRifaService.reservar(otro, id, List.of(5));

        assertThatThrownBy(() -> rifaService.volverABorrador(organizador, id)).isInstanceOfSatisfying(
                ApiException.class, e -> assertThat(e.getCodigo()).isEqualTo(CodigoError.RIFA_CON_RESERVAS));

        jdbc.update("UPDATE numero SET creado_en = now() - interval '11 minutes' WHERE rifa_id = ?", id);
        entityManager.clear();
        assertThat(rifaService.volverABorrador(organizador, id).estado()).isEqualTo(EstadoRifa.BORRADOR);
        // la reserva vencida se borró
        assertThat(jdbc.queryForObject("SELECT count(*) FROM numero WHERE rifa_id = ?", Integer.class, id)).isZero();
    }

    @Test
    void unBorradorSeEliminaYDejaDeAparecer() {
        RifaDetalle borrador = rifaService.crear(organizador, request(1, 10, null, null));

        rifaService.eliminar(organizador, borrador.id());

        assertThat(rifaService.listarMias(organizador, PageRequest.of(0, 20)).getContent()).isEmpty();
        assertThatThrownBy(() -> rifaService.detalle(organizador, borrador.id())).isInstanceOfSatisfying(
                ApiException.class, e -> assertThat(e.getCodigo()).isEqualTo(CodigoError.RIFA_NO_ENCONTRADA));
    }

    @Test
    void alEliminarLaRifaSuTorneoQuedaLibreParaOtra() {
        Long torneo = torneo(organizador, null, null);
        RifaDetalle primera = rifaService.crear(organizador, conTorneo(null, null, null, torneo, 0));

        rifaService.eliminar(organizador, primera.id());

        assertThat(rifaService.crear(organizador, conTorneo(null, null, null, torneo, 0)).torneoId()).isEqualTo(torneo);
    }

    @Test
    void laRifaDeOtroNoSeEliminaNiVuelveABorrador() {
        RifaDetalle ajena = rifaService.crear(otro, request(1, 10, null, null));

        assertThatThrownBy(() -> rifaService.eliminar(organizador, ajena.id())).isInstanceOfSatisfying(
                ApiException.class, e -> assertThat(e.getCodigo()).isEqualTo(CodigoError.RIFA_NO_ENCONTRADA));
        assertThatThrownBy(() -> rifaService.volverABorrador(organizador, ajena.id())).isInstanceOfSatisfying(
                ApiException.class, e -> assertThat(e.getCodigo()).isEqualTo(CodigoError.RIFA_NO_ENCONTRADA));
    }

    @Test
    void soloSePublicaUnBorrador() {
        Long id = rifaLista(premio("100", null));
        rifaService.publicar(organizador, id);

        assertThatThrownBy(() -> rifaService.publicar(organizador, id))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCodigo()).isEqualTo(CodigoError.RIFA_NO_EDITABLE));
    }

    @Test
    void laRifaDeOtroNoSePublica() {
        RifaDetalle ajena = rifaService.crear(otro, request(1, 10, null, null));

        assertThatThrownBy(() -> rifaService.publicar(organizador, ajena.id()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCodigo()).isEqualTo(CodigoError.RIFA_NO_ENCONTRADA));
    }

    // --- con torneo (HU-401) -------------------------------------------------

    @Test
    void seAsociaAUnTorneoPropioSinPublicarYSinFechas() {
        Long torneo = torneo(organizador, null, null);

        RifaDetalle rifa = rifaService.crear(organizador, conTorneo(MANIANA, EN_UNA_SEMANA, SORTEO, torneo, 35));

        assertThat(rifa.torneoId()).isEqualTo(torneo);
        assertThat(rifa.torneoNombre()).isEqualTo("Copa de prueba");
        assertThat(rifa.pctDestinadoTorneo()).isEqualTo(35);
        assertThat(rifaService.listarMias(organizador, PageRequest.of(0, 20)).getContent())
                .extracting(RifaResumen::torneoId, RifaResumen::torneoNombre)
                .containsExactly(tuple(torneo, "Copa de prueba"));
    }

    @Test
    void elTorneoDeOtroNoSeEncuentra() {
        Long ajeno = torneo(otro, null, null);

        assertThatThrownBy(() -> rifaService.crear(organizador, conTorneo(null, null, null, ajeno, 0)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getCodigo()).isEqualTo(CodigoError.TORNEO_NO_ENCONTRADO);
                });
    }

    @Test
    void unTorneoQueYaEmpezoNoAdmiteRifa() {
        Long enCurso = torneoEnCurso();

        assertThatThrownBy(() -> rifaService.crear(organizador, conTorneo(null, null, null, enCurso, 0)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getCodigo()).isEqualTo(CodigoError.TORNEO_NO_ADMITE_RIFA);
                });
    }

    @Test
    void unTorneoTieneUnaSolaRifa() {
        Long torneo = torneo(organizador, null, null);
        RifaDetalle primera = rifaService.crear(organizador, conTorneo(null, null, null, torneo, 0));
        RifaDetalle segunda = rifaService.crear(organizador, request(1, 10, null, null));

        assertThatThrownBy(() -> rifaService.modificar(organizador, segunda.id(), conTorneo(null, null, null, torneo, 0)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getCodigo()).isEqualTo(CodigoError.TORNEO_CON_RIFA);
                });
        // la que ya lo tiene se sigue modificando
        assertThat(rifaService.modificar(organizador, primera.id(), conTorneo(null, null, null, torneo, 0)).torneoId())
                .isEqualTo(torneo);
    }

    @Test
    void laVentaVaDeLaPublicacionDelTorneoASuInicio() {
        Long torneo = torneo(organizador, MANIANA, EN_UNA_SEMANA);
        Instant hoy = Instant.now().plus(1, ChronoUnit.HOURS);

        assertThatValidacion(() -> rifaService.crear(organizador,
                conTorneo(hoy, EN_UNA_SEMANA.plus(1, ChronoUnit.HOURS), null, torneo, 0)))
                .containsEntry("ventaDesde", ErrorCampo.Codigo.VENTANA_INVALIDA)
                .containsEntry("ventaHasta", ErrorCampo.Codigo.VENTANA_INVALIDA);
        assertThat(rifaService.crear(organizador, conTorneo(MANIANA, EN_UNA_SEMANA, null, torneo, 0)).id()).isNotNull();
    }

    @Test
    void lasFechasQueElTorneoNoTieneNoSeControlan() {
        Long torneo = torneo(organizador, null, MANIANA);

        // sin publicadoEn, el comienzo de la venta no se compara; el final, con fechaInicio, sí
        assertThatValidacion(() -> rifaService.crear(organizador, conTorneo(MANIANA, EN_UNA_SEMANA, null, torneo, 0)))
                .containsOnlyKeys("ventaHasta");
    }

    @Test
    void conFechaDeFinLaVentaPuedeSeguirMientrasSeJuegaElTorneo() {
        Long torneo = torneo(organizador, null, MANIANA);
        jdbc.update("UPDATE torneo SET fecha_fin = ? WHERE id = ?", Timestamp.from(EN_UNA_SEMANA), torneo);
        entityManager.clear();

        // termina después del inicio pero antes del fin: vale
        RifaDetalle rifa = rifaService.crear(organizador,
                conTorneo(MANIANA, EN_UNA_SEMANA.minus(1, ChronoUnit.HOURS), null, torneo, 0));
        assertThatValidacion(() -> rifaService.modificar(organizador, rifa.id(),
                conTorneo(MANIANA, EN_UNA_SEMANA.plus(1, ChronoUnit.HOURS), null, torneo, 0)))
                .containsOnlyKeys("ventaHasta");
    }

    @Test
    void sinTorneoNoSeDestinaNada() {
        Long torneo = torneo(organizador, null, null);
        RifaDetalle conTorneo = rifaService.crear(organizador, conTorneo(null, null, null, torneo, 35));

        assertThatValidacion(() -> rifaService.crear(organizador, conTorneo(null, null, null, null, 10)))
                .containsEntry("pctDestinadoTorneo", ErrorCampo.Codigo.PREMIOS_INVALIDOS);
        RifaDetalle sacado = rifaService.modificar(organizador, conTorneo.id(), conTorneo(null, null, null, null, 0));
        assertThat(sacado.torneoId()).isNull();
        assertThat(sacado.pctDestinadoTorneo()).isZero();
    }

    @Test
    void loDestinadoAlTorneoYLosPremiosNoPasanDeCien() {
        Long torneo = torneo(organizador, null, null);
        RifaDetalle creada = rifaService.crear(organizador, conTorneo(null, null, null, torneo, 35));

        // desde los premios
        assertThatValidacion(() -> rifaService.reemplazarPremios(organizador, creada.id(),
                List.of(premio("40", null), premio("30", null))))
                .containsEntry("premios", ErrorCampo.Codigo.PREMIOS_INVALIDOS);
        // y desde los datos generales, con los premios ya cargados
        rifaService.reemplazarPremios(organizador, creada.id(), List.of(premio("65", null)));
        assertThatValidacion(() -> rifaService.modificar(organizador, creada.id(),
                conTorneo(null, null, null, torneo, 40)))
                .containsEntry("pctDestinadoTorneo", ErrorCampo.Codigo.PREMIOS_INVALIDOS);
    }

    @Test
    void publicaConElTorneoPublicadoYTodoSumandoCien() {
        Long torneo = torneo(organizador, Instant.now().minus(1, ChronoUnit.DAYS), SORTEO);
        RifaDetalle creada = rifaService.crear(organizador, conTorneo(MANIANA, EN_UNA_SEMANA, SORTEO, torneo, 35));
        rifaService.reemplazarPremios(organizador, creada.id(), List.of(premio("40", null), premio("25", null)));

        RifaDetalle publicada = rifaService.publicar(organizador, creada.id());

        assertThat(publicada.estado()).isEqualTo(EstadoRifa.EN_VENTA);
        assertThat(publicada.pctDestinadoTorneo()).isEqualTo(35);
    }

    @Test
    void conElTorneoSinPublicarNoSePublica() {
        Long torneo = torneo(organizador, null, null);
        RifaDetalle creada = rifaService.crear(organizador, conTorneo(MANIANA, EN_UNA_SEMANA, SORTEO, torneo, 0));
        rifaService.reemplazarPremios(organizador, creada.id(), List.of(premio("100", null)));

        assertThatIncompleta(() -> rifaService.publicar(organizador, creada.id()))
                .containsExactly(FaltantePublicacion.TORNEO_NO_PUBLICADO);
    }

    @Test
    void siElTorneoCambioSusFechasLaVentaQuedaFuera() {
        Long torneo = torneo(organizador, null, null);
        RifaDetalle creada = rifaService.crear(organizador, conTorneo(MANIANA, EN_UNA_SEMANA, SORTEO, torneo, 35));
        rifaService.reemplazarPremios(organizador, creada.id(), List.of(premio("65", null)));
        // el torneo se publica después, con un inicio anterior al fin de la venta
        jdbc.update("UPDATE torneo SET publicado_en = now(), fecha_inicio = ? WHERE id = ?",
                Timestamp.from(MANIANA.plus(1, ChronoUnit.DAYS)), torneo);

        assertThatIncompleta(() -> rifaService.publicar(organizador, creada.id()))
                .containsExactly(FaltantePublicacion.VENTANA_FUERA_DEL_TORNEO);
    }

    @Test
    void loDestinadoAlTorneoYLosPremiosTienenQueSumarCien() {
        Long torneo = torneo(organizador, Instant.now().minus(1, ChronoUnit.DAYS), SORTEO);
        RifaDetalle creada = rifaService.crear(organizador, conTorneo(MANIANA, EN_UNA_SEMANA, SORTEO, torneo, 35));
        rifaService.reemplazarPremios(organizador, creada.id(), List.of(premio("50", null)));

        assertThatIncompleta(() -> rifaService.publicar(organizador, creada.id()))
                .containsExactly(FaltantePublicacion.PREMIOS_NO_SUMAN_100);
    }

    @Test
    void reemplazarLosPremiosDevuelveElTorneoAunqueSeLimpieLaSesion() {
        Long torneo = torneo(organizador, null, null);
        RifaDetalle creada = rifaService.crear(organizador, conTorneo(null, null, null, torneo, 20));
        // como en un pedido nuevo: la rifa y el torneo se vuelven a leer de la base
        entityManager.clear();

        RifaDetalle rifa = rifaService.reemplazarPremios(organizador, creada.id(), List.of(premio("80", null)));

        assertThat(rifa.torneoNombre()).isEqualTo("Copa de prueba");
    }

    @Test
    void laBaseNoAceptaDestinarATorneoSinTorneo() {
        RifaDetalle rifa = rifaService.crear(organizador, request(1, 10, null, null));

        assertThatThrownBy(() -> jdbc.update("UPDATE rifa SET pct_destinado_torneo = 10 WHERE id = ?", rifa.id()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_rifa_pct_destinado_torneo");
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

    @Test
    void fueraDeBorradorLaBaseExigeLoteriaYFechaDeSorteo() {
        RifaDetalle rifa = rifaService.crear(organizador, request(1, 10, MANIANA, EN_UNA_SEMANA));

        // con ventana y términos alcanzaba en V10; V17 pide también la lotería y el sorteo
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE rifa SET estado = 'EN_VENTA', terminos_aceptados_en = now() WHERE id = ?", rifa.id()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_rifa_completa_fuera_de_borrador");
    }

    @Test
    void laBaseNoAceptaUnSorteoAntesDelFinDeLaVenta() {
        RifaDetalle rifa = rifaService.crear(organizador, request(1, 10, MANIANA, EN_UNA_SEMANA));

        assertThatThrownBy(() -> jdbc.update("UPDATE rifa SET sorteada_en = venta_hasta WHERE id = ?", rifa.id()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_rifa_sorteo_despues_de_la_venta");
    }

    // --- ayudas -------------------------------------------------------------

    private static RifaRequest requestConSorteo(Instant ventaDesde, Instant ventaHasta, Instant fechaSorteo) {
        return conTorneo(ventaDesde, ventaHasta, fechaSorteo, null, 0);
    }

    private static RifaRequest conTorneo(Instant ventaDesde, Instant ventaHasta, Instant fechaSorteo, Long torneoId,
            int pctDestinadoTorneo) {
        return new RifaRequest("Rifa de prueba", null, 1, 100, new BigDecimal("500"), ventaDesde, ventaHasta,
                fechaSorteo, torneoId, BigDecimal.valueOf(pctDestinadoTorneo));
    }

    /** Un torneo publicado que ya empezó: fuera de BORRADOR la base exige todos sus datos. */
    private Long torneoEnCurso() {
        Torneo torneo = new Torneo();
        torneo.setOrganizador(usuarioRepository.getReferenceById(organizador));
        torneo.setNombre("Copa en curso");
        torneo.setDisciplina("Paddle");
        torneo.setReglamentoTexto("Dos sets a 6 games");
        torneo.setModalidad(Modalidad.PRESENCIAL);
        torneo.setTipoParticipante(TipoParticipante.INDIVIDUAL);
        torneo.setCupoMaximo(8);
        torneo.setCupoMinimo(2);
        torneo.setValorInscripcion(new BigDecimal("1000"));
        torneo.setCierreInscripcionEn(Instant.now().minus(2, ChronoUnit.DAYS));
        torneo.setFechaInicio(Instant.now().minus(1, ChronoUnit.DAYS));
        torneo.setPublicadoEn(Instant.now().minus(10, ChronoUnit.DAYS));
        torneo.setEstado(EstadoTorneo.EN_CURSO);
        return torneoRepository.saveAndFlush(torneo).getId();
    }

    /**
     * Un torneo en BORRADOR con las fechas que se le pasen: a la rifa solo le importan {@code publicadoEn} y
     * {@code fechaInicio}, y en BORRADOR la base no exige el resto.
     */
    private Long torneo(Long organizadorId, Instant publicadoEn, Instant fechaInicio) {
        Torneo torneo = new Torneo();
        torneo.setOrganizador(usuarioRepository.getReferenceById(organizadorId));
        torneo.setNombre("Copa de prueba");
        torneo.setDisciplina("Paddle");
        torneo.setPublicadoEn(publicadoEn);
        torneo.setFechaInicio(fechaInicio);
        return torneoRepository.saveAndFlush(torneo).getId();
    }

    /** La venta de una rifa publicada, abierta desde hace una hora, para poder comprar. */
    private void abrirLaVenta(Long id) {
        jdbc.update("UPDATE rifa SET venta_desde = now() - interval '1 hour' WHERE id = ?", id);
        entityManager.clear();
    }

    /** Una rifa con ventana y sorteo cargados, lista para publicar si los premios suman 100. */
    private Long rifaLista(PremioRifaRequest... premios) {
        RifaDetalle creada = rifaService.crear(organizador, requestConSorteo(MANIANA, EN_UNA_SEMANA, SORTEO));
        rifaService.reemplazarPremios(organizador, creada.id(), List.of(premios));
        return creada.id();
    }

    @SuppressWarnings("unchecked")
    private static ListAssert<FaltantePublicacion> assertThatIncompleta(ThrowingCallable llamada) {
        Throwable error = catchThrowable(llamada);
        assertThat(error).isInstanceOf(ApiException.class);
        ApiException api = (ApiException) error;
        assertThat(api.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(api.getCodigo()).isEqualTo(CodigoError.RIFA_INCOMPLETA);
        return assertThat((List<FaltantePublicacion>) api.getPropiedades().get("faltantes"));
    }

    private static RifaRequest request(int desde, int hasta, Instant ventaDesde, Instant ventaHasta) {
        return new RifaRequest("Rifa de prueba", null, desde, hasta, new BigDecimal("500"), ventaDesde, ventaHasta, null,
                null, null);
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
