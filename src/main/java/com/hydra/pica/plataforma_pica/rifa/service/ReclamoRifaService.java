package com.hydra.pica.plataforma_pica.rifa.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.ErrorCampo;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.common.error.ProhibidoException;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoReclamo;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.ReclamoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;
import com.hydra.pica.plataforma_pica.rifa.dto.ReclamoRifaDetalle;
import com.hydra.pica.plataforma_pica.rifa.dto.ReclamoRifaRequest;
import com.hydra.pica.plataforma_pica.rifa.event.ReclamoRifaRegistrado;
import com.hydra.pica.plataforma_pica.rifa.repository.ReclamoRifaRepository;
import com.hydra.pica.plataforma_pica.rifa.repository.RifaRepository;
import com.hydra.pica.plataforma_pica.rifa.repository.TicketRepository;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Servicio de reclamos sobre el resultado de rifas (HU-506, T506-1).
 * Permite a compradores disputar el resultado del sorteo dentro de las 48 horas posteriores
 * a su finalización, pasando la rifa a EN_REVISION y notificando al organizador.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReclamoRifaService {

    public static final int HORAS_LIMITE_RECLAMO = 48;

    private final RifaRepository rifaRepository;
    private final TicketRepository ticketRepository;
    private final ReclamoRifaRepository reclamoRifaRepository;
    private final UsuarioRepository usuarioRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public ReclamoRifaDetalle registrarReclamo(Long usuarioId, Long rifaId, ReclamoRifaRequest request) {
        validarRequest(request);

        Usuario usuario = usuarioRepository.findById(usuarioId)
                .orElseThrow(() -> new NoEncontradoException(CodigoError.USUARIO_NO_ENCONTRADO,
                        "No existe el usuario " + usuarioId));

        Rifa rifa = rifaRepository.buscarBloqueado(rifaId)
                .orElseThrow(() -> new NoEncontradoException(CodigoError.RIFA_NO_ENCONTRADA,
                        "No existe la rifa " + rifaId));

        // Criterio 2: Comprobar que solo los compradores pueden reclamar
        if (!ticketRepository.existsByRifaIdAndCompradorId(rifaId, usuarioId)) {
            throw new ProhibidoException(CodigoError.NO_ES_COMPRADOR,
                    "Solo los compradores de la rifa " + rifaId + " pueden presentar reclamos");
        }

        // Validación de estado de la rifa
        if (rifa.getEstado() == EstadoRifa.BORRADOR || rifa.getEstado() == EstadoRifa.EN_VENTA
                || rifa.getEstado() == EstadoRifa.CERRADA) {
            throw new ConflictoException(CodigoError.RIFA_NO_SORTEADA,
                    "La rifa " + rifaId + " está " + rifa.getEstado() + ": aún no ha finalizado su sorteo");
        }
        if (rifa.getEstado() == EstadoRifa.LIQUIDADA || rifa.getEstado() == EstadoRifa.CANCELADA) {
            throw new ConflictoException(CodigoError.PLAZO_RECLAMO_VENCIDO,
                    "La rifa " + rifaId + " está " + rifa.getEstado() + ": no admite reclamos");
        }

        // Criterio 1: Comprobar ventana de 48 horas posteriores a la finalización
        Instant finalizadaEn = rifa.getSemillaObtenidaEn() != null ? rifa.getSemillaObtenidaEn() : rifa.getSorteadaEn();
        if (finalizadaEn == null) {
            throw new ConflictoException(CodigoError.RIFA_NO_SORTEADA,
                    "La rifa " + rifaId + " no registra fecha de finalización");
        }

        Instant ahora = Instant.now();
        Instant plazoLimite = finalizadaEn.plus(HORAS_LIMITE_RECLAMO, ChronoUnit.HOURS);
        if (ahora.isAfter(plazoLimite)) {
            throw new ConflictoException(CodigoError.PLAZO_RECLAMO_VENCIDO,
                    "Venció el plazo de 48 horas para reclamar el resultado de la rifa " + rifaId);
        }

        // Criterio 3: Registrar el reclamo en estado PENDIENTE
        ReclamoRifa reclamo = new ReclamoRifa();
        reclamo.setRifa(rifa);
        reclamo.setUsuario(usuario);
        reclamo.setMotivo(request.motivo().strip());
        reclamo.setEstado(EstadoReclamo.PENDIENTE);
        reclamo.setCreadoEn(ahora);
        reclamo = reclamoRifaRepository.save(reclamo);

        // Criterio 4: Comprobar que la rifa pasa a EN_REVISION
        rifa.setEstado(EstadoRifa.EN_REVISION);
        rifaRepository.save(rifa);

        log.info("Reclamo {} registrado para rifa {}. Estado de rifa actualizado a EN_REVISION",
                reclamo.getId(), rifaId);

        // Notificar al organizador mediante evento de dominio
        eventPublisher.publishEvent(new ReclamoRifaRegistrado(
                rifaId, reclamo.getId(), usuarioId, reclamo.getMotivo()));

        return toDetalle(reclamo);
    }

    @Transactional(readOnly = true)
    public List<ReclamoRifaDetalle> listarPorRifa(Long rifaId) {
        return reclamoRifaRepository.findByRifaIdOrderByCreadoEnDesc(rifaId).stream()
                .map(this::toDetalle)
                .toList();
    }

    private ReclamoRifaDetalle toDetalle(ReclamoRifa reclamo) {
        return new ReclamoRifaDetalle(
                reclamo.getId(),
                reclamo.getRifa().getId(),
                reclamo.getUsuario().getId(),
                reclamo.getMotivo(),
                reclamo.getEstado(),
                reclamo.getResolucion(),
                reclamo.getResueltoEn(),
                reclamo.getResueltoPor(),
                reclamo.getCreadoEn()
        );
    }

    private static void validarRequest(ReclamoRifaRequest request) {
        if (request == null || request.motivo() == null || request.motivo().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION, "Hay campos inválidos")
                    .con("errores", List.of(new ErrorCampo("motivo", ErrorCampo.Codigo.REQUERIDO, "Falta el motivo")));
        }
    }
}
