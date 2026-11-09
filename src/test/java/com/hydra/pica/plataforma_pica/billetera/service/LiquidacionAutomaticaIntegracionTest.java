package com.hydra.pica.plataforma_pica.billetera.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.billetera.repository.CustodiaRepository;
import com.hydra.pica.plataforma_pica.partido.event.ResultadoConfirmado;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * La liquidación sin que nadie la pida (HU-606): al confirmarse la final y en la revisión periódica. Cada torneo se
 * liquida en su propia transacción, así que acá los datos se guardan de verdad (sin rollback) y se borran al terminar,
 * para no aparecer en los listados de otros tests. En los tests la tarea está apagada y se llama a mano.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
class LiquidacionAutomaticaIntegracionTest {

    @Autowired private LiquidacionService liquidacionService;
    @Autowired private LiquidacionAutomatica liquidacionAutomatica;
    @Autowired private CustodiaRepository custodiaRepository;
    @Autowired private PlatformTransactionManager transacciones;
    @Autowired private JdbcTemplate jdbc;

    private final List<long[]> creados = new ArrayList<>();

    /** Torneo, organizador y persona. La auditoría de la liberación queda: es de solo agregar. */
    @AfterEach
    void borrar() {
        for (long[] creado : creados) {
            jdbc.update("DELETE FROM custodia WHERE torneo_id = ?", creado[0]);
            jdbc.update("DELETE FROM torneo WHERE id = ?", creado[0]);
            jdbc.update("DELETE FROM usuario WHERE id = ?", creado[1]);
            jdbc.update("DELETE FROM persona WHERE id = ?", creado[2]);
        }
    }

    @Test
    void alConfirmarseLaFinalSeLiquida() {
        long torneo = torneoFinalizado();
        LiquidacionAutomatica encendida = new LiquidacionAutomatica(liquidacionService, custodiaRepository,
                transacciones, true);

        encendida.alConfirmarResultado(new ResultadoConfirmado(torneo, null, null, true));

        assertThat(estadoDeLaCustodia(torneo)).isEqualTo("LIBERADA");
    }

    @Test
    void unResultadoQueNoEsLaFinalNoLiquida() {
        long torneo = torneoFinalizado();
        LiquidacionAutomatica encendida = new LiquidacionAutomatica(liquidacionService, custodiaRepository,
                transacciones, true);

        encendida.alConfirmarResultado(new ResultadoConfirmado(torneo, null, null, false));

        assertThat(custodiaRepository.findByTorneoId(torneo)).isEmpty();
    }

    @Test
    void laRevisionLiquidaLosQueQuedaronSinLiquidarYUnaSegundaVueltaNoHaceNada() {
        long torneo = torneoFinalizado();

        assertThat(liquidacionAutomatica.liquidarPendientes()).isGreaterThanOrEqualTo(1);
        assertThat(estadoDeLaCustodia(torneo)).isEqualTo("LIBERADA");
        assertThat(custodiaRepository.torneosFinalizadosSinLiquidar()).doesNotContain(torneo);
        assertThat(liquidacionAutomatica.liquidarPendientes()).isZero();
    }

    private String estadoDeLaCustodia(long torneo) {
        return jdbc.queryForObject("SELECT estado FROM custodia WHERE torneo_id = ?", String.class, torneo);
    }

    private long torneoFinalizado() {
        String nombre = "liq_auto_" + UUID.randomUUID().toString().substring(0, 8);
        long persona = jdbc.queryForObject(
                "INSERT INTO persona (nombres, apellidos) VALUES (?, 'Test') RETURNING id", Long.class, nombre);
        long organizador = jdbc.queryForObject("""
                INSERT INTO usuario (username, email, estado, email_verificado, persona_id)
                VALUES (?, ?, 'ACTIVO', TRUE, ?) RETURNING id
                """, Long.class, nombre, nombre + "@example.com", persona);
        long torneo = jdbc.queryForObject("""
                INSERT INTO torneo (organizador_id, nombre, reglamento_url, disciplina, formato, modalidad,
                                    tipo_participante, cupo_maximo, cupo_minimo, valor_inscripcion,
                                    pct_gastos_administrativos, cierre_inscripcion_en, fecha_inicio, estado)
                VALUES (?, 'Torneo que se liquida solo', 'https://r', 'Ajedrez', 'ELIMINACION_DIRECTA', 'ONLINE',
                        'INDIVIDUAL', 8, 2, 1000, 10, now() - interval '7 days', now() - interval '1 day', 'FINALIZADO')
                RETURNING id
                """, Long.class, organizador);
        creados.add(new long[] {torneo, organizador, persona});
        return torneo;
    }
}
