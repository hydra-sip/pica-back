package com.hydra.pica.plataforma_pica.torneo.service;

import java.util.List;

import com.hydra.pica.plataforma_pica.torneo.domain.Formato;
import com.hydra.pica.plataforma_pica.torneo.domain.Modalidad;
import com.hydra.pica.plataforma_pica.torneo.domain.TipoParticipante;
import com.hydra.pica.plataforma_pica.torneo.dto.OpcionesTorneo.Disciplina;
import com.hydra.pica.plataforma_pica.torneo.dto.OpcionesTorneo.FormatoOpcion;

/**
 * Disciplinas sugeridas y formatos del asistente (PICA-199). Las disciplinas son una ayuda para completar el
 * formulario, no una lista cerrada: la HU-201 pide cualquier deporte o juego y {@code torneo.disciplina} es texto.
 */
final class CatalogoTorneo {

    static final List<Disciplina> DISCIPLINAS = List.of(
            equipo("Fútbol 5", 5, 10, "5 vs 5 · dos tiempos de 20 minutos"),
            equipo("Fútbol 7", 7, 12, "7 vs 7 · dos tiempos de 25 minutos"),
            equipo("Fútbol 11", 11, 18, "11 vs 11 · dos tiempos de 45 minutos"),
            equipo("Pádel", 2, 2, "Parejas 2 vs 2 · al mejor de 3 sets"),
            equipo("Básquet 3x3", 3, 4, "Media cancha FIBA · 21 puntos o 10 minutos"),
            equipo("Vóley", 6, 12, "6 vs 6 · al mejor de 5 sets"),
            individual("Tenis", Modalidad.PRESENCIAL, "Singles · al mejor de 3 sets"),
            individual("Ajedrez", Modalidad.PRESENCIAL, "Partidas de 10 minutos por jugador"),
            individual("eSports", Modalidad.ONLINE, "1 vs 1 en línea · al mejor de 3 partidas"));

    /** Solo eliminación directa está hecha (es el único valor de {@link Formato}); los otros se muestran como próximos. */
    static final List<FormatoOpcion> FORMATOS = List.of(
            new FormatoOpcion(Formato.ELIMINACION_DIRECTA.name(), "Eliminación directa (llaves)", true),
            new FormatoOpcion("LIGA", "Liga (todos contra todos)", false),
            new FormatoOpcion("GRUPOS_Y_ELIMINACION", "Grupos + eliminación", false));

    private CatalogoTorneo() {
    }

    private static Disciplina equipo(String nombre, int tamanio, int nomina, String reglas) {
        return new Disciplina(nombre, TipoParticipante.EQUIPO, Modalidad.PRESENCIAL, tamanio, nomina, reglas);
    }

    private static Disciplina individual(String nombre, Modalidad modalidad, String reglas) {
        return new Disciplina(nombre, TipoParticipante.INDIVIDUAL, modalidad, null, null, reglas);
    }
}
