package com.hydra.pica.plataforma_pica.reputacion.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.partido.domain.Lado;
import com.hydra.pica.plataforma_pica.partido.domain.Partido;
import com.hydra.pica.plataforma_pica.partido.dto.ResultadoRequest;
import com.hydra.pica.plataforma_pica.partido.repository.JuegaRepository;
import com.hydra.pica.plataforma_pica.partido.repository.PartidoRepository;
import com.hydra.pica.plataforma_pica.partido.service.ResultadoService;
import com.hydra.pica.plataforma_pica.partido.service.TorneoConGruposBase;
import com.hydra.pica.plataforma_pica.reputacion.dto.HistorialCompetitivo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * El historial competitivo (HU-801, T801-1, PICA-418) en un torneo con fase de grupos: 2 grupos de 4 sorteados. El
 * resto de los casos están en {@code HistorialCompetitivoIntegracionTest}.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@Transactional
class HistorialCompetitivoGruposIntegracionTest extends TorneoConGruposBase {

    @Autowired private HistorialCompetitivoService historialService;
    @Autowired private ResultadoService resultadoService;
    @Autowired private PartidoRepository partidoRepository;
    @Autowired private JuegaRepository juegaRepository;

    @BeforeEach
    void sorteado() {
        inscribir(8);
        cerrar();
        bracketService.generar(organizador, torneo);
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void enGruposUnaVictoriaCuentaYUnEmpateNoSuma() {
        List<Partido> grupoA = partidoRepository.deLosGrupos(torneo).stream()
                .filter(p -> p.getGrupo().getLetra().equals("A"))
                .toList();
        Partido empate = grupoA.getFirst();
        Partido victoria = grupoA.stream()
                .filter(p -> jugadores(p).stream().noneMatch(jugadores(empate)::contains))
                .findFirst().orElseThrow();

        jugar(empate, 1, 1);
        jugar(victoria, 2, 0);

        assertThat(historialService.de(jugador(empate, Lado.LOCAL))).isEqualTo(new HistorialCompetitivo(0, 0, 0, 0));
        assertThat(historialService.de(jugador(empate, Lado.VISITANTE)))
                .isEqualTo(new HistorialCompetitivo(0, 0, 0, 0));
        assertThat(historialService.de(jugador(victoria, Lado.LOCAL))).isEqualTo(new HistorialCompetitivo(0, 1, 0, 0));
        assertThat(historialService.de(jugador(victoria, Lado.VISITANTE)))
                .isEqualTo(new HistorialCompetitivo(0, 0, 1, 0));
    }

    // --- helpers --------------------------------------------------------------

    /** Carga el resultado y el rival lo acepta. */
    private void jugar(Partido partido, int local, int visitante) {
        resultadoService.cargar(jugador(partido, Lado.LOCAL), partido.getId(),
                new ResultadoRequest(local, visitante, null, null));
        resultadoService.aceptar(jugador(partido, Lado.VISITANTE), partido.getId());
        entityManager.flush();
        entityManager.clear();
    }

    private List<Long> jugadores(Partido partido) {
        return List.of(jugador(partido, Lado.LOCAL), jugador(partido, Lado.VISITANTE));
    }

    private Long jugador(Partido partido, Lado lado) {
        return juegaRepository.delPartido(partido.getId()).stream()
                .filter(j -> j.getLado() == lado)
                .findFirst().orElseThrow()
                .getInscripcion().getUsuario().getId();
    }
}
