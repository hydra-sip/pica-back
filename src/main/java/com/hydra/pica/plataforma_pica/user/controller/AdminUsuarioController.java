package com.hydra.pica.plataforma_pica.user.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.hydra.pica.plataforma_pica.common.dto.PaginaResponse;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.dto.RolesRequest;
import com.hydra.pica.plataforma_pica.user.dto.UsuarioCreateRequest;
import com.hydra.pica.plataforma_pica.user.dto.UsuarioDetalle;
import com.hydra.pica.plataforma_pica.user.dto.UsuarioResumen;
import com.hydra.pica.plataforma_pica.user.service.UsuarioAdminService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
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
@RequestMapping("/api/v1/admin/usuarios")
public class AdminUsuarioController {

    private static final Map<String, String> CAMPOS_ORDENABLES = Map.of(
            "id", "id",
            "username", "username",
            "email", "email",
            "estado", "estado",
            "creadoEn", "creadoEn",
            "apellidos", "persona.apellidos",
            "nombres", "persona.nombres");

    private static final Sort ORDEN_POR_DEFECTO = Sort.by(Sort.Direction.DESC, "creadoEn");

    private final UsuarioAdminService usuarioAdminService;

    public AdminUsuarioController(UsuarioAdminService usuarioAdminService) {
        this.usuarioAdminService = usuarioAdminService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('USUARIO_VER')")
    public PaginaResponse<UsuarioResumen> listar(
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) EstadoUsuario estado,
            @RequestParam(required = false) Long rol,
            @RequestParam(required = false, defaultValue = "false") boolean incluirEliminados,
            @RequestParam(required = false, defaultValue = "0") @Min(0) int page,
            @RequestParam(required = false, defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) List<String> sort) {

        Page<UsuarioResumen> pagina = usuarioAdminService.listar(
                q, estado, rol, incluirEliminados, PageRequest.of(page, size, construirOrden(sort)));
        return PaginaResponse.desde(pagina);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('USUARIO_VER')")
    public UsuarioDetalle verDetalle(@PathVariable Long id) {
        return usuarioAdminService.obtenerDetalle(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('USUARIO_CREAR')")
    @ResponseStatus(HttpStatus.CREATED)
    public UsuarioDetalle crear(@Valid @RequestBody UsuarioCreateRequest request) {
        return usuarioAdminService.crear(request);
    }

    @PutMapping("/{id}/roles")
    @PreAuthorize("hasAuthority('ROL_ASIGNAR')")
    public UsuarioDetalle reemplazarRoles(@PathVariable Long id, @Valid @RequestBody RolesRequest request) {
        return usuarioAdminService.reemplazarRoles(id, request.roles());
    }

    private Sort construirOrden(List<String> sort) {
        if (sort == null || sort.isEmpty()) {
            return ORDEN_POR_DEFECTO;
        }

        List<Sort.Order> ordenes = new ArrayList<>();
        for (String criterio : sort) {
            String[] partes = criterio.split(",", 2);
            String campo = CAMPOS_ORDENABLES.get(partes[0].trim());
            if (campo == null) {
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
