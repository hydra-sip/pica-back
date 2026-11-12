package com.hydra.pica.plataforma_pica.control.service;

import java.time.Instant;
import java.util.Map;

import com.hydra.pica.plataforma_pica.control.domain.AccionAuditoria;
import com.hydra.pica.plataforma_pica.control.domain.Auditoria;
import com.hydra.pica.plataforma_pica.control.dto.RegistroAuditoria;
import com.hydra.pica.plataforma_pica.control.repository.AuditoriaRepository;
import com.hydra.pica.plataforma_pica.control.repository.AuditoriaSpecifications;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Servicio para registrar eventos auditables de solo agregado (RNF-AUD-01).
 */
@Service
@RequiredArgsConstructor
public class AuditoriaService {

    private static final Sort MAS_NUEVO_PRIMERO = Sort.by(Sort.Direction.DESC, "creadoEn", "id");

    private final AuditoriaRepository auditoriaRepository;
    private final UsuarioRepository usuarioRepository;

    @Transactional(propagation = Propagation.REQUIRED)
    public Auditoria registrar(Long usuarioId, AccionAuditoria accion, String entidad, String entidadId, Map<String, Object> detalle) {
        return guardar(usuarioId, accion, entidad, entidadId, detalle);
    }

    /**
     * Registra el evento en una transacción autónoma independiente (REQUIRES_NEW), garantizando
     * que persista en la base de datos incluso si la transacción de quien invoca falla o hace rollback
     * (por ejemplo, ante intentos de cobro o carga rechazados).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Auditoria registrarEnTransaccionNueva(Long usuarioId, AccionAuditoria accion, String entidad, String entidadId, Map<String, Object> detalle) {
        return guardar(usuarioId, accion, entidad, entidadId, detalle);
    }

    /** El libro de auditoría (HU-704): los filtros se combinan; del más nuevo al más viejo. */
    @Transactional(readOnly = true)
    public Page<RegistroAuditoria> listar(Long torneoId, Long usuarioId, AccionAuditoria accion, Instant desde,
                                         Instant hasta, int pagina, int tamanio) {
        Specification<Auditoria> filtros = Specification.allOf(
                AuditoriaSpecifications.delTorneo(torneoId),
                AuditoriaSpecifications.delUsuario(usuarioId),
                AuditoriaSpecifications.conAccion(accion),
                AuditoriaSpecifications.desde(desde),
                AuditoriaSpecifications.hasta(hasta));
        return auditoriaRepository.findAll(filtros, PageRequest.of(pagina, tamanio, MAS_NUEVO_PRIMERO))
                .map(RegistroAuditoria::desde);
    }

    private Auditoria guardar(Long usuarioId, AccionAuditoria accion, String entidad, String entidadId, Map<String, Object> detalle) {
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
