package com.hydra.pica.plataforma_pica.control.service.fraude;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.hydra.pica.plataforma_pica.control.domain.AlertaFraude;
import com.hydra.pica.plataforma_pica.control.domain.ClaveParametro;
import com.hydra.pica.plataforma_pica.control.domain.EstadoAlertaFraude;
import com.hydra.pica.plataforma_pica.control.domain.ReglaFraude;
import com.hydra.pica.plataforma_pica.control.repository.AlertaFraudeRepository;
import com.hydra.pica.plataforma_pica.control.service.ParametrosPlataforma;
import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;
import com.hydra.pica.plataforma_pica.rifa.repository.RifaRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Regla 1: usuario que compra más de {@code FRAUDE_RIFA_NUMEROS_MAX} números en una misma rifa dentro de la ventana
 * {@code FRAUDE_RIFA_VENTANA_MIN}. Una alerta abierta por usuario y rifa.
 */
@Component
@Order(1)
@RequiredArgsConstructor
public class ReglaCompraMasivaRifa implements ReglaDeFraude {

    private final ParametrosPlataforma parametros;
    private final AlertaFraudeRepository alertaFraudeRepository;
    private final RifaRepository rifaRepository;
    private final AlertasFraude alertas;
    private final EntityManager entityManager;

    @Transactional
    public boolean evaluar(Long rifaId, Long usuarioId) {
        if (rifaId == null || usuarioId == null) {
            return false;
        }
        int maxNumeros = parametros.cantidad(ClaveParametro.FRAUDE_RIFA_NUMEROS_MAX);
        Duration ventana = parametros.minutos(ClaveParametro.FRAUDE_RIFA_VENTANA_MIN);
        Instant desde = Instant.now().minus(ventana);

        long cantidad = ((Number) entityManager.createNativeQuery("""
                SELECT COUNT(*)
                FROM numero n
                JOIN ticket t ON t.id = n.ticket_id
                WHERE n.rifa_id = :rifaId
                  AND t.comprador_id = :usuarioId
                  AND n.estado = 'VENDIDO'
                  AND t.creado_en >= :desde
                """)
                .setParameter("rifaId", rifaId)
                .setParameter("usuarioId", usuarioId)
                .setParameter("desde", desde)
                .getSingleResult()).longValue();

        if (cantidad <= maxNumeros) {
            return false;
        }
        if (alertaFraudeRepository.existsByReglaAndUsuarioIdAndRifaIdAndEstado(
                ReglaFraude.COMPRA_MASIVA_RIFA, usuarioId, rifaId, EstadoAlertaFraude.PENDIENTE)) {
            return false;
        }

        Rifa rifa = rifaRepository.findById(rifaId).orElse(null);
        if (rifa == null) {
            return false;
        }

        Map<String, Object> datos = new LinkedHashMap<>();
        datos.put("numerosComprados", cantidad);
        datos.put("umbralNumeros", maxNumeros);
        datos.put("ventanaMinutos", ventana.toMinutes());
        datos.put("rifaId", rifaId);
        if (rifa.getTorneo() != null) {
            datos.put("torneoId", rifa.getTorneo().getId());
        }

        AlertaFraude alerta = alertas.nueva(ReglaFraude.COMPRA_MASIVA_RIFA, usuarioId,
                "Compra masiva en la rifa \"" + rifa.getTitulo() + "\": " + cantidad
                        + " números comprados en los últimos " + ventana.toMinutes() + " min (umbral: más de "
                        + maxNumeros + ")",
                datos);
        alerta.setRifa(rifa);
        alerta.setTorneo(rifa.getTorneo());
        alertas.guardar(alerta);
        return true;
    }

    @Override
    @Transactional
    public int evaluarTodo() {
        int maxNumeros = parametros.cantidad(ClaveParametro.FRAUDE_RIFA_NUMEROS_MAX);
        Instant desde = Instant.now().minus(parametros.minutos(ClaveParametro.FRAUDE_RIFA_VENTANA_MIN));
        @SuppressWarnings("unchecked")
        List<Object[]> compras = entityManager.createNativeQuery("""
                SELECT n.rifa_id, t.comprador_id
                FROM numero n
                JOIN ticket t ON t.id = n.ticket_id
                WHERE n.estado = 'VENDIDO'
                  AND t.creado_en >= :desde
                GROUP BY n.rifa_id, t.comprador_id
                HAVING COUNT(*) > :maxNumeros
                """)
                .setParameter("desde", desde)
                .setParameter("maxNumeros", maxNumeros)
                .getResultList();
        int creadas = 0;
        for (Object[] fila : compras) {
            if (evaluar(((Number) fila[0]).longValue(), ((Number) fila[1]).longValue())) {
                creadas++;
            }
        }
        return creadas;
    }
}
