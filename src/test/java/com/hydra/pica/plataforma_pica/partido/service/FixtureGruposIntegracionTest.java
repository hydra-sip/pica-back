package com.hydra.pica.plataforma_pica.partido.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.partido.domain.EstadoPartido;
import com.hydra.pica.plataforma_pica.partido.domain.FasePartido;
import com.hydra.pica.plataforma_pica.partido.domain.Juega;
import com.hydra.pica.plataforma_pica.partido.domain.Lado;
import com.hydra.pica.plataforma_pica.partido.domain.Partido;
import com.hydra.pica.plataforma_pica.partido.dto.BracketTorneo;
import com.hydra.pica.plataforma_pica.partido.dto.ResultadoRequest;
import com.hydra.pica.plataforma_pica.partido.event.ResultadoConfirmado;
import com.hydra.pica.plataforma_pica.partido.repository.JuegaRepository;
import com.hydra.pica.plataforma_pica.partido.repository.PartidoRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Formato;
import com.hydra.pica.plataforma_pica.torneo.domain.Modalidad;
import com.hydra.pica.plataforma_pica.torneo.domain.TipoParticipante;
import com.hydra.pica.plataforma_pica.torneo.dto.InformacionTorneoRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;

/** El fixture todos contra todos y los resultados de la fase de grupos (CR-02-4, PICA-398). */
@SpringBootTest
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@Transactional
@RecordApplicationEvents
class FixtureGruposIntegracionTest extends TorneoConGruposBase {

    @Autowired private PartidoRepository partidoRepository;
    @Autowired private JuegaRepository juegaRepository;
    @Autowired private ResultadoService resultadoService;
    @Autowired private ApplicationEvents eventos;
    @Autowired private AusenciaService ausenciaService;

    private List<Long> inscriptos;

    @BeforeEach
    void sorteado() {
        inscriptos = inscribir(8);
        cerrar();
        bracketService.generar(organizador, torneo);
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void cadaGrupoDeCuatroTieneSeisPartidosYCadaUnoJuegaTres() {
        List<Partido> deGrupos = partidoRepository.deLosGrupos(torneo);

        assertThat(deGrupos).hasSize(12).allSatisfy(p -> {
            assertThat(p.getFase()).isEqualTo(FasePartido.GRUPOS);
            assertThat(p.getArbitro().getId()).isEqualTo(arbitro);
        });
        assertThat(deGrupos.stream().map(p -> p.getGrupo().getLetra()).distinct()).containsExactly("A", "B");
        for (Long inscripcion : inscriptos) {
            long jugados = deGrupos.stream()
                    .filter(p -> juegaRepository.delPartido(p.getId()).stream()
                            .anyMatch(j -> j.getId().getInscripcionId().equals(inscripcion)))
                    .count();
            assertThat(jugados).as("partidos de %d", inscripcion).isEqualTo(3);
        }
    }

    @Test
    void laLlaveQuedaVaciaParaLosQuePasen() {
        BracketTorneo bracket = bracketService.ver(torneo);

        // 2 grupos y pasan 2: semifinal y final, sin lados todavía
        assertThat(bracket.rondas()).extracting(r -> r.nombre()).containsExactly("Semifinal", "Final");
        assertThat(bracket.rondas().get(0).partidos()).hasSize(2)
                .allSatisfy(p -> assertThat(p.local()).isNull());
    }

    @Test
    void enGruposSeCargaUnEmpateSinPenalesYQuedaSinGanador() {
        Partido partido = partidoRepository.deLosGrupos(torneo).getFirst();

        resultadoService.cargar(capitan(partido, Lado.LOCAL), partido.getId(), new ResultadoRequest(1, 1, null, null));
        resultadoService.aceptar(capitan(partido, Lado.VISITANTE), partido.getId());
        entityManager.flush();
        entityManager.clear();

        assertThat(partidoRepository.findById(partido.getId()).orElseThrow().getEstado())
                .isEqualTo(EstadoPartido.CONFIRMADO);
        assertThat(juegaRepository.delPartido(partido.getId())).extracting(Juega::getEsGanador)
                .containsOnly(false);
        assertThat(eventos.stream(ResultadoConfirmado.class))
                .anySatisfy(e -> {
                    assertThat(e.partidoId()).isEqualTo(partido.getId());
                    assertThat(e.ganadorId()).isNull();
                    assertThat(e.torneoFinalizado()).isFalse();
                });
    }

    @Test
    void enGruposUnaVictoriaNoAvanzaANingunLado() {
        Partido partido = partidoRepository.deLosGrupos(torneo).getFirst();

        resultadoService.cargar(capitan(partido, Lado.LOCAL), partido.getId(), new ResultadoRequest(2, 0, null, null));
        resultadoService.aceptar(capitan(partido, Lado.VISITANTE), partido.getId());
        entityManager.flush();

        assertThat(bracketService.ver(torneo).rondas().get(0).partidos())
                .allSatisfy(p -> assertThat(p.local()).isNull());
        assertThat(jdbc.queryForObject("SELECT estado FROM torneo WHERE id = ?", String.class, torneo))
                .isEqualTo("EN_CURSO");
    }

    @Test
    void enGruposNoHayPenales() {
        Partido partido = partidoRepository.deLosGrupos(torneo).getFirst();

        assertThatThrownBy(() -> resultadoService.cargar(capitan(partido, Lado.LOCAL), partido.getId(),
                new ResultadoRequest(1, 1, 4, 3)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCodigo()).isEqualTo(CodigoError.VALIDACION);
                    assertThat(e.getPropiedades().get("errores").toString()).contains("penalesLocal");
                });
    }

    @Test
    void elPartidoDeGruposSeNombraConElGrupoYLaFecha() {
        Partido partido = partidoRepository.deLosGrupos(torneo).getFirst();

        assertThat(Partidos.nombreRonda(partido, 2)).isEqualTo("Grupo A · Fecha 1");
    }

    @Test
    void sinArbitrosQueHayanAceptadoNoEmpieza() {
        // otro torneo igual, sin árbitros designados
        Long otro = torneoService.crear(organizador, new InformacionTorneoRequest("Otra copa", "Ajedrez", null,
                Formato.GRUPOS_Y_ELIMINACION, null, Modalidad.ONLINE, TipoParticipante.INDIVIDUAL, null, null,
                EN_DOS_SEMANAS, null, EN_UNA_SEMANA, "Reglas", 8, 6, null, null, Map.of())).id();
        Long anterior = torneo;
        torneo = otro;
        estado(EstadoTorneo.INSCRIPCION_ABIERTA);
        inscribir(8);
        cerrar();
        torneo = anterior;

        assertThatThrownBy(() -> bracketService.generar(organizador, otro))
                .extracting("codigo").isEqualTo(CodigoError.SIN_ARBITROS);
    }

    @Test
    void enGruposElQueSePresentaGanaPorAusenciaYNadieAvanza() {
        Partido partido = vencido("DESCALIFICAR_AMBOS");
        jdbc.update("UPDATE juega SET checkin_en = now() - interval '50 minutes' WHERE partido_id = ? AND lado = 'LOCAL'",
                partido.getId());

        assertThat(ausenciaService.declararSiVencio(partido.getId(), Instant.now())).isTrue();
        entityManager.flush();
        entityManager.clear();

        assertThat(partidoRepository.findById(partido.getId()).orElseThrow().getEstado())
                .isEqualTo(EstadoPartido.WALKOVER);
        assertThat(juegaRepository.delPartido(partido.getId())).anySatisfy(j -> {
            assertThat(j.getLado()).isEqualTo(Lado.LOCAL);
            assertThat(j.getEsGanador()).isTrue();
        });
    }

    @Test
    void enGruposSinPresentesYDescalificandoANingunoSuma() {
        Partido partido = vencido("DESCALIFICAR_AMBOS");

        assertThat(ausenciaService.declararSiVencio(partido.getId(), Instant.now())).isTrue();
        entityManager.flush();
        entityManager.clear();

        assertThat(partidoRepository.findById(partido.getId()).orElseThrow().getEstado())
                .isEqualTo(EstadoPartido.WALKOVER);
        assertThat(juegaRepository.delPartido(partido.getId())).extracting(Juega::getEsGanador).containsOnly(false);
    }

    /** El primer partido de grupos con la ventana de check-in ya cerrada. */
    private Partido vencido(String sinPresentes) {
        jdbc.update("""
                UPDATE torneo SET checkin_min_antes = 30, checkin_duracion_min = 20, checkin_sin_presentes = ?
                WHERE id = ?""", sinPresentes, torneo);
        Partido partido = partidoRepository.deLosGrupos(torneo).getFirst();
        jdbc.update("UPDATE partido SET fecha_programada = now() - interval '1 hour' WHERE id = ?", partido.getId());
        entityManager.clear();
        return partidoRepository.findById(partido.getId()).orElseThrow();
    }

    /** El usuario que juega ese lado (torneo individual: el inscripto es su propio capitán). */
    private Long capitan(Partido partido, Lado lado) {
        return juegaRepository.delPartido(partido.getId()).stream()
                .filter(j -> j.getLado() == lado)
                .findFirst().orElseThrow()
                .getInscripcion().getUsuario().getId();
    }
}
