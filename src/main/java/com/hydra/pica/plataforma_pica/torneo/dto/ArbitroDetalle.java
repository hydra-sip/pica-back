package com.hydra.pica.plataforma_pica.torneo.dto;

import java.time.Instant;
import java.util.Set;

import com.hydra.pica.plataforma_pica.torneo.domain.ArbitroDesignado;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoDesignacion;
import com.hydra.pica.plataforma_pica.torneo.domain.PermisoArbitro;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;

/** Un árbitro del torneo como lo ve su organizador en el paso 2 (HU-203). */
public record ArbitroDetalle(
        Long usuarioId,
        String username,
        String nombres,
        String apellidos,
        EstadoDesignacion estado,
        Set<PermisoArbitro> permisos,
        Instant designadoEn) {

    public static ArbitroDetalle de(ArbitroDesignado arbitro) {
        Usuario usuario = arbitro.getUsuario();
        return new ArbitroDetalle(usuario.getId(), usuario.getUsername(), usuario.getPersona().getNombres(),
                usuario.getPersona().getApellidos(), arbitro.getEstado(), arbitro.getPermisos(),
                arbitro.getDesignadoEn());
    }
}
