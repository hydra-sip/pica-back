package com.hydra.pica.plataforma_pica.control.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.ErrorCampo;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.control.domain.AccionAuditoria;
import com.hydra.pica.plataforma_pica.control.domain.AlertaFraude;
import com.hydra.pica.plataforma_pica.control.domain.EstadoAlertaFraude;
import com.hydra.pica.plataforma_pica.control.domain.ReglaFraude;
import com.hydra.pica.plataforma_pica.control.dto.AlertaFraudeDetalle;
import com.hydra.pica.plataforma_pica.control.dto.RevisarAlertaFraudeRequest;
import com.hydra.pica.plataforma_pica.control.repository.AlertaFraudeRepository;
import com.hydra.pica.plataforma_pica.control.repository.AlertaFraudeSpecifications;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consulta y revisión administrativa de alertas de fraude (HU-705, PICA-416).
 */
@Service
@RequiredArgsConstructor
public class AlertaFraudeAdminService {

    private static final int NOTA_MAXIMA = 1000;
    private static final String ENTIDAD_AUDITORIA = "ALERTA_FRAUDE";
    // PENDIENTE ('P') ordena antes que REVISADA ('R'), y dentro de cada estado las más recientes primero.
    private static final Sort ORDEN_ADMIN = Sort.by(
            Sort.Order.asc("estado"),
            Sort.Order.desc("creadaEn"),
            Sort.Order.desc("id"));

    private final AlertaFraudeRepository alertaFraudeRepository;
    private final UsuarioRepository usuarioRepository;
    private final AuditoriaService auditoriaService;

    @Transactional(readOnly = true)
    public Page<AlertaFraudeDetalle> listar(
            EstadoAlertaFraude estado,
            ReglaFraude regla,
            Long torneoId,
            Long rifaId,
            Long usuarioId,
            Instant desde,
            Instant hasta,
            int page,
            int size) {
        Specification<AlertaFraude> filtros = Specification.allOf(
                AlertaFraudeSpecifications.conEstado(estado),
                AlertaFraudeSpecifications.conRegla(regla),
                AlertaFraudeSpecifications.delTorneo(torneoId),
                AlertaFraudeSpecifications.deLaRifa(rifaId),
                AlertaFraudeSpecifications.delUsuario(usuarioId),
                AlertaFraudeSpecifications.desde(desde),
                AlertaFraudeSpecifications.hasta(hasta));
        return alertaFraudeRepository.findAll(filtros, PageRequest.of(page, size, ORDEN_ADMIN))
                .map(AlertaFraudeDetalle::de);
    }

    @Transactional(readOnly = true)
    public AlertaFraudeDetalle detalle(Long id) {
        AlertaFraude alerta = alertaFraudeRepository.findConRelacionesPorId(id)
                .orElseThrow(() -> noEncontrada(id));
        return AlertaFraudeDetalle.de(alerta);
    }

    @Transactional
    public AlertaFraudeDetalle revisar(Long revisorId, Long id, RevisarAlertaFraudeRequest request) {
        String nota = validarNota(request);

        Usuario revisor = usuarioRepository.findById(revisorId)
                .orElseThrow(() -> new NoEncontradoException(CodigoError.USUARIO_NO_ENCONTRADO,
                        "No existe el usuario " + revisorId));

        AlertaFraude alerta = alertaFraudeRepository.buscarBloqueado(id)
                .orElseThrow(() -> noEncontrada(id));

        if (alerta.getEstado() != EstadoAlertaFraude.PENDIENTE) {
            throw new ConflictoException(CodigoError.ALERTA_FRAUDE_YA_REVISADA,
                    "La alerta " + id + " ya fue revisada previamente");
        }

        alerta.setEstado(EstadoAlertaFraude.REVISADA);
        alerta.setNota(nota);
        alerta.setRevisadaPor(revisor);
        alerta.setRevisadaEn(Instant.now());
        alerta = alertaFraudeRepository.saveAndFlush(alerta);

        Map<String, Object> detalleAuditoria = new LinkedHashMap<>();
        detalleAuditoria.put("regla", alerta.getRegla().name());
        detalleAuditoria.put("usuarioId", alerta.getUsuario().getId());
        if (alerta.getTorneo() != null) {
            detalleAuditoria.put("torneoId", alerta.getTorneo().getId());
        }
        if (alerta.getRifa() != null) {
            detalleAuditoria.put("rifaId", alerta.getRifa().getId());
        }
        detalleAuditoria.put("nota", nota);
        auditoriaService.registrar(revisorId, AccionAuditoria.MODIFICACION, ENTIDAD_AUDITORIA,
                String.valueOf(alerta.getId()), detalleAuditoria);

        return AlertaFraudeDetalle.de(alerta);
    }

    private static String validarNota(RevisarAlertaFraudeRequest request) {
        if (request == null || request.nota() == null || request.nota().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION, "Hay campos inválidos")
                    .con("errores", List.of(new ErrorCampo(
                            "nota", ErrorCampo.Codigo.REQUERIDO, "Indicá una nota de revisión")));
        }
        String nota = request.nota().strip();
        if (nota.length() > NOTA_MAXIMA) {
            throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION, "Hay campos inválidos")
                    .con("errores", List.of(new ErrorCampo(
                            "nota", ErrorCampo.Codigo.LONGITUD, "Hasta " + NOTA_MAXIMA + " caracteres")));
        }
        return nota;
    }

    private static NoEncontradoException noEncontrada(Long id) {
        return new NoEncontradoException(CodigoError.ALERTA_FRAUDE_NO_ENCONTRADA,
                "No existe la alerta de fraude " + id);
    }
}
