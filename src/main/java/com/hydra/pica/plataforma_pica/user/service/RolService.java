package com.hydra.pica.plataforma_pica.user.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.common.error.ProhibidoException;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.Rol;
import com.hydra.pica.plataforma_pica.user.dto.FiltroRoles;
import com.hydra.pica.plataforma_pica.user.dto.RolDetalle;
import com.hydra.pica.plataforma_pica.user.dto.RolRequest;
import com.hydra.pica.plataforma_pica.user.dto.RolResumen;
import com.hydra.pica.plataforma_pica.user.repository.RolRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRolRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRolRepository.CantidadPorRol;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ABM de roles del backoffice (PICA-125). Los permisos de cada rol van aparte (PICA-126).
 *
 * Dos roles están protegidos. SUPER_USUARIO (es_sistema) no se modifica ni se da de baja.
 * PARTICIPANTE es el que el registro le pone a todo usuario nuevo buscándolo por nombre
 * ({@link UsuarioService#ROL_PARTICIPANTE}): renombrarlo, inactivarlo o darlo de baja dejaría al
 * registro sin rol, así que eso da 403; el nombre amigable y la descripción sí se pueden cambiar.
 * Para sacarlo de verdad hay que cambiar esa constante (la protección la sigue) y migrar a sus
 * usuarios a otro rol.
 *
 * La baja no toca las asignaciones: un rol eliminado deja de sumar permisos (PermisoRepository solo
 * cuenta roles vivos y activos) y al reactivarlo sus usuarios lo recuperan tal cual estaba.
 */
@Service
@RequiredArgsConstructor
public class RolService {

    private static final String UQ_NOMBRE = "uq_rol_nombre";

    /**
     * Campos del contrato por los que se puede ordenar el listado, con su columna. La consulta es
     * nativa y Spring pega el Sort en el ORDER BY sin traducir, así que un campo que no esté acá
     * sería un error de SQL (500); se corta antes con un 400.
     */
    private static final Map<String, String> COLUMNAS_ORDENABLES = Map.of(
            "id", "id",
            "nombre", "nombre",
            "nombreAmigable", "nombre_amigable",
            "estado", "estado",
            "creadoEn", "creado_en");

    private final RolRepository rolRepository;
    private final UsuarioRolRepository usuarioRolRepository;

    @Transactional(readOnly = true)
    public Page<RolResumen> listar(FiltroRoles filtro, Pageable pageable) {
        String q = filtro.q() == null || filtro.q().isBlank() ? null : filtro.q().strip();
        String estado = filtro.estado() == null ? null : filtro.estado().name();
        Page<Rol> roles = rolRepository.buscar(q, estado, filtro.conEliminados(), porColumna(pageable));

        Map<Long, Long> cantidades = cantidadesDeUsuarios(roles.map(Rol::getId).getContent());
        return roles.map(rol -> RolResumen.de(rol, cantidades.getOrDefault(rol.getId(), 0L)));
    }

    /** Trae también los dados de baja, para que la pantalla los pueda abrir y reactivar. */
    @Transactional(readOnly = true)
    public RolDetalle detalle(Long id) {
        return detalle(buscarIncluyendoEliminados(id));
    }

    /** Nace sin permisos; se los pone PUT /admin/roles/{id}/permisos. */
    @Transactional
    public RolDetalle crear(RolRequest request) {
        verificarNombreLibre(request.nombre());

        Rol rol = new Rol();
        rol.setNombre(request.nombre());
        rol.setNombreAmigable(request.nombreAmigable().strip());
        rol.setDescripcion(limpiar(request.descripcion()));
        // la columna tiene default en la BD pero Hibernate manda el null si no se setea
        rol.setEstado(request.estado() != null ? request.estado() : EstadoGeneral.ACTIVO);
        return detalle(guardar(rol));
    }

    @Transactional
    public RolDetalle modificar(Long id, RolRequest request) {
        Rol rol = buscarVivo(id);
        EstadoGeneral estado = request.estado() != null ? request.estado() : rol.getEstado();
        boolean cambiaNombre = !rol.getNombre().equals(request.nombre());

        if (rol.isEsSistema()) {
            throw new ProhibidoException(CodigoError.ROL_PROTEGIDO,
                    "El rol " + rol.getNombre() + " es del sistema y no se puede modificar");
        }
        if (esParticipante(rol) && (cambiaNombre || estado != EstadoGeneral.ACTIVO)) {
            throw new ProhibidoException(CodigoError.ROL_PROTEGIDO, "El registro le asigna " + rol.getNombre()
                    + " a cada usuario nuevo: no se puede renombrar ni inactivar");
        }
        if (cambiaNombre) {
            verificarNombreLibre(request.nombre());
        }

        rol.setNombre(request.nombre());
        rol.setNombreAmigable(request.nombreAmigable().strip());
        rol.setDescripcion(limpiar(request.descripcion()));
        rol.setEstado(estado);
        return detalle(guardar(rol));
    }

    @Transactional
    public void eliminar(Long id) {
        Rol rol = buscarVivo(id);
        if (rol.isEsSistema()) {
            throw new ProhibidoException(CodigoError.ROL_PROTEGIDO,
                    "El rol " + rol.getNombre() + " es del sistema y no se puede dar de baja");
        }
        if (esParticipante(rol)) {
            throw new ProhibidoException(CodigoError.ROL_PROTEGIDO, "El registro le asigna " + rol.getNombre()
                    + " a cada usuario nuevo: no se puede dar de baja");
        }
        rol.setEliminadoEn(Instant.now());
    }

    /** Si no estaba dado de baja no cambia nada y lo devuelve igual. */
    @Transactional
    public RolDetalle reactivar(Long id) {
        Rol rol = buscarIncluyendoEliminados(id);
        rol.setEliminadoEn(null);
        // el flush es para que modificadoEn salga actualizado en la respuesta
        return detalle(rolRepository.saveAndFlush(rol));
    }

    private Rol buscarIncluyendoEliminados(Long id) {
        return rolRepository.findByIdIncluyendoEliminados(id)
                .orElseThrow(() -> noEncontrado(id));
    }

    /**
     * Para modificar o dar de baja: uno eliminado da 404, primero hay que reactivarlo. El filtro
     * repite lo que ya hace el @SQLRestriction porque findById devuelve lo que haya en la sesión
     * sin volver a la base.
     */
    private Rol buscarVivo(Long id) {
        return rolRepository.findById(id)
                .filter(rol -> rol.getEliminadoEn() == null)
                .orElseThrow(() -> noEncontrado(id));
    }

    private static NoEncontradoException noEncontrado(Long id) {
        return new NoEncontradoException(CodigoError.ROL_NO_ENCONTRADO, "No existe el rol " + id);
    }

    /** El nombre es único contando a los eliminados, así que se busca también entre ellos. */
    private void verificarNombreLibre(String nombre) {
        rolRepository.findByNombreIncluyendoEliminados(nombre).ifPresent(existente -> {
            throw new ConflictoException(CodigoError.ROL_DUPLICADO, existente.getEliminadoEn() != null
                    ? "Ya existe un rol " + nombre + " dado de baja: reactivalo en vez de crear otro"
                    : "Ya existe un rol " + nombre);
        });
    }

    /**
     * Dos altas simultáneas con el mismo nombre pasan las dos por {@link #verificarNombreLibre} y
     * una pierde contra uq_rol_nombre; sin esto el cliente vería un 500.
     */
    private Rol guardar(Rol rol) {
        try {
            // flush ahora para que el choque salte acá y no al cerrar la transacción
            return rolRepository.saveAndFlush(rol);
        } catch (DataIntegrityViolationException e) {
            if (!UQ_NOMBRE.equals(nombreDeConstraint(e))) {
                throw e;
            }
            throw new ConflictoException(CodigoError.ROL_DUPLICADO,
                    "El rol " + rol.getNombre() + " se creó al mismo tiempo desde otra solicitud");
        }
    }

    private static String nombreDeConstraint(DataIntegrityViolationException e) {
        Throwable causa = e;
        while (causa != null && !(causa instanceof ConstraintViolationException)) {
            causa = causa.getCause();
        }
        return causa == null ? null : ((ConstraintViolationException) causa).getConstraintName();
    }

    private RolDetalle detalle(Rol rol) {
        long cantidad = cantidadesDeUsuarios(List.of(rol.getId())).getOrDefault(rol.getId(), 0L);
        return RolDetalle.de(rol, cantidad);
    }

    private Map<Long, Long> cantidadesDeUsuarios(Collection<Long> rolIds) {
        if (rolIds.isEmpty()) {
            return Map.of();
        }
        return usuarioRolRepository.contarUsuariosPorRol(rolIds).stream()
                .collect(Collectors.toMap(CantidadPorRol::getRolId, CantidadPorRol::getCantidad));
    }

    private static boolean esParticipante(Rol rol) {
        return UsuarioService.ROL_PARTICIPANTE.equals(rol.getNombre());
    }

    private static String limpiar(String texto) {
        return texto == null || texto.isBlank() ? null : texto.strip();
    }

    /** Pasa los campos del contrato a columnas y desempata por id para que el paginado sea estable. */
    private static Pageable porColumna(Pageable pageable) {
        List<Sort.Order> orden = new ArrayList<>();
        for (Sort.Order pedido : pageable.getSort()) {
            String columna = COLUMNAS_ORDENABLES.get(pedido.getProperty());
            if (columna == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION,
                        "No se puede ordenar por " + pedido.getProperty());
            }
            orden.add(new Sort.Order(pedido.getDirection(), columna));
        }
        if (orden.stream().noneMatch(o -> o.getProperty().equals("id"))) {
            orden.add(Sort.Order.asc("id"));
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(orden));
    }
}
