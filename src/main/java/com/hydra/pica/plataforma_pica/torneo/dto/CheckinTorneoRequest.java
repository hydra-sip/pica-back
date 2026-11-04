package com.hydra.pica.plataforma_pica.torneo.dto;

/**
 * La ventana de check-in del torneo (HU-308): abre {@code minAntes} minutos antes de cada cruce y dura
 * {@code duracion} minutos. Lo que compara un valor con el otro lo valida {@code TorneoService}.
 *
 * @param sinPresentes el criterio si no se presenta ninguno de los dos (HU-309), un {@code CriterioSinPresentes};
 *                     texto para responder un valor que no existe como cualquier otro campo inválido. Null deja el
 *                     vigente
 */
public record CheckinTorneoRequest(Integer minAntes, Integer duracion, String sinPresentes) {

    public CheckinTorneoRequest(Integer minAntes, Integer duracion) {
        this(minAntes, duracion, null);
    }
}
