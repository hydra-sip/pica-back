package com.hydra.pica.plataforma_pica.inscripcion.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.common.error.ProhibidoException;
import com.hydra.pica.plataforma_pica.control.service.BloqueoService;
import com.hydra.pica.plataforma_pica.equipo.domain.Equipo;
import com.hydra.pica.plataforma_pica.equipo.domain.Nomina;
import com.hydra.pica.plataforma_pica.equipo.domain.UsuarioEquipo;
import com.hydra.pica.plataforma_pica.equipo.domain.UsuarioEquipoId;
import com.hydra.pica.plataforma_pica.equipo.repository.NominaRepository;
import com.hydra.pica.plataforma_pica.equipo.repository.UsuarioEquipoRepository;
import com.hydra.pica.plataforma_pica.inscripcion.domain.EstadoInscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.domain.Inscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.domain.MotivoCierre;
import com.hydra.pica.plataforma_pica.inscripcion.dto.InscripcionDetalle;
import com.hydra.pica.plataforma_pica.inscripcion.dto.RepartoIntegrante;
import com.hydra.pica.plataforma_pica.inscripcion.repository.InscripcionRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.repository.ReprogramacionRepository;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.dto.UsuarioPublico;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Lo que comparten {@link InscripcionService}, {@link PagoInscripcionService} y {@link ReemplazoInscripcionService}:
 * cargar la inscripción, saber de quién es, las validaciones del usuario y del torneo, el bloqueo de la fila del
 * torneo, el cupo y el detalle que devuelven.
 */
@Component
@RequiredArgsConstructor
class InscripcionComun {

    /** Las que ocupan al usuario en el torneo: no puede tener otra. */
    static final List<EstadoInscripcion> ACTIVAS =
            List.of(EstadoInscripcion.PENDIENTE_PAGO, EstadoInscripcion.CONFIRMADA);

    private final InscripcionRepository inscripcionRepository;
    private final TorneoRepository torneoRepository;
    private final NominaRepository nominaRepository;
    private final UsuarioEquipoRepository usuarioEquipoRepository;
    private final CierreInscripcion cierreInscripcion;
    private final BloqueoService bloqueoService;
    private final ReprogramacionRepository reprogramacionRepository;

    Inscripcion buscar(Long id) {
        return inscripcionRepository.findById(id).orElseThrow(() -> noEncontrada(id));
    }

    boolean figuraEnLaNomina(Inscripcion inscripcion, Long usuarioId) {
        return inscripcion.esDeEquipo()
                && nominaRepository.existsByIdInscripcionIdAndIdUsuarioId(inscripcion.getId(), usuarioId);
    }

    // el dueño: el participante de la individual, o el capitán del equipo de la de equipo
    boolean esDe(Inscripcion inscripcion, Long usuarioId) {
        Usuario titular = inscripcion.titular();
        return titular != null && titular.getId().equals(usuarioId);
    }

    NoEncontradoException noEncontrada(Long id) {
        return new NoEncontradoException(CodigoError.INSCRIPCION_NO_ENCONTRADA, "No existe la inscripción " + id);
    }

    void validarUsuario(Usuario usuario) {
        if (usuario.getEstado() == EstadoUsuario.ACTIVO) {
            return;
        }
        if (usuario.getEstado() == EstadoUsuario.BLOQUEADO) {
            throw new ProhibidoException(CodigoError.USUARIO_BLOQUEADO,
                    "El usuario " + usuario.getId() + " está bloqueado");
        }
        // pendiente de verificación, o cualquier estado que se agregue después
        throw new ConflictoException(CodigoError.USUARIO_NO_ACTIVO,
                "El usuario " + usuario.getId() + " no está activo");
    }

    void validarTorneoAbierto(Torneo torneo) {
        if (torneo.getEliminadoEn() != null) {
            throw new NoEncontradoException(CodigoError.TORNEO_NO_ENCONTRADO, "No existe el torneo " + torneo.getId());
        }
        if (torneo.getEstado() != EstadoTorneo.INSCRIPCION_ABIERTA || !torneo.getCierreInscripcionEn().isAfter(Instant.now())) {
            throw new ConflictoException(CodigoError.INSCRIPCION_NO_ABIERTA,
                    "El torneo " + torneo.getId() + " no tiene la inscripción abierta");
        }
    }

    /**
     * Mientras su organizador está bloqueado, el torneo no suma inscripciones: no se empieza, no se confirma ni se
     * paga ninguna. Las que ya existían quedan como están y no se mueve plata (HU-703).
     */
    void validarOrganizadorNoBloqueado(Torneo torneo) {
        if (bloqueoService.estaBloqueado(torneo.getOrganizador())) {
            throw new ConflictoException(CodigoError.ORGANIZADOR_BLOQUEADO,
                    "El organizador del torneo " + torneo.getId() + " está bloqueado: la inscripción está suspendida");
        }
    }

    Torneo torneoBloqueado(Long torneoId) {
        return torneoRepository.buscarBloqueado(torneoId)
                .orElseThrow(() -> new NoEncontradoException(CodigoError.TORNEO_NO_ENCONTRADO, "No existe el torneo " + torneoId));
    }

    // solo las confirmadas ocupan cupo
    long confirmadas(Long torneoId) {
        return inscripcionRepository.countByTorneoIdAndEstado(torneoId, EstadoInscripcion.CONFIRMADA);
    }

    void validarCupo(Torneo torneo) {
        if (confirmadas(torneo.getId()) >= torneo.getCupoMaximo()) {
            throw new ConflictoException(CodigoError.CUPO_COMPLETO, "El torneo " + torneo.getId() + " ya no tiene cupo");
        }
    }

    void validarQueNoOrganiza(Torneo torneo, Long usuarioId) {
        if (torneo.getOrganizador().getId().equals(usuarioId) || torneoRepository.esArbitro(torneo.getId(), usuarioId)) {
            throw new ProhibidoException(CodigoError.INSCRIPCION_NO_PERMITIDA_EN_TORNEO,
                    "El organizador y los árbitros del torneo no se inscriben en él");
        }
    }

    // los de equipo llevan además el equipo y la nómina; el rol es el de usuarioId
    InscripcionDetalle detalleDe(Inscripcion inscripcion, Long usuarioId) {
        if (!inscripcion.esDeEquipo()) {
            return InscripcionDetalle.de(inscripcion, null, null, usuarioId);
        }
        List<UsuarioPublico> nomina = nominaRepository.jugadoresDe(inscripcion.getId()).stream()
                .map(UsuarioPublico::desde).toList();
        return InscripcionDetalle.de(inscripcion, nomina, repartoDe(inscripcion, nomina), usuarioId);
    }

    // el % de cada uno, en el orden de la nómina; nulo si la inscripción se liquida con todo al capitán
    private List<RepartoIntegrante> repartoDe(Inscripcion inscripcion, List<UsuarioPublico> nomina) {
        Map<Long, BigDecimal> pcts = new HashMap<>();
        for (Nomina fila : nominaRepository.findAllByIdInscripcionId(inscripcion.getId())) {
            if (fila.getPctPremio() == null) {
                return null;
            }
            pcts.put(fila.getId().getUsuarioId(), fila.getPctPremio());
        }
        return nomina.stream().map(u -> new RepartoIntegrante(u.id(), pcts.get(u.id()))).toList();
    }

    // los que todavía no son integrantes se suman; un integrante dado de baja que se vuelve a cargar se reactiva
    void sumarAlEquipo(Equipo equipo, List<Usuario> jugadores) {
        for (Usuario jugador : jugadores) {
            UsuarioEquipoId id = new UsuarioEquipoId(jugador.getId(), equipo.getId());
            usuarioEquipoRepository.findById(id).ifPresentOrElse(
                    integrante -> integrante.setEliminadoEn(null),
                    () -> usuarioEquipoRepository.save(new UsuarioEquipo(jugador, equipo)));
        }
        usuarioEquipoRepository.flush();
    }

    // HU-206: la confirmación que completa el cupo cierra la inscripción en la misma transacción, con el torneo ya
    // bloqueado; así nadie más llega a pagar. El cierre es automático: no lo hace quien paga. Mientras corre el plazo
    // de una reprogramación (CR-01) no se cierra: los que rechacen la nueva fecha todavía pueden liberar lugares, y
    // al vencer el plazo se cierra si el cupo sigue completo
    void cerrarSiSeCompletoElCupo(Torneo torneo) {
        if (confirmadas(torneo.getId()) >= torneo.getCupoMaximo()
                && !reprogramacionRepository.existsByTorneoIdAndPlazoProcesadoEnIsNull(torneo.getId())) {
            cierreInscripcion.cerrar(torneo, MotivoCierre.CUPO, null);
        }
    }
}
