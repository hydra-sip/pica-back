package com.hydra.pica.plataforma_pica.control.controller;

import java.time.Instant;

import com.hydra.pica.plataforma_pica.common.dto.PaginaResponse;
import com.hydra.pica.plataforma_pica.common.security.UsuarioActual;
import com.hydra.pica.plataforma_pica.control.domain.EstadoAlertaFraude;
import com.hydra.pica.plataforma_pica.control.domain.ReglaFraude;
import com.hydra.pica.plataforma_pica.control.dto.AlertaFraudeDetalle;
import com.hydra.pica.plataforma_pica.control.dto.RevisarAlertaFraudeRequest;
import com.hydra.pica.plataforma_pica.control.service.AlertaFraudeAdminService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Panel administrativo de alertas de fraude (HU-705, PICA-416).
 */
@RestController
@RequestMapping("/api/v1/admin/alertas-fraude")
@PreAuthorize("hasAuthority('ALERTA_FRAUDE_REVISAR')")
@RequiredArgsConstructor
public class AlertaFraudeAdminController {

    private final AlertaFraudeAdminService alertaFraudeAdminService;

    @GetMapping
    public PaginaResponse<AlertaFraudeDetalle> listar(
            @RequestParam(required = false) EstadoAlertaFraude estado,
            @RequestParam(required = false) ReglaFraude regla,
            @RequestParam(required = false) Long torneoId,
            @RequestParam(required = false) Long torneo,
            @RequestParam(required = false) Long rifaId,
            @RequestParam(required = false) Long rifa,
            @RequestParam(required = false) Long usuarioId,
            @RequestParam(required = false) Long usuario,
            @RequestParam(required = false) Instant desde,
            @RequestParam(required = false) Instant hasta,
            @RequestParam(required = false, defaultValue = "0") @Min(0) int page,
            @RequestParam(required = false, defaultValue = "20") @Min(1) @Max(100) int size) {
        Long filtroTorneo = torneoId != null ? torneoId : torneo;
        Long filtroRifa = rifaId != null ? rifaId : rifa;
        Long filtroUsuario = usuarioId != null ? usuarioId : usuario;
        return PaginaResponse.desde(alertaFraudeAdminService.listar(
                estado, regla, filtroTorneo, filtroRifa, filtroUsuario, desde, hasta, page, size));
    }

    @GetMapping("/{id}")
    public AlertaFraudeDetalle detalle(@PathVariable Long id) {
        return alertaFraudeAdminService.detalle(id);
    }

    @PostMapping("/{id}/revisar")
    public AlertaFraudeDetalle revisar(
            @PathVariable Long id,
            @RequestBody(required = false) RevisarAlertaFraudeRequest request,
            @UsuarioActual Long revisorId) {
        return alertaFraudeAdminService.revisar(revisorId, id, request);
    }
}
