package com.hydra.pica.plataforma_pica.control.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.hydra.pica.plataforma_pica.control.domain.AlertaFraude;
import com.hydra.pica.plataforma_pica.control.domain.ClaveParametro;
import com.hydra.pica.plataforma_pica.control.domain.EstadoAlertaFraude;
import com.hydra.pica.plataforma_pica.control.domain.ReglaFraude;
import com.hydra.pica.plataforma_pica.control.repository.AlertaFraudeRepository;
import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;
import com.hydra.pica.plataforma_pica.rifa.repository.RifaRepository;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Evalúa las reglas configurables de detección de conductas fraudulentas y genera alertas para soporte sin duplicar
 * casos abiertos ({@link EstadoAlertaFraude#PENDIENTE}) (HU-705, PICA-416).
 */
@Service
@RequiredArgsConstructor
public class DeteccionFraudeService {

    private final AlertaFraudeRepository alertaFraudeRepository;
    private final ParametrosPlataforma parametros;
    private final UsuarioRepository usuarioRepository;
    private final TorneoRepository torneoRepository;
    private final RifaRepository rifaRepository;
    private final EntityManager entityManager;

    /**
     * Regla 1: usuario que compra más de {@code FRAUDE_RIFA_NUMEROS_MAX} números en una misma rifa dentro de la
     * ventana {@code FRAUDE_RIFA_VENTANA_MIN}.
     */
    @Transactional
    public boolean evaluarCompraRifa(Long rifaId, Long usuarioId) {
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

        AlertaFraude alerta = new AlertaFraude();
        alerta.setRegla(ReglaFraude.COMPRA_MASIVA_RIFA);
        alerta.setRifa(rifa);
        alerta.setTorneo(rifa.getTorneo());
        alerta.setUsuario(usuarioRepository.getReferenceById(usuarioId));
        alerta.setMotivo("Compra masiva en la rifa \"" + rifa.getTitulo() + "\": " + cantidad
                + " números comprados en los últimos " + ventana.toMinutes() + " min (umbral: más de "
                + maxNumeros + ")");
        alerta.setDatos(datos);
        alertaFraudeRepository.saveAndFlush(alerta);
        return true;
    }

    /**
     * Regla 2: carrusel carga/retiro — múltiples cargas y retiros combinados en la ventana
     * {@code FRAUDE_CARRUSEL_VENTANA_MIN} alcanzando {@code FRAUDE_CARRUSEL_OPERACIONES_MIN}.
     */
    @Transactional
    public boolean evaluarCarruselBilletera(Long usuarioId) {
        if (usuarioId == null) {
            return false;
        }
        int minOperaciones = parametros.cantidad(ClaveParametro.FRAUDE_CARRUSEL_OPERACIONES_MIN);
        Duration ventana = parametros.minutos(ClaveParametro.FRAUDE_CARRUSEL_VENTANA_MIN);
        Instant desde = Instant.now().minus(ventana);

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
        if (alertaFraudeRepository.existsByReglaAndUsuarioIdAndEstado(
                ReglaFraude.CARRUSEL_CARGA_RETIRO, usuarioId, EstadoAlertaFraude.PENDIENTE)) {
            return false;
        }

        BigDecimal montoCargas = aBigDecimal(fila[2]);
        BigDecimal montoRetiros = aBigDecimal(fila[3]);

        Map<String, Object> datos = new LinkedHashMap<>();
        datos.put("cargas", cargas);
        datos.put("retiros", retiros);
        datos.put("totalOperaciones", totalOperaciones);
        datos.put("montoCargas", montoCargas);
        datos.put("montoRetiros", montoRetiros);
        datos.put("umbralOperaciones", minOperaciones);
        datos.put("ventanaMinutos", ventana.toMinutes());

        AlertaFraude alerta = new AlertaFraude();
        alerta.setRegla(ReglaFraude.CARRUSEL_CARGA_RETIRO);
        alerta.setUsuario(usuarioRepository.getReferenceById(usuarioId));
        alerta.setMotivo("Carrusel carga/retiro en billetera: " + cargas + " cargas y " + retiros
                + " retiros en los últimos " + ventana.toMinutes() + " min (umbral: "
                + minOperaciones + " operaciones)");
        alerta.setDatos(datos);
        alertaFraudeRepository.saveAndFlush(alerta);
        return true;
    }

    /**
     * Regla 3: organizador con {@code FRAUDE_CANCELACIONES_MIN} o más cancelaciones de torneos.
     */
    @Transactional
    public boolean evaluarCancelacionesOrganizador(Long torneoId) {
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
        return evaluarCancelacionesPorOrganizador(organizadores.getFirst().longValue());
    }

    @Transactional
    public boolean evaluarCancelacionesPorOrganizador(Long organizadorId) {
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
        if (alertaFraudeRepository.existsByReglaAndUsuarioIdAndEstado(
                ReglaFraude.CANCELACIONES_REITERADAS, organizadorId, EstadoAlertaFraude.PENDIENTE)) {
            return false;
        }

        Long torneoMasRecienteId = torneoIds.getFirst();
        Map<String, Object> datos = new LinkedHashMap<>();
        datos.put("torneosCancelados", torneoIds.size());
        datos.put("umbralCancelaciones", minCancelaciones);
        datos.put("torneoIds", torneoIds);

        AlertaFraude alerta = new AlertaFraude();
        alerta.setRegla(ReglaFraude.CANCELACIONES_REITERADAS);
        alerta.setUsuario(usuarioRepository.getReferenceById(organizadorId));
        alerta.setTorneo(torneoRepository.getReferenceById(torneoMasRecienteId));
        alerta.setMotivo("Cancelaciones reiteradas: el organizador acumula " + torneoIds.size()
                + " torneos cancelados (umbral: " + minCancelaciones + ")");
        alerta.setDatos(datos);
        alertaFraudeRepository.saveAndFlush(alerta);
        return true;
    }

    /**
     * Regla 4: usuario con {@code FRAUDE_OBJECIONES_MIN} o más objeciones de resultados de partidos.
     */
    @Transactional
    public boolean evaluarObjecionesUsuario(Long torneoId, Long partidoId) {
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
        return evaluarObjecionesPorUsuario(usuarioId, torneoEfectivo, partidoId);
    }

    @Transactional
    public boolean evaluarObjecionesPorUsuario(Long usuarioId, Long torneoId, Long partidoId) {
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
        if (alertaFraudeRepository.existsByReglaAndUsuarioIdAndEstado(
                ReglaFraude.OBJECIONES_ABUSIVAS, usuarioId, EstadoAlertaFraude.PENDIENTE)) {
            return false;
        }

        Map<String, Object> datos = new LinkedHashMap<>();
        datos.put("totalObjeciones", totalObjeciones);
        datos.put("objecionesEnTorneo", objecionesEnTorneo);
        datos.put("umbralObjeciones", minObjeciones);
        if (partidoId != null) {
            datos.put("partidoId", partidoId);
        }

        AlertaFraude alerta = new AlertaFraude();
        alerta.setRegla(ReglaFraude.OBJECIONES_ABUSIVAS);
        alerta.setUsuario(usuarioRepository.getReferenceById(usuarioId));
        if (torneoId != null) {
            alerta.setTorneo(torneoRepository.getReferenceById(torneoId));
        }
        alerta.setMotivo("Objeciones abusivas: el usuario acumula " + totalObjeciones
                + " objeciones de partidos (umbral: " + minObjeciones + ")");
        alerta.setDatos(datos);
        alertaFraudeRepository.saveAndFlush(alerta);
        return true;
    }

    /**
     * Regla 5: cuentas bancarias (CBU/CVU/Alias) registradas por {@code FRAUDE_CUENTAS_USUARIOS_MIN} o más usuarios
     * distintos.
     */
    @Transactional
    public boolean evaluarCuentaCompartida(Long usuarioId, String valor) {
        if (usuarioId == null || valor == null || valor.isBlank()) {
            return false;
        }
        String valorLimpio = valor.strip();
        int minUsuarios = parametros.cantidad(ClaveParametro.FRAUDE_CUENTAS_USUARIOS_MIN);

        @SuppressWarnings("unchecked")
        List<Object[]> filas = entityManager.createNativeQuery("""
                SELECT u.id, u.username, c.tipo
                FROM cuenta_bancaria c
                JOIN usuario u ON u.id = c.usuario_id
                WHERE c.eliminado_en IS NULL
                  AND u.eliminado_en IS NULL
                  AND LOWER(c.valor) = LOWER(:valor)
                ORDER BY c.creado_en DESC, c.id DESC
                """)
                .setParameter("valor", valorLimpio)
                .getResultList();

        Map<Long, String> usuarios = new LinkedHashMap<>();
        String tipoCuenta = null;
        for (Object[] fila : filas) {
            Long id = ((Number) fila[0]).longValue();
            String username = (String) fila[1];
            usuarios.putIfAbsent(id, username);
            if (tipoCuenta == null && fila[2] != null) {
                tipoCuenta = (String) fila[2];
            }
        }
        if (usuarios.size() < minUsuarios) {
            return false;
        }
        if (alertaFraudeRepository.existsPendienteCuentaCompartidaPorValor(valorLimpio)
                || alertaFraudeRepository.existsByReglaAndUsuarioIdAndEstado(
                ReglaFraude.CUENTAS_COMPARTIDAS, usuarioId, EstadoAlertaFraude.PENDIENTE)) {
            return false;
        }

        List<Long> usuariosIds = new ArrayList<>(usuarios.keySet());
        List<String> usernames = new ArrayList<>(usuarios.values());
        Map<String, Object> datos = new LinkedHashMap<>();
        datos.put("tipoCuenta", tipoCuenta);
        datos.put("valor", valorLimpio);
        datos.put("cantidadUsuarios", usuarios.size());
        datos.put("umbralUsuarios", minUsuarios);
        datos.put("usuariosIds", usuariosIds);
        datos.put("usernames", usernames);

        AlertaFraude alerta = new AlertaFraude();
        alerta.setRegla(ReglaFraude.CUENTAS_COMPARTIDAS);
        alerta.setUsuario(usuarioRepository.getReferenceById(usuarioId));
        alerta.setMotivo("Cuenta bancaria compartida (" + tipoCuenta + " " + valorLimpio + ") entre "
                + usuarios.size() + " usuarios: " + String.join(", ", usernames));
        alerta.setDatos(datos);
        alertaFraudeRepository.saveAndFlush(alerta);
        return true;
    }

    /**
     * Pasada completa de las 5 reglas sobre la base (ejecutada diariamente por {@link DeteccionFraudeAutomatica}).
     * Idempotente: no duplica alertas en estado {@link EstadoAlertaFraude#PENDIENTE}.
     */
    @Transactional
    public int evaluarTodo() {
        int creadas = 0;

        int maxNumeros = parametros.cantidad(ClaveParametro.FRAUDE_RIFA_NUMEROS_MAX);
        Instant desdeRifa = Instant.now().minus(parametros.minutos(ClaveParametro.FRAUDE_RIFA_VENTANA_MIN));
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
                .setParameter("desde", desdeRifa)
                .setParameter("maxNumeros", maxNumeros)
                .getResultList();
        for (Object[] fila : compras) {
            if (evaluarCompraRifa(((Number) fila[0]).longValue(), ((Number) fila[1]).longValue())) {
                creadas++;
            }
        }

        int minOps = parametros.cantidad(ClaveParametro.FRAUDE_CARRUSEL_OPERACIONES_MIN);
        Instant desdeCarrusel = Instant.now().minus(parametros.minutos(ClaveParametro.FRAUDE_CARRUSEL_VENTANA_MIN));
        @SuppressWarnings("unchecked")
        List<Number> usuariosCarrusel = entityManager.createNativeQuery("""
                SELECT b.usuario_id
                FROM movimiento m
                JOIN billetera b ON b.id = m.billetera_id
                WHERE m.creado_en >= :desde
                  AND ((m.tipo = 'CARGA' AND m.estado = 'CONFIRMADO')
                       OR (m.tipo = 'RETIRO' AND m.estado IN ('PENDIENTE', 'CONFIRMADO')))
                GROUP BY b.usuario_id
                HAVING COUNT(*) >= :minOps
                """)
                .setParameter("desde", desdeCarrusel)
                .setParameter("minOps", minOps)
                .getResultList();
        for (Number usuarioId : usuariosCarrusel) {
            if (evaluarCarruselBilletera(usuarioId.longValue())) {
                creadas++;
            }
        }

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
        for (Number organizadorId : organizadores) {
            if (evaluarCancelacionesPorOrganizador(organizadorId.longValue())) {
                creadas++;
            }
        }

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
        for (Object[] fila : objetores) {
            if (fila[0] != null && evaluarObjecionesPorUsuario(
                    ((Number) fila[0]).longValue(),
                    fila[1] == null ? null : ((Number) fila[1]).longValue(),
                    fila[2] == null ? null : ((Number) fila[2]).longValue())) {
                creadas++;
            }
        }

        int minUsuarios = parametros.cantidad(ClaveParametro.FRAUDE_CUENTAS_USUARIOS_MIN);
        @SuppressWarnings("unchecked")
        List<Object[]> compartidas = entityManager.createNativeQuery("""
                SELECT DISTINCT ON (LOWER(c.valor)) c.usuario_id, c.valor
                FROM cuenta_bancaria c
                JOIN usuario u ON u.id = c.usuario_id
                WHERE c.eliminado_en IS NULL
                  AND u.eliminado_en IS NULL
                  AND LOWER(c.valor) IN (
                      SELECT LOWER(c2.valor)
                      FROM cuenta_bancaria c2
                      JOIN usuario u2 ON u2.id = c2.usuario_id
                      WHERE c2.eliminado_en IS NULL AND u2.eliminado_en IS NULL
                      GROUP BY LOWER(c2.valor)
                      HAVING COUNT(DISTINCT c2.usuario_id) >= :minUsuarios
                  )
                ORDER BY LOWER(c.valor), c.creado_en DESC, c.id DESC
                """)
                .setParameter("minUsuarios", minUsuarios)
                .getResultList();
        for (Object[] fila : compartidas) {
            if (evaluarCuentaCompartida(((Number) fila[0]).longValue(), (String) fila[1])) {
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
