package com.hydra.pica.plataforma_pica.reputacion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import com.hydra.pica.plataforma_pica.EscenarioTorneo;
import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.inscripcion.service.InscripcionService;
import com.hydra.pica.plataforma_pica.partido.dto.ResultadoRequest;
import com.hydra.pica.plataforma_pica.partido.service.ResultadoService;
import com.hydra.pica.plataforma_pica.reputacion.dto.PerfilOrganizador;
import com.hydra.pica.plataforma_pica.reputacion.dto.PerfilOrganizador.MovimientoIndice;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Modalidad;
import com.hydra.pica.plataforma_pica.torneo.domain.PermisoArbitro;
import com.hydra.pica.plataforma_pica.torneo.domain.TipoParticipante;
import com.hydra.pica.plataforma_pica.torneo.dto.DesignarArbitroRequest;
import com.hydra.pica.plataforma_pica.torneo.dto.EconomiaTorneoRequest;
import com.hydra.pica.plataforma_pica.torneo.dto.GastoDeclarado;
import com.hydra.pica.plataforma_pica.torneo.dto.InformacionTorneoRequest;
import com.hydra.pica.plataforma_pica.torneo.dto.SolicitudCancelacion;
import com.hydra.pica.plataforma_pica.torneo.dto.TorneoDetalle;
import com.hydra.pica.plataforma_pica.torneo.dto.TorneoResumen;
import com.hydra.pica.plataforma_pica.torneo.event.CancelacionResuelta;
import com.hydra.pica.plataforma_pica.torneo.event.TorneoEstadoCambiado;
import com.hydra.pica.plataforma_pica.torneo.service.ArbitrosTorneoService;
import com.hydra.pica.plataforma_pica.torneo.service.CancelacionTorneoService;
import com.hydra.pica.plataforma_pica.torneo.service.EconomiaTorneoService;
import com.hydra.pica.plataforma_pica.torneo.service.PublicacionTorneoService;
import com.hydra.pica.plataforma_pica.torneo.service.RevisionCancelacionService;
import com.hydra.pica.plataforma_pica.torneo.service.TorneoPublicoService;
import com.hydra.pica.plataforma_pica.torneo.service.TorneoService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pruebas de aceptación de HU-806 (T806-1, PICA-429): contadores de torneos del organizador, índice parametrizable
 * con penalización por cancelación (mayor con gastos) sin bajar de 0, actualización por eventos y perfil público
 * accesible desde la ficha del torneo. Cada test corre contra PostgreSQL y hace rollback.
 */
@SpringBootTest(properties = "app.cobro.modo=saldo")
@ActiveProfiles("dev")
@Import({TestcontainersConfiguration.class, EscenarioTorneo.class})
@Transactional
@RecordApplicationEvents
class PerfilOrganizadorIntegracionTest {

    private static final Instant EN_UNA_SEMANA = Instant.now().plus(7, ChronoUnit.DAYS);
    private static final Instant EN_DOS_SEMANAS = Instant.now().plus(14, ChronoUnit.DAYS);
    private static final byte[] PDF = "%PDF-1.7\ncomprobante".getBytes(StandardCharsets.US_ASCII);

    @Autowired private PerfilOrganizadorService perfilOrganizadorService;
    @Autowired private TorneoService torneoService;
    @Autowired private EconomiaTorneoService economiaTorneoService;
    @Autowired private PublicacionTorneoService publicacionTorneoService;
    @Autowired private TorneoPublicoService torneoPublicoService;
    @Autowired private ArbitrosTorneoService arbitrosService;
    @Autowired private CancelacionTorneoService cancelacionTorneoService;
    @Autowired private RevisionCancelacionService revisionCancelacionService;
    @Autowired private ResultadoService resultadoService;
    @Autowired private InscripcionService inscripcionService;
    @Autowired private ApplicationEvents eventos;
    @Autowired private EscenarioTorneo escenario;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    private Long organizador;
    private Long arbitro;

    @BeforeEach
    void preparar() {
        organizador = escenario.usuario("org_perfil", "ORGANIZADOR").getId();
        arbitro = escenario.usuario("arb_perfil", "ARBITRO").getId();
    }

    @Test
    void organizadorSinTorneosTieneContadoresEnCeroEIndiceInicial() {
        // Crea también un borrador para comprobar que no cuenta como organizado publicado.
        torneoService.crear(organizador, new InformacionTorneoRequest("Borrador", "Ajedrez", null, null,
                Modalidad.ONLINE, TipoParticipante.INDIVIDUAL, null, null, EN_DOS_SEMANAS, null, EN_UNA_SEMANA,
                "Reglas", 8, 2, null, null));

        PerfilOrganizador perfil = perfilOrganizadorService.ver(organizador);

        assertThat(perfil.id()).isEqualTo(organizador);
        assertThat(perfil.username()).isEqualTo("org_perfil");
        assertThat(perfil.nombreCompleto()).isEqualTo("org_perfil Test");
        assertThat(perfil.torneosOrganizados()).isZero();
        assertThat(perfil.torneosFinalizados()).isZero();
        assertThat(perfil.torneosCancelados()).isZero();
        assertThat(perfil.indice()).isEqualTo(100);
        assertThat(perfil.indiceActualizadoEn()).isNull();
        assertThat(perfil.historialIndice()).isEmpty();
        assertThat(perfil.torneosProximos()).isEmpty();
    }

    @Test
    void finalizarUnTorneoIncrementaTorneosFinalizadosYSaleDeProximos() {
        Long torneo = escenario.individualEnCurso(organizador, arbitro, "Copa Finalizable", "jug_fin_", 2);
        Long finalPartido = escenario.partido(torneo, 1, 1);
        Long local = escenario.jugador(finalPartido, "LOCAL");
        Long visitante = escenario.jugador(finalPartido, "VISITANTE");

        resultadoService.cargar(local, finalPartido, new ResultadoRequest(3, 1, null, null));
        resultadoService.aceptar(visitante, finalPartido);
        entityManager.flush();
        entityManager.clear();

        assertThat(eventos.stream(TorneoEstadoCambiado.class))
                .contains(new TorneoEstadoCambiado(torneo, EstadoTorneo.EN_CURSO, EstadoTorneo.FINALIZADO));

        PerfilOrganizador perfil = perfilOrganizadorService.ver(organizador);
        assertThat(perfil.torneosOrganizados()).isEqualTo(1);
        assertThat(perfil.torneosFinalizados()).isEqualTo(1);
        assertThat(perfil.torneosCancelados()).isZero();
        assertThat(perfil.indice()).isEqualTo(100);
        assertThat(perfil.torneosProximos()).isEmpty();
    }

    @Test
    void cancelarDisminuyeElIndiceYMasSiTuvoGastosSinBajarDeCero() {
        // Cancela uno sin gastos (-15 -> 85) y otro con gastos (-30 -> 55), de forma idempotente.
        Long sinGastos = crearTorneoAbiertoPagado("Copa Sin Gastos", "jug_sg_");
        Long conGastos = crearTorneoAbiertoPagado("Copa Con Gastos", "jug_cg_");

        cancelacionTorneoService.cancelar(organizador, sinGastos, new SolicitudCancelacion("Lluvia", List.of()));
        entityManager.flush();
        assertThat(eventos.stream(TorneoEstadoCambiado.class))
                .anyMatch(e -> e.torneoId().equals(sinGastos) && e.estadoNuevo() == EstadoTorneo.CANCELADO);
        assertThat(eventos.stream(CancelacionResuelta.class))
                .anyMatch(e -> e.torneoId().equals(sinGastos));

        perfilOrganizadorService.registrarCancelacion(sinGastos);
        perfilOrganizadorService.registrarCancelacion(sinGastos);

        cancelacionTorneoService.cancelar(organizador, conGastos, new SolicitudCancelacion("Cancha rota",
                List.of(new GastoDeclarado(new BigDecimal("200.00"), "Seña cancha", PDF))));
        Long soporte = escenario.usuario("soporte_org", "ADMINISTRADOR").getId();
        revisionCancelacionService.aprobar(soporte, conGastos);
        entityManager.flush();

        perfilOrganizadorService.registrarCancelacion(conGastos);
        entityManager.flush();
        entityManager.clear();

        PerfilOrganizador perfil = perfilOrganizadorService.ver(organizador);
        assertThat(perfil.torneosOrganizados()).isEqualTo(2);
        assertThat(perfil.torneosFinalizados()).isZero();
        assertThat(perfil.torneosCancelados()).isEqualTo(2);
        assertThat(perfil.indice()).isEqualTo(55);
        assertThat(perfil.indiceActualizadoEn()).isNotNull();
        assertThat(perfil.historialIndice())
                .extracting(MovimientoIndice::torneoId, MovimientoIndice::conGastos, MovimientoIndice::delta,
                        MovimientoIndice::valorResultante)
                .containsExactly(
                        tuple(conGastos, true, -30, 55),
                        tuple(sinGastos, false, -15, 85));

        // Otra cancelación fuerte por encima del saldo del índice lo deja en 0 (nunca negativo).
        jdbc.update("UPDATE parametro_plataforma SET valor = 80 WHERE clave = 'INDICE_ORGANIZADOR_CANCELACION'");
        Long tercerTorneo = crearTorneoAbiertoPagado("Copa Tope", "jug_tp_");
        cancelacionTorneoService.cancelar(organizador, tercerTorneo, new SolicitudCancelacion("Motivo", List.of()));
        entityManager.flush();
        perfilOrganizadorService.registrarCancelacion(tercerTorneo);

        assertThat(perfilOrganizadorService.ver(organizador).indice()).isZero();
    }

    @Test
    void desdeLaFichaPublicaDelTorneoSeAccedeAlPerfilConHistorialEIndiceYProximos() {
        Long finalizado = escenario.individualEnCurso(organizador, arbitro, "Copa Pasada", "jug_pas_", 2);
        Long partidoFinal = escenario.partido(finalizado, 1, 1);
        resultadoService.cargar(escenario.jugador(partidoFinal, "LOCAL"), partidoFinal,
                new ResultadoRequest(2, 0, null, null));
        resultadoService.aceptar(escenario.jugador(partidoFinal, "VISITANTE"), partidoFinal);

        Long cancelado = crearTorneoAbiertoPagado("Copa Cancelada", "jug_canc_");
        cancelacionTorneoService.cancelar(organizador, cancelado, new SolicitudCancelacion("Fuerza mayor", List.of()));
        entityManager.flush();
        perfilOrganizadorService.registrarCancelacion(cancelado);

        Long proximo = crearTorneoAbierto("Copa Vigente");
        String slug = jdbc.queryForObject("SELECT slug FROM torneo WHERE id = ?", String.class, proximo);
        entityManager.flush();
        entityManager.clear();

        TorneoDetalle ficha = torneoPublicoService.detallePublico(slug);
        assertThat(ficha.organizador()).isNotNull();

        PerfilOrganizador perfil = perfilOrganizadorService.ver(ficha.organizador().id());
        assertThat(perfil.torneosOrganizados()).isEqualTo(3);
        assertThat(perfil.torneosFinalizados()).isEqualTo(1);
        assertThat(perfil.torneosCancelados()).isEqualTo(1);
        assertThat(perfil.indice()).isEqualTo(85);
        assertThat(perfil.torneosProximos())
                .extracting(TorneoResumen::id, TorneoResumen::slug, r -> r.organizador().id())
                .containsExactly(tuple(proximo, slug, organizador));
    }

    private Long crearTorneoAbierto(String nombre) {
        Long id = torneoService.crear(organizador, new InformacionTorneoRequest(nombre, "Fútbol 5", null, null,
                Modalidad.PRESENCIAL, TipoParticipante.INDIVIDUAL, "Sede Central", null,
                EN_DOS_SEMANAS, null, EN_UNA_SEMANA, "Reglas generales", 8, 2, null, null)).id();
        arbitrosService.designar(organizador, id,
                new DesignarArbitroRequest(arbitro, Set.of(PermisoArbitro.CARGAR_RESULTADOS)));
        economiaTorneoService.actualizarEconomia(organizador, id, new EconomiaTorneoRequest(
                new BigDecimal("1000.00"), List.of(new BigDecimal("100"))));
        publicacionTorneoService.publicar(organizador, id);
        return id;
    }

    private Long crearTorneoAbiertoPagado(String nombre, String prefijo) {
        Long torneoId = crearTorneoAbierto(nombre);
        Long jugador = escenario.usuario(prefijo + "1", "PARTICIPANTE").getId();
        jdbc.update("""
                INSERT INTO billetera (usuario_id, saldo_disponible) VALUES (?, ?)
                ON CONFLICT (usuario_id) DO UPDATE SET saldo_disponible = excluded.saldo_disponible
                """, jugador, new BigDecimal("5000.00"));
        Long insId = jdbc.queryForObject("""
                INSERT INTO inscripcion (torneo_id, usuario_id, estado, reglamento_aceptado_en)
                VALUES (?, ?, 'PENDIENTE_PAGO', now()) RETURNING id
                """, Long.class, torneoId, jugador);
        entityManager.flush();
        entityManager.clear();
        inscripcionService.pagar(jugador, insId);
        return torneoId;
    }
}
