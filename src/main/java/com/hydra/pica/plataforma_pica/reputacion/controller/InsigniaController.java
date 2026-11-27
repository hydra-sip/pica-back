package com.hydra.pica.plataforma_pica.reputacion.controller;

import com.hydra.pica.plataforma_pica.reputacion.dto.LogrosJugador;
import com.hydra.pica.plataforma_pica.reputacion.service.InsigniaService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Las insignias obtenidas y las rifas ganadas de un jugador (HU-804, PICA-425). Con sesión, cualquiera: las ve el
 * jugador en Mi perfil y el resto de la plataforma al consultar sus logros.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class InsigniaController {

    private final InsigniaService insigniaService;

    @GetMapping("/usuarios/{id}/logros")
    public LogrosJugador ver(@PathVariable Long id) {
        return insigniaService.ver(id);
    }
}
