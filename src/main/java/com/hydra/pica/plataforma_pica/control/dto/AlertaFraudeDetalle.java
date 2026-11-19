package com.hydra.pica.plataforma_pica.control.dto;

import java.time.Instant;
import java.util.Map;

import com.hydra.pica.plataforma_pica.control.domain.AlertaFraude;
import com.hydra.pica.plataforma_pica.control.domain.EstadoAlertaFraude;
import com.hydra.pica.plataforma_pica.control.domain.ReglaFraude;
import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;

/**
 * Vista administrativa de una alerta de fraude con el detalle de la regla, recurso involucrado y revisión (HU-705).
 */
public record AlertaFraudeDetalle(
        Long id,
        ReglaFraude regla,
        EstadoAlertaFraude estado,
        String motivo,
        Map<String, Object> datos,
        TorneoAlertaResumen torneo,
        RifaAlertaResumen rifa,
        UsuarioAlertaResumen usuario,
        String nota,
        String revisadaPor,
        Instant revisadaEn,
        Instant creadaEn
) {

    public record TorneoAlertaResumen(
            Long id,
            String nombre
    ) {
    }

    public record RifaAlertaResumen(
            Long id,
            String titulo
    ) {
    }

    public record UsuarioAlertaResumen(
            Long id,
            String username,
            String email
    ) {
    }

    public static AlertaFraudeDetalle de(AlertaFraude alerta) {
        Torneo torneo = alerta.getTorneo();
        Rifa rifa = alerta.getRifa();
        Usuario usuario = alerta.getUsuario();
        Usuario revisora = alerta.getRevisadaPor();
        return new AlertaFraudeDetalle(
                alerta.getId(),
                alerta.getRegla(),
                alerta.getEstado(),
                alerta.getMotivo(),
                alerta.getDatos(),
                torneo == null ? null : new TorneoAlertaResumen(torneo.getId(), torneo.getNombre()),
                rifa == null ? null : new RifaAlertaResumen(rifa.getId(), rifa.getTitulo()),
                new UsuarioAlertaResumen(usuario.getId(), usuario.getUsername(), usuario.getEmail()),
                alerta.getNota(),
                revisora == null ? null : revisora.getUsername(),
                alerta.getRevisadaEn(),
                alerta.getCreadaEn()
        );
    }
}
