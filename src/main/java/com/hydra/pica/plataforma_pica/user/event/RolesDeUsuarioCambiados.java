package com.hydra.pica.plataforma_pica.user.event;

/**
 * Lo publica {@code UsuarioRolService.reemplazarRoles} cuando los roles de un usuario cambiaron de
 * verdad (no si se mandó la misma lista). Los permisos viajan en el access token, así que para que
 * el usuario los vea hay que revocarle los refresh tokens y obligarlo a loguearse de nuevo: eso lo
 * hace quien maneja los refresh tokens (PICA-118) escuchando este evento.
 */
public record RolesDeUsuarioCambiados(Long usuarioId) {
}
