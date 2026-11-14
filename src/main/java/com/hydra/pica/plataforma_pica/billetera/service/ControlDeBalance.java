package com.hydra.pica.plataforma_pica.billetera.service;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Balance cero (RNF-CON-02): ni las billeteras ni las custodias pueden tener plata que no explique un movimiento. Cada
 * consulta devuelve solo lo que no cierra; una lista vacía es que está todo bien. Lee la base con JDBC: lo pendiente de
 * la sesión de JPA tiene que estar escrito (flush) antes de llamarla.
 */
@Service
@RequiredArgsConstructor
public class ControlDeBalance {

    /**
     * Lo que dicen los movimientos de cada billetera.
     * <ul>
     *   <li>Disponible: entran las cargas, premios, liquidaciones, honorarios y reintegros confirmados; salen los pagos
     *   con saldo ("pago-", también los revertidos, que vuelven como reintegro) y los retiros, que descuentan desde
     *   que se piden. Los pagos simulados ("sim-") no tocaron la billetera.</li>
     *   <li>Retenido: los pagos con saldo confirmados cuya custodia sigue RETENIDA; al liberarla salen del retenido.</li>
     * </ul>
     */
    private static final String BILLETERAS = """
            SELECT b.id, b.saldo_disponible, x.disponible, b.saldo_retenido, x.retenido
            FROM billetera b
            CROSS JOIN LATERAL (
                SELECT coalesce(sum(CASE
                           WHEN m.estado = 'CONFIRMADO'
                                AND m.tipo IN ('CARGA', 'PREMIO', 'LIQUIDACION', 'HONORARIO', 'REINTEGRO') THEN m.monto
                           WHEN m.referencia_externa LIKE 'pago-%' AND m.estado IN ('CONFIRMADO', 'REVERTIDO') THEN -m.monto
                           WHEN m.tipo = 'RETIRO' AND m.estado IN ('PENDIENTE', 'CONFIRMADO') THEN -m.monto
                           ELSE 0 END), 0) AS disponible,
                       coalesce(sum(m.monto) FILTER (WHERE m.referencia_externa LIKE 'pago-%' AND m.estado = 'CONFIRMADO'
                                                       AND coalesce(c.estado, 'RETENIDA') = 'RETENIDA'), 0) AS retenido
                FROM movimiento m LEFT JOIN custodia c ON c.id = m.custodia_id
                WHERE m.billetera_id = b.id) x
            WHERE (b.saldo_disponible <> x.disponible OR b.saldo_retenido <> x.retenido)
            """;

    /**
     * Lo que entró a cada custodia contra lo que salió de ella: la liquidación (premios, organizador, gastos
     * administrativos, honorarios y el aporte de la rifa) o, si se canceló, los reintegros y los gastos aprobados. Una
     * custodia liberada o reintegrada tiene que dar igual; una retenida no puede haber repartido más de lo que entró (una
     * rifa liquidada guarda ahí su aporte hasta que se liquida el torneo).
     */
    private static final String CUSTODIAS = """
            SELECT c.id, c.torneo_id, c.rifa_id, c.estado, e.monto AS entrado, s.monto AS repartido
            FROM custodia c
            CROSS JOIN LATERAL (
                SELECT coalesce(sum(m.monto), 0) AS monto FROM movimiento m
                WHERE m.custodia_id = c.id AND m.estado = 'CONFIRMADO') e
            CROSS JOIN LATERAL (
                SELECT coalesce(sum(m.monto), 0) AS monto FROM movimiento m
                WHERE m.estado = 'CONFIRMADO' AND m.custodia_id IS NULL
                  AND ((c.torneo_id IS NOT NULL
                        AND (m.referencia_externa LIKE 'liq-t' || c.torneo_id || '-%'
                             OR m.referencia_externa LIKE 'reint-t' || c.torneo_id || '-b%'
                             OR m.referencia_externa = 'canc-t' || c.torneo_id || '-organizador'))
                    OR (c.rifa_id IS NOT NULL
                        AND (m.referencia_externa LIKE 'liq-r' || c.rifa_id || '-%'
                             OR m.referencia_externa LIKE 'reint-r' || c.rifa_id || '-b%')))) s
            WHERE CASE WHEN c.estado = 'RETENIDA' THEN s.monto > e.monto ELSE s.monto <> e.monto END
            """;

    private final NamedParameterJdbcTemplate jdbc;

    /** Una billetera que no cierra: lo que tiene y lo que dicen sus movimientos. */
    public record DiferenciaBilletera(Long billeteraId, BigDecimal disponible, BigDecimal disponibleEsperado,
                                      BigDecimal retenido, BigDecimal retenidoEsperado) {
    }

    /** Una custodia que no cierra: lo que entró y lo que repartió. */
    public record DiferenciaCustodia(Long custodiaId, Long torneoId, Long rifaId, String estado, BigDecimal entrado,
                                     BigDecimal repartido) {
    }

    /** Todas las billeteras que no cierran. */
    public List<DiferenciaBilletera> billeteras() {
        return jdbc.query(BILLETERAS + " ORDER BY b.id", Map.of(), ControlDeBalance::billetera);
    }

    /** Las que no cierran entre esas billeteras. */
    public List<DiferenciaBilletera> billeteras(Collection<Long> billeteraIds) {
        if (billeteraIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query(BILLETERAS + " AND b.id IN (:ids) ORDER BY b.id", Map.of("ids", billeteraIds),
                ControlDeBalance::billetera);
    }

    /** Todas las custodias que no cierran. */
    public List<DiferenciaCustodia> custodias() {
        return jdbc.query(CUSTODIAS + " ORDER BY c.id", Map.of(), ControlDeBalance::custodia);
    }

    /** Las que no cierran entre esas custodias. */
    public List<DiferenciaCustodia> custodias(Collection<Long> custodiaIds) {
        if (custodiaIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query(CUSTODIAS + " AND c.id IN (:ids) ORDER BY c.id", Map.of("ids", custodiaIds),
                ControlDeBalance::custodia);
    }

    private static DiferenciaBilletera billetera(java.sql.ResultSet rs, int fila) throws java.sql.SQLException {
        return new DiferenciaBilletera(rs.getLong(1), rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getBigDecimal(4),
                rs.getBigDecimal(5));
    }

    private static DiferenciaCustodia custodia(java.sql.ResultSet rs, int fila) throws java.sql.SQLException {
        return new DiferenciaCustodia(rs.getLong(1), rs.getObject(2, Long.class), rs.getObject(3, Long.class),
                rs.getString(4), rs.getBigDecimal(5), rs.getBigDecimal(6));
    }
}
