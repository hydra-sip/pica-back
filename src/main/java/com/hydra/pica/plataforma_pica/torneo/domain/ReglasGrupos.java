package com.hydra.pica.plataforma_pica.torneo.domain;

/**
 * Las reglas de la fase de grupos (CR-02, PICA-396): las elige el organizador en el asistente y quedan selladas al
 * publicar ({@link SelloTorneo}). Solo en {@link Formato#GRUPOS_Y_ELIMINACION}; en eliminación directa no hay.
 *
 * <p>El desempate no se configura: puntos, resultado entre sí, partidos ganados y, si sigue, partido de desempate.
 *
 * @param tamanioGrupo       integrantes por grupo con el cupo lleno, de {@value #TAMANIO_MINIMO} a {@value #TAMANIO_MAXIMO}
 * @param clasificanPorGrupo cuántos pasan a la llave, menos que el tamaño
 * @param puntosVictoria     por partido ganado, de 1 a {@value #PUNTOS_MAXIMOS}
 * @param puntosEmpate       por partido empatado, menos que por ganar; perder da 0
 */
public record ReglasGrupos(Integer tamanioGrupo, Integer clasificanPorGrupo, Integer puntosVictoria,
                           Integer puntosEmpate) {

    public static final int TAMANIO_MINIMO = 3;
    public static final int TAMANIO_MAXIMO = 8;
    public static final int PUNTOS_MAXIMOS = 10;

    /** Lo que propuso Fede: grupos de 4, pasan 2, 3 por ganar y 1 por empatar. Lo que falta se completa con esto. */
    public static final ReglasGrupos POR_DEFECTO = new ReglasGrupos(4, 2, 3, 1);

    /** Lo que no vino, con el valor por defecto. */
    public ReglasGrupos completas() {
        return new ReglasGrupos(
                tamanioGrupo != null ? tamanioGrupo : POR_DEFECTO.tamanioGrupo,
                clasificanPorGrupo != null ? clasificanPorGrupo : POR_DEFECTO.clasificanPorGrupo,
                puntosVictoria != null ? puntosVictoria : POR_DEFECTO.puntosVictoria,
                puntosEmpate != null ? puntosEmpate : POR_DEFECTO.puntosEmpate);
    }

    /** Las del torneo, o null si es de eliminación directa. */
    public static ReglasGrupos de(Torneo torneo) {
        return torneo.getFormato() != Formato.GRUPOS_Y_ELIMINACION ? null
                : new ReglasGrupos(torneo.getTamanioGrupo(), torneo.getClasificanPorGrupo(), torneo.getPuntosVictoria(),
                        torneo.getPuntosEmpate());
    }

    /** Cuántos grupos hay con ese cupo máximo. */
    public int grupos(int cupoMaximo) {
        return cupoMaximo / tamanioGrupo;
    }
}
