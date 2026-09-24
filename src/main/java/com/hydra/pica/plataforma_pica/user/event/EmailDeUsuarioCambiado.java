package com.hydra.pica.plataforma_pica.user.event;

/**
 * Lo publica {@code UsuarioEdicionService} cuando un admin cambia el email de un usuario. El usuario
 * queda con {@code emailVerificado = false}: la señal para mandarle el mail con el link de
 * verificación al email nuevo (PICA-113), como en el autoregistro.
 */
public record EmailDeUsuarioCambiado(Long usuarioId, String email) {
}
