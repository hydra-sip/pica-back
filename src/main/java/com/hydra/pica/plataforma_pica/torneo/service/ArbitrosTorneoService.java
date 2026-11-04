package com.hydra.pica.plataforma_pica.torneo.service;

import java.util.List;
import java.util.Set;

import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.equipo.repository.NominaRepository;
import com.hydra.pica.plataforma_pica.inscripcion.domain.EstadoInscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.repository.InscripcionRepository;
import com.hydra.pica.plataforma_pica.partido.repository.PartidoRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.ArbitroDesignado;
import com.hydra.pica.plataforma_pica.torneo.domain.ArbitroDesignadoId;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.dto.ArbitroDetalle;
import com.hydra.pica.plataforma_pica.torneo.dto.DesignarArbitroRequest;
import com.hydra.pica.plataforma_pica.torneo.repository.ArbitroDesignadoRepository;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.dto.UsuarioPublico;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRolRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioSpecifications;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Los árbitros del torneo (HU-203, PICA-236): el organizador designa usuarios con rol ARBITRO y elige qué les
 * delega. La designación nace PENDIENTE hasta que el árbitro la acepta (HU-807).
 *
 * Se cambian desde el borrador hasta que el torneo termina, para poder reemplazar a uno que no puede ir. El torneo
 * queda bloqueado mientras tanto, como en el asistente.
 */
@Service
@RequiredArgsConstructor
public class ArbitrosTorneoService {

    static final String ROL_ARBITRO = "ARBITRO";
    private static final Set<EstadoTorneo> TERMINADOS = Set.of(EstadoTorneo.FINALIZADO, EstadoTorneo.CANCELADO);
    private static final List<EstadoInscripcion> ACTIVAS =
            List.of(EstadoInscripcion.PENDIENTE_PAGO, EstadoInscripcion.CONFIRMADA);
    private static final Sort ORDEN = Sort.by("persona.apellidos", "persona.nombres", "id");

    private final TorneosPropios torneosPropios;
    private final ArbitroDesignadoRepository arbitroRepository;
    private final UsuarioRepository usuarioRepository;
    private final UsuarioRolRepository usuarioRolRepository;
    private final InscripcionRepository inscripcionRepository;
    private final NominaRepository nominaRepository;
    private final PartidoRepository partidoRepository;

    /** Usuarios activos con rol ARBITRO, por usuario, nombres o apellidos; sin texto, todos. */
    @Transactional(readOnly = true)
    public Page<UsuarioPublico> buscar(String q, int page, int size) {
        Specification<Usuario> filtro = Specification
                .where(UsuarioSpecifications.conNombreOUsername(q))
                .and(UsuarioSpecifications.conEstado(EstadoUsuario.ACTIVO))
                .and(UsuarioSpecifications.conRolActivo(ROL_ARBITRO));
        return usuarioRepository.findAll(filtro, PageRequest.of(page, size, ORDEN)).map(UsuarioPublico::desde);
    }

    @Transactional(readOnly = true)
    public List<ArbitroDetalle> listar(Long organizadorId, Long id) {
        torneosPropios.buscarPropio(organizadorId, id);
        return arbitroRepository.delTorneo(id).stream().map(ArbitroDetalle::de).toList();
    }

    @Transactional
    public ArbitroDetalle designar(Long organizadorId, Long id, DesignarArbitroRequest request) {
        Torneo torneo = modificable(organizadorId, id);
        Usuario usuario = usuarioRepository.findById(request.usuarioId())
                .orElseThrow(() -> new NoEncontradoException(CodigoError.USUARIO_NO_ENCONTRADO,
                        "No existe el usuario " + request.usuarioId()));
        if (usuario.getEstado() != EstadoUsuario.ACTIVO
                || !usuarioRolRepository.tieneRolActivo(usuario.getId(), ROL_ARBITRO)) {
            throw new ConflictoException(CodigoError.USUARIO_NO_ES_ARBITRO,
                    "El usuario " + usuario.getId() + " no es un árbitro activo");
        }
        if (arbitroRepository.existsById(new ArbitroDesignadoId(id, usuario.getId()))) {
            throw new ConflictoException(CodigoError.ARBITRO_DUPLICADO,
                    "El usuario " + usuario.getId() + " ya es árbitro del torneo " + id);
        }
        validarQueNoJuegaNiOrganiza(torneo, usuario.getId());
        ArbitroDesignado arbitro = arbitroRepository.saveAndFlush(
                new ArbitroDesignado(torneo, usuario, request.permisos()));
        return ArbitroDetalle.de(arbitro);
    }

    /**
     * Deja de figurar y pierde el acceso en el momento (ver {@link Arbitraje}). Con partidos asignados no se quita:
     * 409 ARBITRO_CON_PARTIDOS, primero se reasignan.
     */
    @Transactional
    public void quitar(Long organizadorId, Long id, Long usuarioId) {
        modificable(organizadorId, id);
        ArbitroDesignado arbitro = arbitroRepository.findById(new ArbitroDesignadoId(id, usuarioId))
                .orElseThrow(() -> new NoEncontradoException(CodigoError.ARBITRO_NO_ENCONTRADO,
                        "El usuario " + usuarioId + " no es árbitro del torneo " + id));
        if (partidoRepository.existsByTorneoIdAndArbitroId(id, usuarioId)) {
            throw new ConflictoException(CodigoError.ARBITRO_CON_PARTIDOS,
                    "El usuario " + usuarioId + " tiene partidos asignados en el torneo " + id + ": hay que reasignarlos");
        }
        arbitroRepository.delete(arbitro);
        arbitroRepository.flush();
    }

    private Torneo modificable(Long organizadorId, Long id) {
        Torneo torneo = torneosPropios.buscarBloqueado(organizadorId, id);
        if (TERMINADOS.contains(torneo.getEstado())) {
            throw new ConflictoException(CodigoError.TORNEO_NO_EDITABLE,
                    "El torneo " + id + " está " + torneo.getEstado() + ": ya no se cambian sus árbitros");
        }
        return torneo;
    }

    /** El organizador no se arbitra a sí mismo, y quien juega el torneo no lo arbitra (al revés lo frena Mateo). */
    private void validarQueNoJuegaNiOrganiza(Torneo torneo, Long usuarioId) {
        if (torneo.getOrganizador().getId().equals(usuarioId)) {
            throw new ConflictoException(CodigoError.ARBITRO_NO_PERMITIDO,
                    "El organizador no puede ser árbitro de su torneo");
        }
        if (inscripcionRepository.existsByTorneoIdAndUsuarioIdAndEstadoIn(torneo.getId(), usuarioId, ACTIVAS)
                || nominaRepository.figuraEnOtraInscripcion(torneo.getId(), usuarioId, ACTIVAS, null)) {
            throw new ConflictoException(CodigoError.ARBITRO_NO_PERMITIDO,
                    "El usuario " + usuarioId + " está inscripto en el torneo " + torneo.getId());
        }
    }
}
