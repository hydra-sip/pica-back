package com.hydra.pica.plataforma_pica.partido.dto;

import java.util.List;
import com.hydra.pica.plataforma_pica.partido.domain.TablaGrupo;
import com.hydra.pica.plataforma_pica.torneo.domain.ReglasGrupos;

/**
 * La fase de grupos de un torneo, pública (CR-02, PICA-399): las reglas, y por grupo su tabla, sus partidos y los
 * desempates. Sin sortear, {@code grupos} vacío.
 *
 * @param desempate el orden de los criterios, como se selló: {@code puntos,entre-si,ganados,partido}
 */
public record GruposTorneo(Long torneoId, ReglasGrupos reglas, String desempate, List<Grupo> grupos) {

    /**
     * @param terminado              si se jugaron todos los partidos del grupo
     * @param partidos               los de todos contra todos, por fecha
     * @param desempate              los del partido de desempate, si hubo
     * @param desempatesPendientes   los empatados que tocan un puesto que clasifica, cuando el grupo terminó; se
     *                               definen con un partido de desempate
     */
    public record Grupo(String letra, boolean terminado, List<Fila> tabla, List<PartidoBracket> partidos,
                        List<PartidoBracket> desempate, List<List<Long>> desempatesPendientes) {
    }

    /**
     * @param posicion los que comparten puesto tienen la misma
     * @param criterio con qué se definió el puesto; EMPATE si lo comparte
     */
    public record Fila(Long inscripcionId, String nombre, int posicion, int jugados, int ganados, int empatados,
                       int perdidos, int aFavor, int enContra, int diferencia, int puntos,
                       TablaGrupo.Criterio criterio, boolean clasifica) {
    }
}
