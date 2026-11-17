package com.hydra.pica.plataforma_pica.reputacion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import java.util.Map;
import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.inscripcion.dto.InscripcionDelTorneo;
import com.hydra.pica.plataforma_pica.partido.service.TorneoConGruposBase;
import com.hydra.pica.plataforma_pica.reputacion.domain.Gravedad;
import com.hydra.pica.plataforma_pica.reputacion.domain.TipoEventoFairPlay;
import com.hydra.pica.plataforma_pica.reputacion.dto.FairPlayDetalle.Movimiento;
import com.hydra.pica.plataforma_pica.reputacion.dto.FairPlayDetalle;
import com.hydra.pica.plataforma_pica.reputacion.dto.SancionDetalle;
import com.hydra.pica.plataforma_pica.reputacion.dto.SancionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Las pruebas de aceptación de HU-802 (T802-1, PICA-421) con los parámetros de V57: arranca en 100, un check-in suma 1
 * hasta 100, una ausencia resta 10 y una sanción 5, 15 o 30. Un torneo con grupos de 8 con los partidos armados.
 * Que se mueve solo con cada evento lo prueban los tests de los eventos (check-in, ausencia y sanción) y
 * {@code FairPlayAutomaticoTest}.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@Transactional
class FairPlayIntegracionTest extends TorneoConGruposBase {

    @Autowired private FairPlayService fairPlayService;
    @Autowired private SancionService sancionService;

    private List<Long> inscripciones;
    private Long inscripcion;
    private Long jugador;
    private Long partido;

    @BeforeEach
    void conPartidos() {
        inscripciones = inscribir(8);
        cerrar();
        bracketService.generar(organizador, torneo);
        entityManager.flush();
        entityManager.clear();
        inscripcion = inscripciones.getFirst();
        jugador = jdbc.queryForObject("SELECT usuario_id FROM inscripcion WHERE id = ?", Long.class, inscripcion);
        partido = jdbc.queryForObject("""
                SELECT p.id FROM partido p JOIN juega j ON j.partido_id = p.id
                WHERE p.torneo_id = ? AND j.inscripcion_id = ? ORDER BY p.id LIMIT 1""", Long.class, torneo, inscripcion);
    }

    @Test
    void unJugadorSinParticipacionesTieneElValorInicial() {
        FairPlayDetalle indice = fairPlayService.ver(jugador);

        assertThat(indice.valor()).isEqualTo(100);
        assertThat(indice.actualizadoEn()).isNull();
        assertThat(indice.historial()).isEmpty();
    }

    @Test
    void elValorInicialEsElDelParametro() {
        parametro("FAIR_PLAY_INICIAL", 80);

        assertThat(fairPlayService.ver(jugador).valor()).isEqualTo(80);
    }

    @Test
    void unCheckinLoMantieneEnElMaximoYLoMejoraSiEstabaMasAbajo() {
        fairPlayService.registrarDeInscripcion(inscripcion, TipoEventoFairPlay.CHECKIN, torneo, partido);
        assertThat(fairPlayService.ver(jugador).valor()).isEqualTo(100);

        fairPlayService.registrarDeInscripcion(inscripcion, TipoEventoFairPlay.AUSENCIA, torneo, partido);
        fairPlayService.registrarDeInscripcion(inscripcion, TipoEventoFairPlay.CHECKIN, torneo, partido);
        entityManager.flush();
        entityManager.clear();

        FairPlayDetalle indice = fairPlayService.ver(jugador);
        assertThat(indice.valor()).isEqualTo(91);
        assertThat(indice.actualizadoEn()).isNotNull();
        assertThat(indice.historial())
                .extracting(Movimiento::tipo, Movimiento::delta, Movimiento::valorResultante, Movimiento::partidoId)
                .containsExactly(
                        tuple(TipoEventoFairPlay.CHECKIN, 1, 91, partido),
                        tuple(TipoEventoFairPlay.AUSENCIA, -10, 90, partido),
                        tuple(TipoEventoFairPlay.CHECKIN, 0, 100, partido));
        assertThat(indice.historial()).allSatisfy(m -> {
            assertThat(m.torneoId()).isEqualTo(torneo);
            assertThat(m.torneo()).isEqualTo("Copa con grupos");
        });
    }

    @Test
    void unaAusenciaSinAvisoLoBaja() {
        fairPlayService.registrarDeInscripcion(inscripcion, TipoEventoFairPlay.AUSENCIA, torneo, partido);

        assertThat(fairPlayService.ver(jugador).valor()).isEqualTo(90);
    }

    @Test
    void unaSancionLoBajaSegunLaGravedadConfigurada() {
        parametro("FAIR_PLAY_SANCION_MEDIA", 20);
        SancionDetalle leve = sancionar(Gravedad.LEVE);
        SancionDetalle media = sancionar(Gravedad.MEDIA);
        SancionDetalle grave = sancionar(Gravedad.GRAVE);

        fairPlayService.registrarSancion(jugador, torneo, leve.id(), Gravedad.LEVE);
        fairPlayService.registrarSancion(jugador, torneo, media.id(), Gravedad.MEDIA);
        fairPlayService.registrarSancion(jugador, torneo, grave.id(), Gravedad.GRAVE);
        entityManager.flush();
        entityManager.clear();

        FairPlayDetalle indice = fairPlayService.ver(jugador);
        assertThat(indice.valor()).isEqualTo(45);
        assertThat(indice.historial())
                .extracting(Movimiento::tipo, Movimiento::delta, Movimiento::sancionId, Movimiento::gravedad)
                .containsExactly(
                        tuple(TipoEventoFairPlay.SANCION, -30, grave.id(), Gravedad.GRAVE),
                        tuple(TipoEventoFairPlay.SANCION, -20, media.id(), Gravedad.MEDIA),
                        tuple(TipoEventoFairPlay.SANCION, -5, leve.id(), Gravedad.LEVE));
    }

    @Test
    void unaSancionMueveElIndiceUnaSolaVez() {
        SancionDetalle sancion = sancionar(Gravedad.GRAVE);

        fairPlayService.registrarSancion(jugador, torneo, sancion.id(), Gravedad.GRAVE);
        fairPlayService.registrarSancion(jugador, torneo, sancion.id(), Gravedad.GRAVE);

        assertThat(fairPlayService.ver(jugador).valor()).isEqualTo(70);
    }

    @Test
    void nuncaBajaDeCero() {
        parametro("FAIR_PLAY_AUSENCIA", 60);

        fairPlayService.registrarDeInscripcion(inscripcion, TipoEventoFairPlay.AUSENCIA, torneo, partido);
        fairPlayService.registrarDeInscripcion(inscripcion, TipoEventoFairPlay.AUSENCIA, torneo, partido);
        entityManager.flush();
        entityManager.clear();

        FairPlayDetalle indice = fairPlayService.ver(jugador);
        assertThat(indice.valor()).isZero();
        assertThat(indice.historial()).extracting(Movimiento::delta).containsExactly(-40, -60);
    }

    @Test
    void enUnEquipoElCheckinYLaAusenciaMuevenElDelCapitan() {
        jdbc.update("UPDATE torneo SET tipo_participante = 'EQUIPO', tamanio_equipo = 2, nomina_maxima = 3 WHERE id = ?",
                torneo);
        Long capitan = usuario("capitan_fair_play", "PARTICIPANTE").getId();
        Long companero = usuario("companero_fair_play", "PARTICIPANTE").getId();
        Long equipo = jdbc.queryForObject("INSERT INTO equipo (capitan_id, nombre) VALUES (?, 'Los Leales') RETURNING id",
                Long.class, capitan);
        jdbc.update("INSERT INTO usuario_equipo (usuario_id, equipo_id) VALUES (?, ?), (?, ?)", capitan, equipo,
                companero, equipo);
        Long deEquipo = jdbc.queryForObject("""
                INSERT INTO inscripcion (torneo_id, equipo_id, estado, reglamento_aceptado_en)
                VALUES (?, ?, 'CONFIRMADA', now()) RETURNING id""", Long.class, torneo, equipo);
        entityManager.clear();

        fairPlayService.registrarDeInscripcion(deEquipo, TipoEventoFairPlay.AUSENCIA, torneo, partido);

        assertThat(fairPlayService.ver(capitan).valor()).isEqualTo(90);
        assertThat(fairPlayService.ver(companero).valor()).isEqualTo(100);
    }

    @Test
    void elOrganizadorVeElIndiceDeCadaInscripto() {
        fairPlayService.registrarDeInscripcion(inscripcion, TipoEventoFairPlay.AUSENCIA, torneo, partido);
        entityManager.flush();
        entityManager.clear();

        List<InscripcionDelTorneo> lista = inscripcionTorneoService
                .listar(organizador, torneo, null, PageRequest.of(0, 20)).getContent();

        assertThat(lista).hasSize(8);
        assertThat(lista).filteredOn(i -> i.id().equals(inscripcion))
                .extracting(InscripcionDelTorneo::fairPlay).containsExactly(90);
        assertThat(lista).filteredOn(i -> !i.id().equals(inscripcion))
                .extracting(InscripcionDelTorneo::fairPlay).containsOnly(100);
    }

    @Test
    void losIndicesDeVariosUsuariosConElInicialParaElQueNuncaSeMovio() {
        Long otro = jdbc.queryForObject("SELECT usuario_id FROM inscripcion WHERE id = ?", Long.class,
                inscripciones.get(1));
        fairPlayService.registrarDeInscripcion(inscripcion, TipoEventoFairPlay.AUSENCIA, torneo, partido);

        assertThat(fairPlayService.indices(List.of(jugador, otro))).isEqualTo(Map.of(jugador, 90, otro, 100));
        assertThat(fairPlayService.indices(List.of())).isEmpty();
    }

    @Test
    void elDeUnUsuarioQueNoExisteEs404() {
        ApiException error = org.assertj.core.api.Assertions.catchThrowableOfType(ApiException.class,
                () -> fairPlayService.ver(999_999L));

        assertThat(error.getStatus().value()).isEqualTo(404);
    }

    private SancionDetalle sancionar(Gravedad gravedad) {
        return sancionService.aplicar(organizador, torneo, new SancionRequest(jugador, null, gravedad, "Conducta"));
    }

    private void parametro(String clave, int valor) {
        jdbc.update("UPDATE parametro_plataforma SET valor = ? WHERE clave = ?", valor, clave);
        entityManager.clear();
    }
}
