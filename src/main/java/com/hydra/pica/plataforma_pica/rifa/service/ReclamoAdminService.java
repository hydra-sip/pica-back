package com.hydra.pica.plataforma_pica.rifa.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.ErrorCampo;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.control.domain.AccionAuditoria;
import com.hydra.pica.plataforma_pica.control.service.AuditoriaService;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoReclamo;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.ReclamoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;
import com.hydra.pica.plataforma_pica.rifa.dto.ReclamoAdminDetalle;
import com.hydra.pica.plataforma_pica.rifa.dto.ResolverReclamoRequest;
import com.hydra.pica.plataforma_pica.rifa.event.ReclamoRifaResuelto;
import com.hydra.pica.plataforma_pica.rifa.repository.ReclamoRifaRepository;
import com.hydra.pica.plataforma_pica.rifa.repository.RifaRepository;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gestión administrativa de reclamos de rifas y su resolución definitiva (HU-706, PICA-339).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReclamoAdminService {

    private static final String ENTIDAD_AUDITORIA = "RECLAMO_RIFA";

    private final ReclamoRifaRepository reclamoRifaRepository;
    private final RifaRepository rifaRepository;
    private final UsuarioRepository usuarioRepository;
    private final AuditoriaService auditoriaService;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(readOnly = true)
    public Page<ReclamoAdminDetalle> listar(EstadoReclamo estado, Pageable pageable) {
        Page<ReclamoRifa> pagina = estado == null
                ? reclamoRifaRepository.buscarTodosParaAdmin(pageable)
                : reclamoRifaRepository.buscarPorEstadoParaAdmin(estado, pageable);
        return pagina.map(ReclamoAdminDetalle::de);
    }

    @Transactional(readOnly = true)
    public ReclamoAdminDetalle detalle(Long reclamoId) {
        ReclamoRifa reclamo = reclamoRifaRepository.findDetalleById(reclamoId)
                .orElseThrow(() -> new NoEncontradoException(CodigoError.RECLAMO_NO_ENCONTRADO,
                        "No existe el reclamo " + reclamoId));
        return ReclamoAdminDetalle.de(reclamo);
    }

    @Transactional
    public ReclamoAdminDetalle resolver(Long resolutorId, Long reclamoId, ResolverReclamoRequest request) {
        validarRequest(request);

        Usuario resolutor = usuarioRepository.findById(resolutorId)
                .orElseThrow(() -> new NoEncontradoException(CodigoError.USUARIO_NO_ENCONTRADO,
                        "No existe el usuario " + resolutorId));

        Long rifaId = reclamoRifaRepository.findRifaIdById(reclamoId)
                .orElseThrow(() -> new NoEncontradoException(CodigoError.RECLAMO_NO_ENCONTRADO,
                        "No existe el reclamo " + reclamoId));

        Rifa rifa = rifaRepository.buscarBloqueado(rifaId)
                .orElseThrow(() -> new NoEncontradoException(CodigoError.RIFA_NO_ENCONTRADA,
                        "No existe la rifa " + rifaId));

        ReclamoRifa reclamo = reclamoRifaRepository.buscarBloqueado(reclamoId)
                .orElseThrow(() -> new NoEncontradoException(CodigoError.RECLAMO_NO_ENCONTRADO,
                        "No existe el reclamo " + reclamoId));

        if (reclamo.getEstado() != EstadoReclamo.PENDIENTE) {
            throw new ConflictoException(CodigoError.RECLAMO_YA_RESUELTO,
                    "El reclamo " + reclamoId + " ya fue resuelto con estado " + reclamo.getEstado());
        }

        EstadoReclamo nuevoEstado = request.decision().estadoResultante();
        String resolucionLimpia = request.resolucion().strip();
        reclamo.setEstado(nuevoEstado);
        reclamo.setResolucion(resolucionLimpia);
        reclamo.setResueltoEn(Instant.now());
        reclamo.setResueltoPor(resolutor.getUsername());
        reclamo = reclamoRifaRepository.saveAndFlush(reclamo);

        boolean rifaSalioDeRevision = false;
        if (!reclamoRifaRepository.existsByRifaIdAndEstado(rifaId, EstadoReclamo.PENDIENTE)
                && rifa.getEstado() == EstadoRifa.EN_REVISION) {
            rifa.setEstado(EstadoRifa.SORTEADA);
            rifa = rifaRepository.saveAndFlush(rifa);
            rifaSalioDeRevision = true;
        }

        Map<String, Object> detalleAuditoria = new LinkedHashMap<>();
        detalleAuditoria.put("rifaId", rifaId);
        detalleAuditoria.put("decision", request.decision().name());
        detalleAuditoria.put("estado", nuevoEstado.name());
        detalleAuditoria.put("resolucion", resolucionLimpia);
        detalleAuditoria.put("rifaEstadoFinal", rifa.getEstado().name());
        auditoriaService.registrar(resolutorId, AccionAuditoria.LAUDO, ENTIDAD_AUDITORIA,
                String.valueOf(reclamoId), detalleAuditoria);

        log.info("Reclamo {} de la rifa {} resuelto con decisión {} ({}). Estado actual de rifa: {}",
                reclamoId, rifaId, request.decision(), nuevoEstado, rifa.getEstado());

        Long torneoId = rifa.getTorneo() != null ? rifa.getTorneo().getId() : null;
        eventPublisher.publishEvent(new ReclamoRifaResuelto(
                reclamo.getId(), rifaId, torneoId, request.decision(), nuevoEstado, resolucionLimpia,
                rifaSalioDeRevision));

        return ReclamoAdminDetalle.de(reclamo);
    }

    private static void validarRequest(ResolverReclamoRequest request) {
        List<ErrorCampo> errores = new ArrayList<>();
        if (request == null || request.decision() == null) {
            errores.add(new ErrorCampo("decision", ErrorCampo.Codigo.REQUERIDO, "Falta la decisión"));
        }
        if (request == null || request.resolucion() == null || request.resolucion().isBlank()) {
            errores.add(new ErrorCampo("resolucion", ErrorCampo.Codigo.REQUERIDO, "Falta la resolución"));
        }
        if (!errores.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION, "Hay campos inválidos")
                    .con("errores", errores);
        }
    }
}

