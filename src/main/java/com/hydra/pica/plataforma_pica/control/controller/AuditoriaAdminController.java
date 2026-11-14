package com.hydra.pica.plataforma_pica.control.controller;

import java.time.Instant;
import java.util.List;

import com.hydra.pica.plataforma_pica.common.dto.PaginaResponse;
import com.hydra.pica.plataforma_pica.control.domain.AccionAuditoria;
import com.hydra.pica.plataforma_pica.control.dto.RegistroAuditoria;
import com.hydra.pica.plataforma_pica.control.service.LibroAuditoriaService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** El admin consulta el libro de auditoría (HU-704, PICA-345). */
@RestController
@RequestMapping("/api/v1/admin/auditoria")
@PreAuthorize("hasAuthority('AUDITORIA_VER')")
@RequiredArgsConstructor
public class AuditoriaAdminController {

    private final LibroAuditoriaService libroAuditoriaService;

    @GetMapping
    public PaginaResponse<RegistroAuditoria> listar(
            @RequestParam(required = false) @Size(max = 50) String entidad,
            @RequestParam(required = false) @Size(max = 50) String entidadId,
            @RequestParam(required = false) Long usuario,
            @RequestParam(required = false) AccionAuditoria accion,
            @RequestParam(required = false) Instant desde,
            @RequestParam(required = false) Instant hasta,
            @RequestParam(required = false, defaultValue = "0") @Min(0) int page,
            @RequestParam(required = false, defaultValue = "20") @Min(1) @Max(100) int size) {
        return PaginaResponse.desde(
                libroAuditoriaService.listar(entidad, entidadId, usuario, accion, desde, hasta, page, size));
    }

    /** Para elegir el id en el filtro: los de esa entidad que tienen registros. */
    @GetMapping("/ids")
    public List<String> ids(@RequestParam @Size(max = 50) String entidad) {
        return libroAuditoriaService.idsConRegistros(entidad);
    }
}
