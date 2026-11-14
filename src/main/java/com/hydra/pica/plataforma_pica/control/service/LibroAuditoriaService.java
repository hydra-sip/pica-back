package com.hydra.pica.plataforma_pica.control.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.hydra.pica.plataforma_pica.control.domain.AccionAuditoria;
import com.hydra.pica.plataforma_pica.control.domain.Auditoria;
import com.hydra.pica.plataforma_pica.control.dto.RegistroAuditoria;
import com.hydra.pica.plataforma_pica.control.repository.AuditoriaRepository;
import com.hydra.pica.plataforma_pica.control.repository.AuditoriaSpecifications;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** El admin consulta el libro de auditoría (HU-704, PICA-345). Lo que se registra va por {@link AuditoriaService}. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LibroAuditoriaService {

    private static final Sort MAS_NUEVO_PRIMERO = Sort.by(Sort.Direction.DESC, "creadoEn", "id");

    private final AuditoriaRepository auditoriaRepository;
    private final DescripcionesAuditoria descripcionesAuditoria;

    /** Los filtros se combinan; del más nuevo al más viejo, con la descripción de cada entidad. */
    public Page<RegistroAuditoria> listar(String entidad, String entidadId, Long usuarioId, AccionAuditoria accion,
                                         Instant desde, Instant hasta, int pagina, int tamanio) {
        Specification<Auditoria> filtros = Specification.allOf(
                AuditoriaSpecifications.deLaEntidad(entidad, entidadId),
                AuditoriaSpecifications.delUsuario(usuarioId),
                AuditoriaSpecifications.conAccion(accion),
                AuditoriaSpecifications.desde(desde),
                AuditoriaSpecifications.hasta(hasta));
        Page<Auditoria> registros = auditoriaRepository.findAll(filtros,
                PageRequest.of(pagina, tamanio, MAS_NUEVO_PRIMERO));
        Map<String, String> descripciones = descripcionesAuditoria.de(registros.getContent());
        return registros.map(a -> RegistroAuditoria.desde(a,
                descripciones.get(DescripcionesAuditoria.clave(a.getEntidad(), a.getEntidadId()))));
    }

    /** Los ids de una entidad que tienen registros, del que tuvo el último más reciente al más viejo: hasta 200. */
    public List<String> idsConRegistros(String entidad) {
        return auditoriaRepository.idsConRegistros(entidad);
    }
}
