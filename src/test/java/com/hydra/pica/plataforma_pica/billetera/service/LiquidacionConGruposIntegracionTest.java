package com.hydra.pica.plataforma_pica.billetera.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.email.EmailService;
import com.hydra.pica.plataforma_pica.inscripcion.service.PagoInscripcionService;
import com.hydra.pica.plataforma_pica.partido.domain.FasePartido;
import com.hydra.pica.plataforma_pica.partido.domain.Juega;
import com.hydra.pica.plataforma_pica.partido.domain.Lado;
import com.hydra.pica.plataforma_pica.partido.domain.Partido;
import com.hydra.pica.plataforma_pica.partido.dto.ResultadoRequest;
import com.hydra.pica.plataforma_pica.partido.repository.JuegaRepository;
import com.hydra.pica.plataforma_pica.partido.repository.PartidoRepository;
import com.hydra.pica.plataforma_pica.partido.service.ResultadoService;
import com.hydra.pica.plataforma_pica.partido.service.TorneoConGruposBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * Un torneo con grupos de punta a punta (CR-02-6, PICA-400): se inscriben pagando con saldo, se sortean los grupos, se juegan
 * los grupos y la llave, y la liquidación paga a los puestos de la llave; los eliminados en grupos no cobran. Al final,
 * las billeteras y la custodia cierran (RNF-CON-02).
 */
@SpringBootTest(properties = "app.cobro.modo=saldo")
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@Transactional
class LiquidacionConGruposIntegracionTest extends TorneoConGruposBase {

    @Autowired private PagoInscripcionService pagoInscripcionService;
    @Autowired private ResultadoService resultadoService;
    @Autowired private LiquidacionService liquidacionService;
    @Autowired private ControlDeBalance controlDeBalance;
    @Autowired private PartidoRepository partidoRepository;
    @Autowired private JuegaRepository juegaRepository;
    @MockitoBean private EmailService emailService;

    private final List<Long> usuarios = new ArrayList<>();

    @AfterEach
    void elBalanceCierra() {
        BalanceCero.assertCierra(controlDeBalance, jdbc, entityManager, usuarios);
    }

    @Test
    void sorteoGruposLlaveYLiquidacionConLosPuestosDeLaLlave() {
        usuarios.add(organizador);
        jdbc.update("UPDATE torneo SET pct_gastos_administrativos = 10 WHERE id = ?", torneo);
        jdbc.update("INSERT INTO premio (torneo_id, puesto, pct) VALUES (?, 1, 70), (?, 2, 30)", torneo, torneo);
        List<Long> inscripciones = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Long jugador = usuario("pagador_grupos_" + i, "PARTICIPANTE").getId();
            usuarios.add(jugador);
            BalanceCero.cargar(jdbc, jugador, "5000.00");
            Long inscripcion = jdbc.queryForObject("""
                    INSERT INTO inscripcion (torneo_id, usuario_id, estado, reglamento_aceptado_en)
                    VALUES (?, ?, 'PENDIENTE_PAGO', now()) RETURNING id""", Long.class, torneo, jugador);
            entityManager.clear();
            pagoInscripcionService.pagar(jugador, inscripcion);
            inscripciones.add(inscripcion);
        }
        entityManager.flush();
        entityManager.clear();
        // la octava llenó el cupo: la inscripción se cerró sola y fijó la ronda de drand
        assertThat(jdbc.queryForObject("SELECT estado FROM torneo WHERE id = ?", String.class, torneo))
                .isEqualTo("INSCRIPCION_CERRADA");

        bracketService.generar(organizador, torneo);
        entityManager.flush();
        entityManager.clear();
        partidoRepository.deLosGrupos(torneo).stream()
                .filter(p -> p.getFase() == FasePartido.GRUPOS)
                .map(Partido::getId).toList()
                .forEach(this::ganaElDeMenorId);
        for (int ronda = 1; ronda <= 2; ronda++) {
            int r = ronda;
            partidoRepository.delTorneo(torneo).stream().filter(p -> p.getRonda() == r)
                    .map(Partido::getId).toList()
                    .forEach(this::ganaElDeMenorId);
        }
        assertThat(jdbc.queryForObject("SELECT estado FROM torneo WHERE id = ?", String.class, torneo))
                .isEqualTo("FINALIZADO");

        liquidacionService.liquidarTorneo(torneo);
        entityManager.flush();

        // el de menor id gana todo: es el campeón
        Long campeon = inscripciones.getFirst();
        assertThat(jdbc.queryForObject("SELECT posicion FROM inscripcion WHERE id = ?", Integer.class, campeon))
                .isEqualTo(1);
        BigDecimal premios = jdbc.queryForObject("""
                SELECT coalesce(sum(m.monto), 0) FROM movimiento m WHERE m.torneo_id = ? AND m.tipo = 'PREMIO'""",
                BigDecimal.class, torneo);
        // 8 inscripciones de $ 1.000: el pozo de $ 8.000 se reparte entero, 70 % al campeón y 30 % al segundo
        assertThat(premios).isEqualByComparingTo("8000.00");
        assertThat(jdbc.queryForObject("""
                SELECT sum(m.monto) FROM movimiento m WHERE m.inscripcion_id = ? AND m.tipo = 'PREMIO'""",
                BigDecimal.class, campeon)).isEqualByComparingTo("5600.00");
        Long cobraron = jdbc.queryForObject("""
                SELECT count(DISTINCT m.inscripcion_id) FROM movimiento m WHERE m.torneo_id = ? AND m.tipo = 'PREMIO'""",
                Long.class, torneo);
        assertThat(cobraron).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM movimiento m JOIN inscripcion i ON i.id = m.inscripcion_id
                WHERE m.torneo_id = ? AND m.tipo = 'PREMIO' AND i.posicion IS NULL""", Long.class, torneo)).isZero();
    }

    private void ganaElDeMenorId(Long partidoId) {
        List<Juega> lados = juegaRepository.delPartido(partidoId);
        Juega local = lados.stream().filter(j -> j.getLado() == Lado.LOCAL).findFirst().orElseThrow();
        Juega visitante = lados.stream().filter(j -> j.getLado() == Lado.VISITANTE).findFirst().orElseThrow();
        boolean ganaLocal = local.getId().getInscripcionId() < visitante.getId().getInscripcionId();
        resultadoService.cargar(local.getInscripcion().getUsuario().getId(), partidoId,
                ganaLocal ? new ResultadoRequest(1, 0, null, null) : new ResultadoRequest(0, 1, null, null));
        resultadoService.aceptar(visitante.getInscripcion().getUsuario().getId(), partidoId);
        entityManager.flush();
        entityManager.clear();
    }
}
