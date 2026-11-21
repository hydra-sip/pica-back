package com.hydra.pica.plataforma_pica.billetera.service;

import com.hydra.pica.plataforma_pica.billetera.domain.Billetera;
import com.hydra.pica.plataforma_pica.billetera.repository.BilleteraRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;

/**
 * Helpers compartidos para obtener o inicializar la billetera de un usuario dentro del paquete de servicio.
 */
final class Billeteras {

    private Billeteras() {
    }

    static Billetera obtenerOCrear(
            BilleteraRepository billeteraRepository,
            UsuarioRepository usuarioRepository,
            Long usuarioId) {
        return billeteraRepository.findByUsuarioId(usuarioId).orElseGet(() ->
                billeteraRepository.save(Billetera.nuevaPara(usuarioRepository.getReferenceById(usuarioId))));
    }

    static Billetera obtenerOCrearBloqueada(
            BilleteraRepository billeteraRepository,
            UsuarioRepository usuarioRepository,
            Long usuarioId) {
        return billeteraRepository.findConBloqueoPorUsuarioId(usuarioId).orElseGet(() -> {
            // Respaldo si algún flujo o test persiste el usuario sin pasar por UsuarioService.crear
            Billetera nueva = Billetera.nuevaPara(usuarioRepository.getReferenceById(usuarioId));
            billeteraRepository.saveAndFlush(nueva);
            return billeteraRepository.findConBloqueoPorUsuarioId(usuarioId).orElse(nueva);
        });
    }
}
