package com.hydra.pica.plataforma_pica.control.service;

import java.util.Map;

import com.hydra.pica.plataforma_pica.control.domain.AccionAuditoria;
import com.hydra.pica.plataforma_pica.control.domain.Auditoria;
import com.hydra.pica.plataforma_pica.control.repository.AuditoriaRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Servicio para registrar eventos auditables de solo agregado (RNF-AUD-01).
 */
@Service
@RequiredArgsConstructor
public class AuditoriaService {

    private final AuditoriaRepository auditoriaRepository;
    private final UsuarioRepository usuarioRepository;

    @Transactional(propagation = Propagation.REQUIRED)
    public Auditoria registrar(Long usuarioId, AccionAuditoria accion, String entidad, String entidadId, Map<String, Object> detalle) {
        Auditoria auditoria = new Auditoria();
        if (usuarioId != null) {
            auditoria.setUsuario(usuarioRepository.getReferenceById(usuarioId));
        }
        auditoria.setAccion(accion);
        auditoria.setEntidad(entidad);
        auditoria.setEntidadId(entidadId);
        auditoria.setDetalle(detalle);
        return auditoriaRepository.save(auditoria);
    }
}

