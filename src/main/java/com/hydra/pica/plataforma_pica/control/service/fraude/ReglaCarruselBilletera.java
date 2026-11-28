package com.hydra.pica.plataforma_pica.control.service.fraude;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.hydra.pica.plataforma_pica.control.domain.ClaveParametro;
import com.hydra.pica.plataforma_pica.control.domain.ReglaFraude;
import com.hydra.pica.plataforma_pica.control.service.ParametrosPlataforma;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Regla 2: carrusel carga/retiro. Cargas y retiros combinados en la ventana {@code FRAUDE_CARRUSEL_VENTANA_MIN} que
 * llegan a {@code FRAUDE_CARRUSEL_OPERACIONES_MIN}, con al menos una de cada una.
 */
@Component
@Order(2)
@RequiredArgsConstructor
public class ReglaCarruselBilletera implements ReglaDeFraude {

    private final ParametrosPlataforma parametros;
    private final AlertasFraude alertas;
    private final EntityManager entityManager;
    private final Clock clock;

    @Transactional
    public boolean evaluar(Long usuarioId) {
        if (usuarioId == null) {
            return false;
        }
        int minOperaciones = parametros.cantidad(ClaveParametro.FRAUDE_CARRUSEL_OPERACIONES_MIN);
        Duration ventana = parametros.minutos(ClaveParametro.FRAUDE_CARRUSEL_VENTANA_MIN);
        Instant desde = Instant.now(clock).minus(ventana);

        Object[] fila = (Object[]) entityManager.createNativeQuery("""
                SELECT
                    COUNT(*) FILTER (WHERE m.tipo = 'CARGA' AND m.estado = 'CONFIRMADO'),
                    COUNT(*) FILTER (WHERE m.tipo = 'RETIRO' AND m.estado IN ('PENDIENTE', 'CONFIRMADO')),
                    COALESCE(SUM(m.monto) FILTER (WHERE m.tipo = 'CARGA' AND m.estado = 'CONFIRMADO'), 0),
                    COALESCE(SUM(m.monto) FILTER (WHERE m.tipo = 'RETIRO' AND m.estado IN ('PENDIENTE', 'CONFIRMADO')), 0)
                FROM movimiento m
                JOIN billetera b ON b.id = m.billetera_id
                WHERE b.usuario_id = :usuarioId
                  AND m.creado_en >= :desde
                """)
                .setParameter("usuarioId", usuarioId)
                .setParameter("desde", desde)
                .getSingleResult();

        long cargas = ((Number) fila[0]).longValue();
        long retiros = ((Number) fila[1]).longValue();
        long totalOperaciones = cargas + retiros;
        if (cargas < 1 || retiros < 1 || totalOperaciones < minOperaciones) {
            return false;
        }
        if (alertas.pendiente(ReglaFraude.CARRUSEL_CARGA_RETIRO, usuarioId)) {
            return false;
        }

        Map<String, Object> datos = new LinkedHashMap<>();
        datos.put("cargas", cargas);
        datos.put("retiros", retiros);
        datos.put("totalOperaciones", totalOperaciones);
        datos.put("montoCargas", aBigDecimal(fila[2]));
        datos.put("montoRetiros", aBigDecimal(fila[3]));
        datos.put("umbralOperaciones", minOperaciones);
        datos.put("ventanaMinutos", ventana.toMinutes());

        alertas.guardar(alertas.nueva(ReglaFraude.CARRUSEL_CARGA_RETIRO, usuarioId,
                "Carrusel carga/retiro en billetera: " + cargas + " cargas y " + retiros
                        + " retiros en los últimos " + ventana.toMinutes() + " min (umbral: "
                        + minOperaciones + " operaciones)",
                datos));
        return true;
    }

    @Override
    @Transactional
    public int evaluarTodo() {
        int minOps = parametros.cantidad(ClaveParametro.FRAUDE_CARRUSEL_OPERACIONES_MIN);
        Instant desde = Instant.now(clock).minus(parametros.minutos(ClaveParametro.FRAUDE_CARRUSEL_VENTANA_MIN));
        @SuppressWarnings("unchecked")
        List<Number> usuarios = entityManager.createNativeQuery("""
                SELECT b.usuario_id
                FROM movimiento m
                JOIN billetera b ON b.id = m.billetera_id
                WHERE m.creado_en >= :desde
                  AND ((m.tipo = 'CARGA' AND m.estado = 'CONFIRMADO')
                       OR (m.tipo = 'RETIRO' AND m.estado IN ('PENDIENTE', 'CONFIRMADO')))
                GROUP BY b.usuario_id
                HAVING COUNT(*) >= :minOps
                """)
                .setParameter("desde", desde)
                .setParameter("minOps", minOps)
                .getResultList();
        int creadas = 0;
        for (Number usuarioId : usuarios) {
            if (evaluar(usuarioId.longValue())) {
                creadas++;
            }
        }
        return creadas;
    }

    private static BigDecimal aBigDecimal(Object valor) {
        if (valor instanceof BigDecimal bd) {
            return bd;
        }
        return valor == null ? BigDecimal.ZERO : new BigDecimal(valor.toString());
    }
}
