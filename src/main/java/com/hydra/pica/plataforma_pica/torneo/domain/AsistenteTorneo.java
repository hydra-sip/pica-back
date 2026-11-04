package com.hydra.pica.plataforma_pica.torneo.domain;

/**
 * Los tres pasos del asistente de crear torneo y qué tiene que estar cargado en cada uno: 1 datos generales,
 * 2 cupos y nómina (HU-201), 3 la parte económica (HU-601), que es donde se publica.
 */
public final class AsistenteTorneo {

    private AsistenteTorneo() {
    }

    /**
     * El primer paso que tiene algo sin cargar, para que "Continuar edición" abra ahí; con los dos primeros
     * completos, el 3. Null fuera de BORRADOR.
     */
    public static Integer pasoPendiente(Torneo torneo) {
        if (torneo.getEstado() != EstadoTorneo.BORRADOR) {
            return null;
        }
        if (!datosGeneralesCompletos(torneo)) {
            return 1;
        }
        return cuposCompletos(torneo) ? 3 : 2;
    }

    /** La disciplina y el nombre no se cuentan: sin ellos el borrador no se guarda. */
    public static boolean datosGeneralesCompletos(Torneo t) {
        return t.getModalidad() != null
                && t.getTipoParticipante() != null
                && (t.getModalidad() != Modalidad.PRESENCIAL || t.getSede() != null)
                && t.getFechaInicio() != null
                && t.getCierreInscripcionEn() != null
                && (t.getReglamentoTexto() != null || t.getReglamentoUrl() != null);
    }

    public static boolean cuposCompletos(Torneo t) {
        return t.getCupoMaximo() != null
                && t.getCupoMinimo() != null
                && (t.getTipoParticipante() != TipoParticipante.EQUIPO
                        || (t.getTamanioEquipo() != null && t.getNominaMaxima() != null));
    }
}
