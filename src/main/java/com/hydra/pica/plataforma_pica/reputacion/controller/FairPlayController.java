package com.hydra.pica.plataforma_pica.reputacion.controller;

import com.hydra.pica.plataforma_pica.reputacion.dto.FairPlayDetalle;
import com.hydra.pica.plataforma_pica.reputacion.service.FairPlayService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * El índice de fair play de un usuario con su historial (HU-802). Con sesión, cualquiera: es la medida de confiabilidad
 * que ve el organizador al revisar inscriptos y cada uno en su perfil.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class FairPlayController {

    private final FairPlayService fairPlayService;

    @GetMapping("/usuarios/{id}/fair-play")
    public FairPlayDetalle ver(@PathVariable Long id) {
        return fairPlayService.ver(id);
    }
}
