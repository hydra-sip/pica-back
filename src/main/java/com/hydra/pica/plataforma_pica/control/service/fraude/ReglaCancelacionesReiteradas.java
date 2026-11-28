package com.hydra.pica.plataforma_pica.control.service.fraude;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.hydra.pica.plataforma_pica.control.domain.AlertaFraude;
import com.hydra.pica.plataforma_pica.control.domain.ClaveParametro;
import com.hydra.pica.plataforma_pica.control.domain.ReglaFraude;
import com.hydra.pica.plataforma_pica.control.service.ParametrosPlataforma;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Regla 3: organizador con {@code FRAUDE_CANCELACIONES_MIN} o más cancelaciones de torneos resueltas. */
@Component
@Order(3)
@RequiredArgsConstructor
public class ReglaCancelacionesReiteradas implements ReglaDeFraude {

    private final ParametrosPlataforma parametros;
    private final TorneoRepository torneoRepository;
    private final AlertasFraude alertas;
    private final EntityManager entityManager;

    /** Evalúa al organizador del torneo. */
    @Transactional
    public boolean evaluarPorTorneo(Long torneoId) {
        if (torneoId == null) {
            return false;
        }
        @SuppressWarnings("unchecked")
        List<Number> organizadores = entityManager.createNativeQuery(
                "SELECT organizador_id FROM torneo WHERE id = :torneoId")
                .setParameter("torneoId", torneoId)
                .getResultList();
        if (organizadores.isEmpty() || organizadores.getFirst() == null) {
            return false;
        }
        return evaluar(organizadores.getFirst().longValue());
    }

    @Transactional
    public boolean evaluar(Long organizadorId) {
        if (organizadorId == null) {
            return false;
        }
        int minCancelaciones = parametros.cantidad(ClaveParametro.FRAUDE_CANCELACIONES_MIN);

        @SuppressWarnings("unchecked")
        List<Number> filas = entityManager.createNativeQuery("""
                SELECT t.id
                FROM cancelacion c
                JOIN torneo t ON t.id = c.torneo_id
                WHERE t.organizador_id = :organizadorId
                  AND t.eliminado_en IS NULL
                  AND c.estado <> 'EN_REVISION'
                ORDER BY c.creado_en DESC, c.id DESC
                """)
                .setParameter("organizadorId", organizadorId)
                .getResultList();

        List<Long> torneoIds = filas.stream().map(Number::longValue).toList();
        if (torneoIds.size() < minCancelaciones) {
            return false;
        }
        if (alertas.pendiente(ReglaFraude.CANCELACIONES_REITERADAS, organizadorId)) {
            return false;
        }

        Map<String, Object> datos = new LinkedHashMap<>();
        datos.put("torneosCancelados", torneoIds.size());
        datos.put("umbralCancelaciones", minCancelaciones);
        datos.put("torneoIds", torneoIds);

        AlertaFraude alerta = alertas.nueva(ReglaFraude.CANCELACIONES_REITERADAS, organizadorId,
                "Cancelaciones reiteradas: el organizador acumula " + torneoIds.size()
                        + " torneos cancelados (umbral: " + minCancelaciones + ")",
                datos);
        alerta.setTorneo(torneoRepository.getReferenceById(torneoIds.getFirst()));
        alertas.guardar(alerta);
        return true;
    }

    @Override
    @Transactional
    public int evaluarTodo() {
        int minCancelaciones = parametros.cantidad(ClaveParametro.FRAUDE_CANCELACIONES_MIN);
        @SuppressWarnings("unchecked")
        List<Number> organizadores = entityManager.createNativeQuery("""
                SELECT t.organizador_id
                FROM cancelacion c
                JOIN torneo t ON t.id = c.torneo_id
                WHERE t.eliminado_en IS NULL
                  AND c.estado <> 'EN_REVISION'
                GROUP BY t.organizador_id
                HAVING COUNT(*) >= :minCancelaciones
                """)
                .setParameter("minCancelaciones", minCancelaciones)
                .getResultList();
        int creadas = 0;
        for (Number organizadorId : organizadores) {
            if (evaluar(organizadorId.longValue())) {
                creadas++;
            }
        }
        return creadas;
    }
}
