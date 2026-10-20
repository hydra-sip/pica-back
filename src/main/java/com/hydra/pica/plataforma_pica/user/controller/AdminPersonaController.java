package com.hydra.pica.plataforma_pica.user.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.hydra.pica.plataforma_pica.common.dto.PaginaResponse;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.dto.PersonaDetalle;
import com.hydra.pica.plataforma_pica.user.dto.PersonaRequest;
import com.hydra.pica.plataforma_pica.user.dto.PersonaResumen;
import com.hydra.pica.plataforma_pica.user.service.PersonaAdminService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/personas")
public class AdminPersonaController {

    /** Los mismos nombres que acepta el repositorio; cualquier otro se ignora. */
    private static final Set<String> CAMPOS_ORDENABLES =
            Set.of("id", "nombres", "apellidos", "nroDoc", "estado", "creadoEn");

    private static final Sort ORDEN_POR_DEFECTO = Sort.by("apellidos", "nombres");

    private final PersonaAdminService personaAdminService;

    public AdminPersonaController(PersonaAdminService personaAdminService) {
        this.personaAdminService = personaAdminService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERSONA_VER')")
    public PaginaResponse<PersonaResumen> listar(
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) EstadoGeneral estado,
            @RequestParam(required = false, defaultValue = "false") boolean incluirEliminados,
            @RequestParam(required = false, defaultValue = "0") @Min(0) int page,
            @RequestParam(required = false, defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) List<String> sort) {
        return PaginaResponse.desde(personaAdminService.listar(
                q, estado, incluirEliminados, PageRequest.of(page, size, construirOrden(sort))));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PERSONA_VER')")
    public PersonaDetalle ver(@PathVariable Long id) {
        return personaAdminService.obtener(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERSONA_CREAR')")
    @ResponseStatus(HttpStatus.CREATED)
    public PersonaDetalle crear(@Valid @RequestBody PersonaRequest request) {
        return personaAdminService.crear(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PERSONA_EDITAR')")
    public PersonaDetalle modificar(@PathVariable Long id, @Valid @RequestBody PersonaRequest request) {
        return personaAdminService.modificar(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('PERSONA_ELIMINAR')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void eliminar(@PathVariable Long id) {
        personaAdminService.eliminar(id);
    }

    @PostMapping("/{id}/reactivar")
    @PreAuthorize("hasAuthority('PERSONA_ELIMINAR')")
    public PersonaDetalle reactivar(@PathVariable Long id) {
        return personaAdminService.reactivar(id);
    }

    private Sort construirOrden(List<String> sort) {
        if (sort == null || sort.isEmpty()) {
            return ORDEN_POR_DEFECTO;
        }
        List<Sort.Order> ordenes = new ArrayList<>();
        for (String criterio : sort) {
            String[] partes = criterio.split(",", 2);
            String campo = partes[0].trim();
            if (!CAMPOS_ORDENABLES.contains(campo)) {
                continue;
            }
            Sort.Direction direccion = partes.length > 1 && "desc".equalsIgnoreCase(partes[1].trim())
                    ? Sort.Direction.DESC
                    : Sort.Direction.ASC;
            ordenes.add(new Sort.Order(direccion, campo));
        }
        return ordenes.isEmpty() ? ORDEN_POR_DEFECTO : Sort.by(ordenes);
    }
}
