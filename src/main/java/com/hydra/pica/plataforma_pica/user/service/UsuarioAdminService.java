package com.hydra.pica.plataforma_pica.user.service;

import com.hydra.pica.plataforma_pica.common.config.AdminConfig.AdminProperties;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.dto.UsuarioResumen;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
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

    public UsuarioAdminService(UsuarioRepository usuarioRepository, AdminProperties adminProperties) {
        this.usuarioRepository = usuarioRepository;
        this.adminProperties = adminProperties;
    }

    @Transactional(readOnly = true)
    public Page<UsuarioResumen> listar(String q, EstadoUsuario estado, Long rolId, Pageable pageable) {
        Specification<Usuario> filtro = Specification
                .where(UsuarioSpecifications.conTexto(q))
                .and(UsuarioSpecifications.conEstado(estado))
                .and(UsuarioSpecifications.conRol(rolId));

        return usuarioRepository.findAll(filtro, pageable).map(this::aResumen);
    }

    private UsuarioResumen aResumen(Usuario usuario) {
        boolean protegido = usuario.getUsername().equalsIgnoreCase(adminProperties.username());
        return UsuarioResumen.desde(usuario, protegido);
    }
}
