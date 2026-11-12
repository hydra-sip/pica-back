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
 * @param respondidoEn cuándo aceptó o rechazó; null si está PENDIENTE
 * @param honorario lo que el organizador le depositó en la custodia del torneo (HU-604); null si no hay nada
 */
public record MiDesignacion(
        Long torneoId,
        String torneo,
        String slug,
        String disciplina,
        EstadoTorneo estadoTorneo,
        Instant fechaInicio,
        Instant fechaFin,
        String organizador,
        EstadoDesignacion estado,
        Set<PermisoArbitro> permisos,
        Instant designadoEn,
        Instant respondidoEn,
        String motivoRechazo,
        HonorarioDesignacion honorario) {

    public static MiDesignacion de(ArbitroDesignado designacion) {
        return de(designacion, null);
    }

    public static MiDesignacion de(ArbitroDesignado designacion, HonorarioDesignacion honorario) {
        Torneo torneo = designacion.getTorneo();
        Persona organizador = torneo.getOrganizador().getPersona();
        return new MiDesignacion(torneo.getId(), torneo.getNombre(), torneo.getSlug(), torneo.getDisciplina(),
                torneo.getEstado(), torneo.getFechaInicio(), torneo.getFechaFin(),
                organizador.getNombres() + " " + organizador.getApellidos(), designacion.getEstado(),
                designacion.getPermisos(), designacion.getDesignadoEn(), designacion.getRespondidoEn(),
                designacion.getMotivoRechazo(), honorario);
    }
}
