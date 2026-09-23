package com.hydra.pica.plataforma_pica.user.service;

import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hydra.pica.plataforma_pica.common.config.AdminConfig.AdminProperties;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.dto.PersonaUsuario;
import com.hydra.pica.plataforma_pica.user.dto.RolMinimo;
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
    private final AdminProperties adminProperties;
    private final ObjectMapper objectMapper;

    public UsuarioAdminService(
            UsuarioRepository usuarioRepository, AdminProperties adminProperties, ObjectMapper objectMapper) {
        this.usuarioRepository = usuarioRepository;
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

    private UsuarioResumen aResumen(Usuario usuario) {
        boolean protegido = usuario.getUsername().equalsIgnoreCase(adminProperties.username());
        return UsuarioResumen.desde(usuario, protegido);
    }
}
