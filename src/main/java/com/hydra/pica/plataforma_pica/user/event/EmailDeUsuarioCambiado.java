package com.hydra.pica.plataforma_pica.user.event;

/**
 * Lo publica {@code UsuarioEdicionService} cuando un admin cambia el email de un usuario. El usuario
 * queda con {@code emailVerificado = false} y, si no está bloqueado, en PENDIENTE_VERIFICACION.
 * {@code VerificacionEmailService} lo escucha y le manda el link de verificación al email nuevo,
 * como en el autoregistro.
 */
public record EmailDeUsuarioCambiado(Long usuarioId, String email) {
}
