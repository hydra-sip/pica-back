package com.hydra.pica.plataforma_pica.user.controller;

import com.hydra.pica.plataforma_pica.common.dto.PaginaResponse;
import com.hydra.pica.plataforma_pica.user.dto.UsuarioPublico;
import com.hydra.pica.plataforma_pica.user.service.UsuarioBusquedaService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Búsqueda de jugadores para cargar una nómina (PICA-208). Sin @PreAuthorize: cualquier usuario logueado
 * busca, y el "logueado" ya lo exige SecurityConfig.
 */
@RestController
@RequestMapping("/api/v1/usuarios")
public class UsuarioController {

    private final UsuarioBusquedaService usuarioBusquedaService;

    public UsuarioController(UsuarioBusquedaService usuarioBusquedaService) {
        this.usuarioBusquedaService = usuarioBusquedaService;
    }

    /**
     * q obligatorio y no en blanco: sin texto la búsqueda listaría a todos los usuarios activos. El mínimo
     * de 3 caracteres lo valida el servicio sobre el texto sin espacios; acá daría dos errores para "  ".
     */
    @GetMapping
    public PaginaResponse<UsuarioPublico> buscar(
            @RequestParam @NotBlank @Size(max = 100) String q,
            @RequestParam(required = false, defaultValue = "0") @Min(0) int page,
            @RequestParam(required = false, defaultValue = "20") @Min(1) @Max(100) int size) {
        return PaginaResponse.desde(usuarioBusquedaService.buscarJugadores(q, page, size));
    }
}
