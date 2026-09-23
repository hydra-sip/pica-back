package com.hydra.pica.plataforma_pica.user.service;

import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hydra.pica.plataforma_pica.common.config.AdminConfig.AdminProperties;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.dto.PersonaUsuario;
import com.hydra.pica.plataforma_pica.user.dto.RolMinimo;
import com.hydra.pica.plataforma_pica.user.dto.UsuarioCreateRequest;
import com.hydra.pica.plataforma_pica.user.dto.UsuarioDetalle;
import com.hydra.pica.plataforma_pica.user.dto.UsuarioResumen;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioAdminRepositoryCustom;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioSpecifications;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UsuarioAdminService {

    private final UsuarioRepository usuarioRepository;
    private final UsuarioService usuarioService;
    private final AdminProperties adminProperties;
    private final ObjectMapper objectMapper;

    public UsuarioAdminService(
            UsuarioRepository usuarioRepository, UsuarioService usuarioService, AdminProperties adminProperties,
            ObjectMapper objectMapper) {
        this.usuarioRepository = usuarioRepository;
        this.usuarioService = usuarioService;
        this.adminProperties = adminProperties;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public Page<UsuarioResumen> listar(
            String q, EstadoUsuario estado, Long rolId, boolean incluirEliminados, Pageable pageable) {
        Specification<Usuario> filtro = Specification
                .where(UsuarioSpecifications.conTexto(q))
                .and(UsuarioSpecifications.conEstado(estado))
                .and(UsuarioSpecifications.conRol(rolId));

        if (incluirEliminados) {
            return usuarioRepository.buscarIncluyendoEliminados(q, estado, rolId, pageable)
                    .map(this::aResumen);
        }

        Page<Usuario> pagina = usuarioRepository.findAll(filtro, pageable);
        if (!pagina.isEmpty()) {
            // inicializa usuario.getRoles() de toda la página de una vez; el resultado no se usa
            usuarioRepository.findConRolesByIdIn(pagina.getContent().stream().map(Usuario::getId).toList());
        }
        return pagina.map(this::aResumen);
    }

    private UsuarioResumen aResumen(UsuarioAdminRepositoryCustom.UsuarioAdminRow fila) {
        try {
            List<RolMinimo> roles = objectMapper.readValue(
                    fila.rolesJson(), new TypeReference<List<RolMinimo>>() { });
            return new UsuarioResumen(
                    fila.id(),
                    fila.username(),
                    fila.email(),
                    fila.estado(),
                    fila.eliminadoEn() != null,
                    fila.username().equalsIgnoreCase(adminProperties.username()),
                    new PersonaUsuario(fila.personaId(), fila.nombreCompleto(), fila.tipoDoc(), fila.nroDoc()),
                    roles,
                    fila.creadoEn());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("No se pudieron leer los roles del usuario " + fila.id(), exception);
        }
    }

    @Transactional(readOnly = true)
    public UsuarioDetalle obtenerDetalle(Long id) {
        Usuario usuario = usuarioRepository.findByIdIncluyendoEliminados(id)
                .orElseThrow(() -> new NoEncontradoException(
                        CodigoError.USUARIO_NO_ENCONTRADO, "No existe el usuario " + id));
        return UsuarioDetalle.desde(usuario, esProtegido(usuario));
    }

    @Transactional
    public UsuarioDetalle crear(UsuarioCreateRequest request) {
        EstadoUsuario estado = request.estado() == null ? null : EstadoUsuario.valueOf(request.estado().name());
        Set<Long> roles = request.roles() == null ? Set.of() : Set.copyOf(request.roles());

        Usuario usuario = usuarioService.crear(NuevoUsuario.porAdmin(
                request.username(), request.email(), request.passwordTemporal(), request.descripcion(),
                estado, request.personaId(), roles));

        return UsuarioDetalle.desde(usuario, esProtegido(usuario));
    }

    private UsuarioResumen aResumen(Usuario usuario) {
        return UsuarioResumen.desde(usuario, esProtegido(usuario));
    }

    private boolean esProtegido(Usuario usuario) {
        return usuario.getUsername().equalsIgnoreCase(adminProperties.username());
    }
}
