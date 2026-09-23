package com.hydra.pica.plataforma_pica.user.controller;

import java.net.URI;

import com.hydra.pica.plataforma_pica.user.dto.FiltroRoles;
import com.hydra.pica.plataforma_pica.user.dto.PermisosRequest;
import com.hydra.pica.plataforma_pica.user.dto.RolDetalle;
import com.hydra.pica.plataforma_pica.user.dto.RolRequest;
import com.hydra.pica.plataforma_pica.user.dto.RolResumen;
import com.hydra.pica.plataforma_pica.user.service.RolService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * ABM de roles del backoffice (PICA-125) y sus permisos (PICA-126). Cada endpoint exige el
 * permiso de x-permiso en docs/api/openapi.yaml; las reglas están en {@link RolService}.
 */
@RestController
@RequestMapping("/api/v1/admin/roles")
@RequiredArgsConstructor
public class RolAdminController {

    private final RolService rolService;

    @GetMapping
    @PreAuthorize("hasAuthority('ROL_VER')")
    public Page<RolResumen> listar(@Valid FiltroRoles filtro, @PageableDefault(size = 20, sort = "id") Pageable pageable) {
        return rolService.listar(filtro, pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('ROL_VER')")
    public RolDetalle ver(@PathVariable Long id) {
        return rolService.detalle(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ROL_CREAR')")
    public ResponseEntity<RolDetalle> crear(@Valid @RequestBody RolRequest request) {
        RolDetalle creado = rolService.crear(request);
        URI ubicacion = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(creado.id())
                .toUri();
        return ResponseEntity.created(ubicacion).body(creado);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ROL_EDITAR')")
    public RolDetalle modificar(@PathVariable Long id, @Valid @RequestBody RolRequest request) {
        return rolService.modificar(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ROL_ELIMINAR')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void eliminar(@PathVariable Long id) {
        rolService.eliminar(id);
    }

    @PostMapping("/{id}/reactivar")
    @PreAuthorize("hasAuthority('ROL_ELIMINAR')")
    public RolDetalle reactivar(@PathVariable Long id) {
        return rolService.reactivar(id);
    }

    @PutMapping("/{id}/permisos")
    @PreAuthorize("hasAuthority('ROL_EDITAR')")
    public RolDetalle reemplazarPermisos(@PathVariable Long id, @Valid @RequestBody PermisosRequest request) {
        return rolService.reemplazarPermisos(id, request.permisos());
    }
}
