package com.hydra.pica.plataforma_pica.partido.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.hydra.pica.plataforma_pica.EscenarioTorneo;
import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.email.EmailService;
import com.hydra.pica.plataforma_pica.partido.dto.PosicionRanking;
import com.hydra.pica.plataforma_pica.partido.dto.RankingResponse;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * El ranking de pronósticos (HU-307, PICA-297) contra la consulta real: los pronósticos se cargan por SQL con los puntos
 * ya calculados.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import({TestcontainersConfiguration.class, EscenarioTorneo.class})
@Transactional
class RankingPronosticosIntegracionTest {

    @Autowired
    private PronosticoService pronosticoService;

    @Autowired
    private EscenarioTorneo escenario;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @MockitoBean
    private EmailService emailService;

    @Test
    void unUsuarioDadoDeBajaNoOcupaPuesto() {
        Long torneo = torneo("Copa Ranking", "jug_rk_");
        Long partido1 = escenario.partido(torneo, 1, 1);
        Long partido2 = escenario.partido(torneo, 1, 2);
        Long vigente = escenario.usuario("vigente_rk", "PARTICIPANTE").getId();
        Long baja = escenario.usuario("baja_rk", "PARTICIPANTE").getId();
        pronostico(vigente, partido1, 3);
        pronostico(baja, partido1, 3);
        pronostico(baja, partido2, 3);
        jdbc.update("UPDATE usuario SET eliminado_en = now() WHERE id = ?", baja);
        entityManager.flush();
        entityManager.clear();

        RankingResponse ranking = pronosticoService.obtenerRanking(vigente, torneo);

        assertThat(ranking.posiciones()).extracting(PosicionRanking::usuarioId, PosicionRanking::posicion)
                .containsExactly(tuple(vigente, 1));
        assertThat(ranking.miPosicion().posicion()).isEqualTo(1);
    }

    @Test
    void filtraPorTorneoYOrdenaPorPuntos() {
        Long torneo1 = torneo("Copa Uno", "jug_uno_");
        Long torneo2 = torneo("Copa Dos", "jug_dos_");
        Long partido1 = escenario.partido(torneo1, 1, 1);
        Long partido2 = escenario.partido(torneo2, 1, 1);
        Long partido3 = escenario.partido(torneo2, 1, 2);
        Long ana = escenario.usuario("ana_rk", "PARTICIPANTE").getId();
        Long beto = escenario.usuario("beto_rk", "PARTICIPANTE").getId();
        pronostico(ana, partido1, 3);
        pronostico(beto, partido1, 0);
        pronostico(beto, partido2, 3);
        pronostico(beto, partido3, 3);
        entityManager.flush();
        entityManager.clear();

        assertThat(pronosticoService.obtenerRanking(null, torneo1).posiciones())
                .extracting(PosicionRanking::usuarioId, PosicionRanking::puntos)
                .containsExactly(tuple(ana, 3),
                        tuple(beto, 0));
        assertThat(pronosticoService.obtenerRanking(null, null).posiciones())
                .extracting(PosicionRanking::usuarioId, PosicionRanking::puntos)
                .containsExactly(tuple(beto, 6),
                        tuple(ana, 3));
    }

    private Long torneo(String nombre, String prefijo) {
        Long organizador = escenario.usuario("org_" + prefijo, "ORGANIZADOR").getId();
        Long arbitro = escenario.usuario("arb_" + prefijo, "ARBITRO").getId();
        return escenario.individualEnCurso(organizador, arbitro, nombre, prefijo, 4);
    }

    private void pronostico(Long usuario, Long partido, int puntos) {
        jdbc.update("INSERT INTO pronostico (usuario_id, partido_id, eleccion, puntos) VALUES (?, ?, '1', ?)",
                usuario, partido, puntos);
    }
}
