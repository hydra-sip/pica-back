package com.hydra.pica.plataforma_pica.torneo.domain;

/**
 * Cuántas inscripciones confirmadas necesita un torneo para poder jugarse (PICA-447). Lo usan el cierre de la
 * inscripción, que no cierra por debajo, y la generación del bracket y el sorteo de grupos, así nunca discrepan.
 *
 * <ul>
 * <li>Eliminación directa: 2, para armar al menos una final.</li>
 * <li>Grupos y eliminación: cada grupo del cupo máximo ({@link ReglasGrupos#grupos}) necesita al menos uno más que
 * los que pasan. Con el cupo lleno siempre se llega, porque los que pasan son menos que el tamaño del grupo.</li>
 * </ul>
 */
public final class MinimoInscriptos {

    public static final int ELIMINACION_DIRECTA = 2;

    private MinimoInscriptos() {
    }

    public static int de(Torneo torneo) {
        if (torneo.getFormato() != Formato.GRUPOS_Y_ELIMINACION) {
            return ELIMINACION_DIRECTA;
        }
        return de(torneo.getFormato(), torneo.getCupoMaximo(), ReglasGrupos.de(torneo));
    }

    /** El de {@link #de(Torneo)}, o null si al torneo (en BORRADOR) le falta el formato o, con grupos, el cupo. */
    public static Integer siEstaDefinido(Torneo torneo) {
        if (torneo.getFormato() == null
                || torneo.getFormato() == Formato.GRUPOS_Y_ELIMINACION && torneo.getCupoMaximo() == null) {
            return null;
        }
        return de(torneo);
    }

    /** @param reglas las de los grupos; null en eliminación directa */
    public static int de(Formato formato, int cupoMaximo, ReglasGrupos reglas) {
        if (formato != Formato.GRUPOS_Y_ELIMINACION) {
            return ELIMINACION_DIRECTA;
        }
        return reglas.grupos(cupoMaximo) * (reglas.clasificanPorGrupo() + 1);
    }
}
