package com.hydra.pica.plataforma_pica.control.dto;

import java.time.Instant;
import java.util.Map;

import com.hydra.pica.plataforma_pica.control.domain.AccionAuditoria;
import com.hydra.pica.plataforma_pica.control.domain.Auditoria;

/**
 * Una fila del libro de auditoría (HU-704, PICA-345).
 *
 * @param usuarioId null en lo que hace el sistema (liquidaciones, cierres por fecha) y en el login fallido de un
 *                  usuario que no existe
 * @param ip        solo la guardan el login y el login fallido
 */
public record RegistroAuditoria(
        Long id,
        Instant fecha,
        Long usuarioId,
        String username,
        AccionAuditoria accion,
        String entidad,
        String entidadId,
        String ip,
        Map<String, Object> detalle) {

    public static RegistroAuditoria desde(Auditoria auditoria) {
        var usuario = auditoria.getUsuario();
        return new RegistroAuditoria(auditoria.getId(), auditoria.getCreadoEn(),
                usuario == null ? null : usuario.getId(), usuario == null ? null : usuario.getUsername(),
                auditoria.getAccion(), auditoria.getEntidad(), auditoria.getEntidadId(), auditoria.getIp(),
                auditoria.getDetalle());
    }
}
