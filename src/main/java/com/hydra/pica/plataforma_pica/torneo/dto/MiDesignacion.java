package com.hydra.pica.plataforma_pica.torneo.dto;

import java.time.Instant;
import java.util.Set;

import com.hydra.pica.plataforma_pica.torneo.domain.ArbitroDesignado;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoDesignacion;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.PermisoArbitro;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.user.domain.Persona;

/**
 * Una designación como la ve el árbitro (HU-807): el torneo, quién lo organiza y los permisos que le proponen.
 *
 * @param slug null mientras el torneo está en BORRADOR (todavía no tiene ficha pública)
 */
public record MiDesignacion(
        Long torneoId,
        String torneo,
        String slug,
        EstadoTorneo estadoTorneo,
        Instant fechaInicio,
        String organizador,
        EstadoDesignacion estado,
        Set<PermisoArbitro> permisos,
        Instant designadoEn) {

    public static MiDesignacion de(ArbitroDesignado designacion) {
        Torneo torneo = designacion.getTorneo();
        Persona organizador = torneo.getOrganizador().getPersona();
        return new MiDesignacion(torneo.getId(), torneo.getNombre(), torneo.getSlug(), torneo.getEstado(),
                torneo.getFechaInicio(), organizador.getNombres() + " " + organizador.getApellidos(),
                designacion.getEstado(), designacion.getPermisos(), designacion.getDesignadoEn());
    }
}
