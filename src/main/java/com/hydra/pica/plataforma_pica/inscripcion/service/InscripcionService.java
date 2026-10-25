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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Borrador de inscripción individual (PICA-208): crear, ver y cancelar. El usuario siempre sale de la
 * sesión; el servicio decide si puede inscribirse y quién ve cada inscripción.
 *
 * Los borradores y las canceladas no cuentan como inscripción activa, así que no impiden crear otro
 * borrador. Que haya una sola activa por usuario y torneo lo asegura el índice parcial de V14, que
 * corta la carrera cuando un borrador pasa a pendiente de pago (confirmar, ticket siguiente).
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
