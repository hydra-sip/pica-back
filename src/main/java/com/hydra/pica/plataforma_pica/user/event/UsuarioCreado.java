package com.hydra.pica.plataforma_pica.user.event;

/**
 * Lo publica {@code UsuarioService.crear} dentro de la transacción, una vez guardado el usuario
 * y sus roles. Lleva ids y datos planos, no la entidad, para que un listener que corra después
 * del commit (@TransactionalEventListener) no se encuentre con proxies sin sesión.
 *
 * {@code requiereVerificacion} es true solo en el autoregistro: es la señal para mandar el mail
 * con el link de verificación (PICA-113). Admin y Google nacen con el mail verificado.
 */
public record UsuarioCreado(Long usuarioId, String username, String email, boolean requiereVerificacion) {
}
