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

/**
 * Regla 4: usuario con {@code FRAUDE_OBJECIONES_MIN} o más objeciones de resultados. En un equipo cuenta para el
 * capitán.
 */
@Component
@Order(4)
@RequiredArgsConstructor
public class ReglaObjecionesAbusivas implements ReglaDeFraude {

    private final ParametrosPlataforma parametros;
    private final TorneoRepository torneoRepository;
    private final AlertasFraude alertas;
    private final EntityManager entityManager;

    /** Evalúa a quien objetó el partido; sin {@code torneoId}, usa el torneo del partido. */
    @Transactional
    public boolean evaluarPorPartido(Long torneoId, Long partidoId) {
        if (partidoId == null) {
            return false;
        }
        @SuppressWarnings("unchecked")
        List<Object[]> filas = entityManager.createNativeQuery("""
                SELECT COALESCE(i.usuario_id, e.capitan_id), p.torneo_id
                FROM objecion o
                JOIN partido p ON p.id = o.partido_id
                JOIN inscripcion i ON i.id = o.inscripcion_id
                LEFT JOIN equipo e ON e.id = i.equipo_id
                WHERE o.partido_id = :partidoId
                """)
                .setParameter("partidoId", partidoId)
                .getResultList();
        if (filas.isEmpty() || filas.getFirst()[0] == null) {
            return false;
        }
        Long usuarioId = ((Number) filas.getFirst()[0]).longValue();
        Long torneoEfectivo = torneoId != null ? torneoId
                : (filas.getFirst()[1] == null ? null : ((Number) filas.getFirst()[1]).longValue());
        return evaluar(usuarioId, torneoEfectivo, partidoId);
    }

    @Transactional
    public boolean evaluar(Long usuarioId, Long torneoId, Long partidoId) {
        if (usuarioId == null) {
            return false;
        }
        int minObjeciones = parametros.cantidad(ClaveParametro.FRAUDE_OBJECIONES_MIN);

        Object[] fila = (Object[]) entityManager.createNativeQuery("""
                SELECT
                    COUNT(*),
                    COUNT(*) FILTER (WHERE p.torneo_id = :torneoId)
                FROM objecion o
                JOIN partido p ON p.id = o.partido_id
                JOIN inscripcion i ON i.id = o.inscripcion_id
                LEFT JOIN equipo e ON e.id = i.equipo_id
                WHERE COALESCE(i.usuario_id, e.capitan_id) = :usuarioId
                """)
                .setParameter("usuarioId", usuarioId)
                .setParameter("torneoId", torneoId == null ? -1L : torneoId)
                .getSingleResult();

        long totalObjeciones = ((Number) fila[0]).longValue();
        long objecionesEnTorneo = ((Number) fila[1]).longValue();
        if (totalObjeciones < minObjeciones) {
            return false;
        }
        if (alertas.pendiente(ReglaFraude.OBJECIONES_ABUSIVAS, usuarioId)) {
            return false;
        }

        Map<String, Object> datos = new LinkedHashMap<>();
        datos.put("totalObjeciones", totalObjeciones);
        datos.put("objecionesEnTorneo", objecionesEnTorneo);
        datos.put("umbralObjeciones", minObjeciones);
        if (partidoId != null) {
            datos.put("partidoId", partidoId);
        }

        AlertaFraude alerta = alertas.nueva(ReglaFraude.OBJECIONES_ABUSIVAS, usuarioId,
                "Objeciones abusivas: el usuario acumula " + totalObjeciones
                        + " objeciones de partidos (umbral: " + minObjeciones + ")",
                datos);
        if (torneoId != null) {
            alerta.setTorneo(torneoRepository.getReferenceById(torneoId));
        }
        alertas.guardar(alerta);
        return true;
    }

    @Override
    @Transactional
    public int evaluarTodo() {
        int minObjeciones = parametros.cantidad(ClaveParametro.FRAUDE_OBJECIONES_MIN);
        @SuppressWarnings("unchecked")
        List<Object[]> objetores = entityManager.createNativeQuery("""
                SELECT COALESCE(i.usuario_id, e.capitan_id), MAX(p.torneo_id), MAX(o.partido_id)
                FROM objecion o
                JOIN partido p ON p.id = o.partido_id
                JOIN inscripcion i ON i.id = o.inscripcion_id
                LEFT JOIN equipo e ON e.id = i.equipo_id
                GROUP BY COALESCE(i.usuario_id, e.capitan_id)
                HAVING COUNT(*) >= :minObjeciones
                """)
                .setParameter("minObjeciones", minObjeciones)
                .getResultList();
        int creadas = 0;
        for (Object[] fila : objetores) {
            if (fila[0] != null && evaluar(
                    ((Number) fila[0]).longValue(),
                    fila[1] == null ? null : ((Number) fila[1]).longValue(),
                    fila[2] == null ? null : ((Number) fila[2]).longValue())) {
                creadas++;
            }
        }
        return creadas;
    }
}
