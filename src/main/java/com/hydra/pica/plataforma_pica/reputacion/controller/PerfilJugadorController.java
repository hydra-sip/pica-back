package com.hydra.pica.plataforma_pica.reputacion.controller;

import com.hydra.pica.plataforma_pica.reputacion.dto.PerfilJugador;
import com.hydra.pica.plataforma_pica.reputacion.service.PerfilJugadorService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Perfil público del jugador (HU-801): accesible sin iniciar sesión. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class PerfilJugadorController {

    private final PerfilJugadorService perfilJugadorService;

    @GetMapping("/usuarios/{id}/perfil")
    public PerfilJugador ver(@PathVariable Long id) {
        return perfilJugadorService.ver(id);
    }
}
