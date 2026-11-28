package com.hydra.pica.plataforma_pica.control.service.fraude;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.hydra.pica.plataforma_pica.control.domain.ClaveParametro;
import com.hydra.pica.plataforma_pica.control.domain.ReglaFraude;
import com.hydra.pica.plataforma_pica.control.repository.AlertaFraudeRepository;
import com.hydra.pica.plataforma_pica.control.service.ParametrosPlataforma;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Regla 5: una cuenta bancaria (CBU, CVU o alias) registrada por {@code FRAUDE_CUENTAS_USUARIOS_MIN} o más usuarios
 * distintos. Una alerta abierta por cuenta y por usuario.
 */
@Component
@Order(5)
@RequiredArgsConstructor
public class ReglaCuentasCompartidas implements ReglaDeFraude {

    private final ParametrosPlataforma parametros;
    private final AlertaFraudeRepository alertaFraudeRepository;
    private final AlertasFraude alertas;
    private final EntityManager entityManager;

    @Transactional
    public boolean evaluar(Long usuarioId, String valor) {
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
            usuarios.putIfAbsent(((Number) fila[0]).longValue(), (String) fila[1]);
            if (tipoCuenta == null && fila[2] != null) {
                tipoCuenta = (String) fila[2];
            }
        }
        if (usuarios.size() < minUsuarios) {
            return false;
        }
        if (alertaFraudeRepository.existsPendienteCuentaCompartidaPorValor(valorLimpio)
                || alertas.pendiente(ReglaFraude.CUENTAS_COMPARTIDAS, usuarioId)) {
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

        alertas.guardar(alertas.nueva(ReglaFraude.CUENTAS_COMPARTIDAS, usuarioId,
                "Cuenta bancaria compartida (" + tipoCuenta + " " + valorLimpio + ") entre "
                        + usuarios.size() + " usuarios: " + String.join(", ", usernames),
                datos));
        return true;
    }

    @Override
    @Transactional
    public int evaluarTodo() {
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
        int creadas = 0;
        for (Object[] fila : compartidas) {
            if (evaluar(((Number) fila[0]).longValue(), (String) fila[1])) {
                creadas++;
            }
        }
        return creadas;
    }
}
