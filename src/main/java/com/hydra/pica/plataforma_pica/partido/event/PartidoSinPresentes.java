package com.hydra.pica.plataforma_pica.partido.event;

/**
 * Lo publica {@code AusenciaService} cuando al vencer la ventana de check-in no se presentó ninguno de los dos
 * (HU-309): el partido quedó sin fecha o WALKOVER sin ganador, según el criterio del torneo. Lo escuchan el aviso a
 * los capitanes y al organizador, y el tiempo real (HU-305).
 */
public record PartidoSinPresentes(Long torneoId, Long partidoId) {
}
