package com.hydra.pica.plataforma_pica.partido.controller;

import java.util.List;

import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.partido.dto.PartidoPronostico;
import com.hydra.pica.plataforma_pica.partido.dto.PronosticoDetalle;
import com.hydra.pica.plataforma_pica.partido.dto.PronosticoRequest;
import com.hydra.pica.plataforma_pica.partido.dto.RankingResponse;
import com.hydra.pica.plataforma_pica.partido.service.PronosticoService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints de pronósticos de partidos y ranking (HU-306, HU-307, T306-1, T307-1).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class PronosticoController {

    private final PronosticoService pronosticoService;
    private final CurrentUserProvider currentUserProvider;

    /**
     * Lista los partidos para pronóstico, incluyendo el pronóstico del usuario actual si está autenticado.
     */
    @GetMapping("/pronosticos/partidos")
    public List<PartidoPronostico> listarPartidos(
            @RequestParam(required = false) Long torneoId,
            @RequestParam(required = false) Boolean abierto) {
        Long usuarioId = currentUserProvider.getCurrentUserId().orElse(null);
        return pronosticoService.listarPartidos(usuarioId, torneoId, abierto);
    }

    /**
     * Obtiene el ranking general de posiciones en base a los puntajes obtenidos, o filtrado por torneo (HU-307, T307-1).
     */
    @GetMapping("/pronosticos/ranking")
    public RankingResponse obtenerRanking(@RequestParam(required = false) Long torneoId) {
        Long usuarioId = currentUserProvider.getCurrentUserId().orElse(null);
        return pronosticoService.obtenerRanking(usuarioId, torneoId);
    }


    /**
     * Carga o actualiza el pronóstico del usuario autenticado para un partido.
     */
    @PutMapping("/partidos/{id}/pronostico")
    public PronosticoDetalle pronosticar(
            @PathVariable Long id,
            @RequestBody PronosticoRequest request) {
        Long usuarioId = currentUserProvider.getCurrentUserId()
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Sin usuario en la sesión"));
        return pronosticoService.pronosticar(usuarioId, id, request);
    }
}
