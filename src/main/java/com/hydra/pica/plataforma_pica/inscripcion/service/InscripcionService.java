package com.hydra.pica.plataforma_pica.inscripcion.service;

import java.time.Instant;
import java.util.List;

import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.common.error.ProhibidoException;
import com.hydra.pica.plataforma_pica.inscripcion.domain.EstadoInscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.domain.Inscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.dto.InscripcionDetalle;
import com.hydra.pica.plataforma_pica.inscripcion.repository.InscripcionRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.TipoParticipante;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inscripción individual: crear el borrador, verlo, cancelarlo y confirmarlo (PICA-209). El
 * usuario siempre sale de la sesión; el servicio decide si puede inscribirse y quién ve cada inscripción.
 *
 * Los borradores y las canceladas no cuentan como inscripción activa, así que no impiden crear otro
 * borrador. Que haya una sola activa por usuario y torneo lo asegura el índice parcial de V14, que
 * corta la carrera cuando un borrador pasa a pendiente de pago al confirmar.
 */
@Service
@RequiredArgsConstructor
public class InscripcionService {

    private static final List<EstadoInscripcion> ACTIVAS =
            List.of(EstadoInscripcion.PENDIENTE_PAGO, EstadoInscripcion.CONFIRMADA);

    private final InscripcionRepository inscripcionRepository;
    private final TorneoRepository torneoRepository;
    private final UsuarioRepository usuarioRepository;

    @Transactional
    public InscripcionDetalle crear(Long usuarioId, Long torneoId) {
        Torneo torneo = torneoRepository.findByIdAndEliminadoEnIsNull(torneoId)
                .orElseThrow(() -> new NoEncontradoException(CodigoError.TORNEO_NO_ENCONTRADO, "No existe el torneo " + torneoId));
        Usuario usuario = usuarioRepository.findById(usuarioId)
                .orElseThrow(() -> new NoEncontradoException(CodigoError.USUARIO_NO_ENCONTRADO, "No existe el usuario " + usuarioId));
        validarUsuario(usuario);
        validarTorneo(torneo);
        validarQueNoOrganiza(torneo, usuarioId);
        if (inscripcionRepository.existsByTorneoIdAndUsuarioIdAndEstadoIn(torneoId, usuarioId, ACTIVAS)) {
            throw new ConflictoException(CodigoError.INSCRIPCION_DUPLICADA,
                    "Ya tenés una inscripción activa en el torneo " + torneoId);
        }

        Inscripcion inscripcion = new Inscripcion();
        inscripcion.setTorneo(torneo);
        inscripcion.setUsuario(usuario);
        return InscripcionDetalle.de(inscripcionRepository.saveAndFlush(inscripcion));
    }

    /** La ven quien se inscribió y el organizador del torneo; para cualquier otro no existe. */
    @Transactional(readOnly = true)
    public InscripcionDetalle detalle(Long usuarioId, Long id) {
        Inscripcion inscripcion = buscar(id);
        boolean organiza = inscripcion.getTorneo().getOrganizador().getId().equals(usuarioId);
        if (!organiza && !esDe(inscripcion, usuarioId)) {
            throw noEncontrada(id);
        }
        return InscripcionDetalle.de(inscripcion);
    }

    /** La cancelación es una baja lógica: la inscripción queda en CANCELADA. */
    @Transactional
    public void cancelar(Long usuarioId, Long id) {
        Inscripcion inscripcion = buscar(id);
        if (!esDe(inscripcion, usuarioId)) {
            throw noEncontrada(id);
        }
        if (inscripcion.getEstado() != EstadoInscripcion.BORRADOR && inscripcion.getEstado() != EstadoInscripcion.PENDIENTE_PAGO) {
            throw new ConflictoException(CodigoError.INSCRIPCION_NO_EDITABLE,
                    "La inscripción " + id + " está " + inscripcion.getEstado() + ": solo se cancela en BORRADOR o PENDIENTE_PAGO");
        }
        inscripcion.setEstado(EstadoInscripcion.CANCELADA);
    }

    /** Pasa el borrador a pendiente de pago y deja la fecha en que se aceptó el reglamento. */
    @Transactional
    public InscripcionDetalle confirmar(Long usuarioId, Long id, boolean reglamentoAceptado) {
        Inscripcion inscripcion = buscar(id);
        if (!esDe(inscripcion, usuarioId)) {
            throw noEncontrada(id);
        }
        if (inscripcion.getEstado() != EstadoInscripcion.BORRADOR) {
            throw new ConflictoException(CodigoError.INSCRIPCION_NO_EDITABLE,
                    "La inscripción " + id + " está " + inscripcion.getEstado() + ": solo se confirma en BORRADOR");
        }
        if (!reglamentoAceptado) {
            throw new ConflictoException(CodigoError.REGLAMENTO_NO_ACEPTADO, "Hay que aceptar el reglamento para confirmar");
        }
        Long torneoId = inscripcion.getTorneo().getId();
        boolean gratis = inscripcion.getTorneo().getValorInscripcion().signum() == 0;
        // Un torneo gratis confirma acá, así que el cupo se chequea en firme con la fila bloqueada. En uno
        // pago es un aviso temprano: el chequeo en firme es el del pago.
        Torneo torneo = gratis
                ? torneoRepository.buscarBloqueado(torneoId)
                        .orElseThrow(() -> new NoEncontradoException(CodigoError.TORNEO_NO_ENCONTRADO, "No existe el torneo " + torneoId))
                : inscripcion.getTorneo();
        validarUsuario(inscripcion.getUsuario());
        validarTorneo(torneo);
        if (inscripcionRepository.existsByTorneoIdAndUsuarioIdAndEstadoIn(torneoId, usuarioId, ACTIVAS)) {
            throw new ConflictoException(CodigoError.INSCRIPCION_DUPLICADA,
                    "Ya tenés una inscripción activa en el torneo " + torneoId);
        }
        if (inscripcionRepository.countByTorneoIdAndEstado(torneoId, EstadoInscripcion.CONFIRMADA) >= torneo.getCupoMaximo()) {
            throw new ConflictoException(CodigoError.CUPO_COMPLETO, "El torneo " + torneoId + " ya no tiene cupo");
        }
        inscripcion.setEstado(gratis ? EstadoInscripcion.CONFIRMADA : EstadoInscripcion.PENDIENTE_PAGO);
        inscripcion.setReglamentoAceptadoEn(Instant.now());
        try {
            return InscripcionDetalle.de(inscripcionRepository.saveAndFlush(inscripcion));
        } catch (DataIntegrityViolationException e) {
            // otra confirmación del mismo usuario ganó la carrera entre el chequeo de arriba y este flush
            if (e.getMostSpecificCause().getMessage().contains("uq_inscripcion_activa_torneo_usuario")) {
                throw new ConflictoException(CodigoError.INSCRIPCION_DUPLICADA,
                        "Ya tenés una inscripción activa en el torneo " + torneoId);
            }
            throw e;
        }
    }

    private Inscripcion buscar(Long id) {
        return inscripcionRepository.findById(id).orElseThrow(() -> noEncontrada(id));
    }

    private boolean esDe(Inscripcion inscripcion, Long usuarioId) {
        return inscripcion.getUsuario() != null && inscripcion.getUsuario().getId().equals(usuarioId);
    }

    private NoEncontradoException noEncontrada(Long id) {
        return new NoEncontradoException(CodigoError.INSCRIPCION_NO_ENCONTRADA, "No existe la inscripción " + id);
    }

    private void validarUsuario(Usuario usuario) {
        switch (usuario.getEstado()) {
            case ACTIVO -> { }
            case BLOQUEADO -> throw new ProhibidoException(CodigoError.USUARIO_BLOQUEADO, "El usuario está bloqueado");
            default -> throw new ConflictoException(CodigoError.USUARIO_NO_ACTIVO, "El usuario no está activo");
        }
    }

    private void validarTorneo(Torneo torneo) {
        if (torneo.getEliminadoEn() != null) {
            throw new NoEncontradoException(CodigoError.TORNEO_NO_ENCONTRADO, "No existe el torneo " + torneo.getId());
        }
        if (torneo.getEstado() != EstadoTorneo.INSCRIPCION_ABIERTA || !torneo.getCierreInscripcionEn().isAfter(Instant.now())) {
            throw new ConflictoException(CodigoError.INSCRIPCION_NO_ABIERTA,
                    "El torneo " + torneo.getId() + " no tiene la inscripción abierta");
        }
        if (torneo.getTipoParticipante() == TipoParticipante.EQUIPO) {
            throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION,
                    "La inscripción a torneos por equipos no está disponible");
        }
    }

    private void validarQueNoOrganiza(Torneo torneo, Long usuarioId) {
        if (torneo.getOrganizador().getId().equals(usuarioId) || torneoRepository.esArbitro(torneo.getId(), usuarioId)) {
            throw new ProhibidoException(CodigoError.INSCRIPCION_NO_PERMITIDA_EN_TORNEO,
                    "El organizador y los árbitros del torneo no se inscriben en él");
        }
    }
}
