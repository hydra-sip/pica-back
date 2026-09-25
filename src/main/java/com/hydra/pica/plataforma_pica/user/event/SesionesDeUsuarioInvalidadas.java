package com.hydra.pica.plataforma_pica.user.event;

/**
 * Lo publica {@code UsuarioEdicionService} cuando al usuario hay que cerrarle las sesiones: lo
 * dieron de baja o un admin le reseteó la contraseña. Quien maneja los refresh tokens (PICA-118)
 * los revoca escuchando este evento, igual que con {@link RolesDeUsuarioCambiados}.
 */
public record SesionesDeUsuarioInvalidadas(Long usuarioId) {
}
