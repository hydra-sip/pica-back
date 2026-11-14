package com.hydra.pica.plataforma_pica.billetera.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Ayudas de los tests de la plata para el balance cero (RNF-CON-02). */
final class BalanceCero {

    private BalanceCero() {
    }

    /**
     * El saldo inicial de un usuario de prueba, como una carga confirmada: sin el movimiento, el control de balance
     * marcaría la billetera.
     */
    static void cargar(JdbcTemplate jdbc, Long usuario, String monto) {
        jdbc.update("INSERT INTO billetera (usuario_id) VALUES (?) ON CONFLICT (usuario_id) DO NOTHING", usuario);
        jdbc.update("""
                WITH b AS (UPDATE billetera SET saldo_disponible = saldo_disponible + ? WHERE usuario_id = ?
                           RETURNING id, saldo_disponible)
                INSERT INTO movimiento (billetera_id, tipo, monto, saldo_resultante, estado, referencia_externa)
                SELECT b.id, 'CARGA', ?, b.saldo_disponible, 'CONFIRMADO', 'prueba-carga-' || gen_random_uuid() FROM b
                """, new BigDecimal(monto), usuario, new BigDecimal(monto));
    }

    /**
     * Las billeteras de esos usuarios, la de la plataforma y las custodias donde pagaron cierran contra sus movimientos.
     */
    static void assertCierra(ControlDeBalance control, JdbcTemplate jdbc, EntityManager entityManager,
                             Collection<Long> usuarios) {
        entityManager.flush();
        var nombrados = new NamedParameterJdbcTemplate(jdbc);
        Map<String, Object> params = Map.of("usuarios", usuarios.isEmpty() ? List.of(-1L) : usuarios);
        List<Long> billeteras = nombrados.queryForList(
                "SELECT id FROM billetera WHERE usuario_id IN (:usuarios) OR usuario_id IS NULL", params, Long.class);
        List<Long> custodias = nombrados.queryForList("""
                SELECT DISTINCT m.custodia_id FROM movimiento m JOIN billetera b ON b.id = m.billetera_id
                WHERE b.usuario_id IN (:usuarios) AND m.custodia_id IS NOT NULL
                """, params, Long.class);

        assertThat(control.billeteras(billeteras)).as("billeteras que no cierran").isEmpty();
        assertThat(control.custodias(custodias)).as("custodias que no cierran").isEmpty();
    }
}
