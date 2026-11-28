package com.hydra.pica.plataforma_pica.control.service.fraude;

import java.util.Map;

import com.hydra.pica.plataforma_pica.control.domain.AlertaFraude;
import com.hydra.pica.plataforma_pica.control.domain.EstadoAlertaFraude;
import com.hydra.pica.plataforma_pica.control.domain.ReglaFraude;
import com.hydra.pica.plataforma_pica.control.repository.AlertaFraudeRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Lo que comparten las reglas: saber si ya hay una alerta abierta y crear una nueva. */
@Component
@RequiredArgsConstructor
class AlertasFraude {

    private final AlertaFraudeRepository alertaFraudeRepository;
    private final UsuarioRepository usuarioRepository;

    /** Si el usuario ya tiene una alerta PENDIENTE de esa regla. */
    boolean pendiente(ReglaFraude regla, Long usuarioId) {
        return alertaFraudeRepository.existsByReglaAndUsuarioIdAndEstado(regla, usuarioId,
                EstadoAlertaFraude.PENDIENTE);
    }

    /** Una alerta PENDIENTE sin guardar, para que la regla le sume el torneo o la rifa antes de {@link #guardar}. */
    AlertaFraude nueva(ReglaFraude regla, Long usuarioId, String motivo, Map<String, Object> datos) {
        AlertaFraude alerta = new AlertaFraude();
        alerta.setRegla(regla);
        alerta.setUsuario(usuarioRepository.getReferenceById(usuarioId));
        alerta.setMotivo(motivo);
        alerta.setDatos(datos);
        return alerta;
    }

    void guardar(AlertaFraude alerta) {
        alertaFraudeRepository.saveAndFlush(alerta);
    }
}
