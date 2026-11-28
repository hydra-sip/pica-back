package com.hydra.pica.plataforma_pica.notificacion.controller;

import com.hydra.pica.plataforma_pica.common.dto.PaginaResponse;
import com.hydra.pica.plataforma_pica.common.security.UsuarioActual;
import com.hydra.pica.plataforma_pica.notificacion.dto.NotificacionDetalle;
import com.hydra.pica.plataforma_pica.notificacion.service.NotificacionService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** El centro de notificaciones del usuario logueado (HU-808, T808-1). */
@RestController
@RequestMapping("/api/v1/me/notificaciones")
@RequiredArgsConstructor
public class NotificacionController {

    private final NotificacionService notificacionService;

    /**
     * Sin {@code leida}, todas; {@code leida=false}, las que faltan leer; {@code leida=true}, las leídas. La campana
     * pide {@code leida=false&size=1} y usa {@code page.totalElements} como contador.
     */
    @GetMapping
    public PaginaResponse<NotificacionDetalle> listar(
            @RequestParam(required = false) Boolean leida,
            @RequestParam(required = false, defaultValue = "0") @Min(0) int page,
            @RequestParam(required = false, defaultValue = "20") @Min(1) @Max(100) int size,
            @UsuarioActual Long usuarioId) {
        return PaginaResponse.desde(notificacionService.listar(usuarioId, leida, page, size));
    }

    @PostMapping("/{id}/leida")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void marcarLeida(@PathVariable Long id, @UsuarioActual Long usuarioId) {
        notificacionService.marcarLeida(usuarioId, id);
    }

    @PostMapping("/leidas")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void marcarTodasLeidas(@UsuarioActual Long usuarioId) {
        notificacionService.marcarTodasLeidas(usuarioId);
    }
}
