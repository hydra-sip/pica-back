package com.hydra.pica.plataforma_pica.rifa.event;

/**
 * Evento emitido al registrarse un reclamo sobre el resultado de una rifa (HU-506, T506-1).
 * Lo escuchan el aviso por email al organizador y la actualización en tiempo real vía WebSocket.
 *
 * @param rifaId     identificador de la rifa reclamada
 * @param reclamoId  identificador del reclamo registrado
 * @param usuarioId  identificador del comprador que presentó el reclamo
 * @param motivo     motivo expresado en el reclamo
 */
public record ReclamoRifaRegistrado(
        Long rifaId,
        Long reclamoId,
        Long usuarioId,
        String motivo
) {
}
