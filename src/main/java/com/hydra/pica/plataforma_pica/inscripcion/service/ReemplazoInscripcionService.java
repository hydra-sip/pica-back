package com.hydra.pica.plataforma_pica.inscripcion.service;

import java.math.BigDecimal;
import java.util.List;

import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.equipo.domain.Equipo;
import com.hydra.pica.plataforma_pica.equipo.domain.Nomina;
import com.hydra.pica.plataforma_pica.equipo.domain.NominaId;
import com.hydra.pica.plataforma_pica.equipo.repository.NominaRepository;
import com.hydra.pica.plataforma_pica.inscripcion.domain.EstadoInscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.domain.Inscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.domain.Reemplazo;
import com.hydra.pica.plataforma_pica.inscripcion.dto.InscripcionDetalle;
import com.hydra.pica.plataforma_pica.inscripcion.dto.ReemplazoDetalle;
import com.hydra.pica.plataforma_pica.inscripcion.dto.ReemplazoRequest;
import com.hydra.pica.plataforma_pica.inscripcion.repository.InscripcionRepository;
import com.hydra.pica.plataforma_pica.inscripcion.repository.ReemplazoRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.dto.UsuarioPublico;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Los reemplazos en la nómina de un equipo ya confirmado: el capitán cambia un jugador por otro mientras la
 * inscripción del torneo siga abierta, y quedan registrados.
 */
@Service
@RequiredArgsConstructor
public class ReemplazoInscripcionService {

    private final InscripcionRepository inscripcionRepository;
    private final UsuarioRepository usuarioRepository;
    private final NominaRepository nominaRepository;
    private final ReemplazoRepository reemplazoRepository;
    private final InscripcionComun comun;

    @Transactional
    public InscripcionDetalle reemplazar(Long usuarioId, Long id, ReemplazoRequest request) {
        if (request == null || request.saleUsuarioId() == null || request.entraUsuarioId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION, "Hay que indicar quién sale y quién entra");
        }
        if (request.saleUsuarioId().equals(request.entraUsuarioId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION,
                    "El usuario que sale y el que entra tienen que ser distintos");
        }

        Long torneoId = inscripcionRepository.torneoIdDeInscripcionPropia(id, usuarioId)
                .orElseThrow(() -> comun.noEncontrada(id));
        Torneo torneo = comun.torneoBloqueado(torneoId);
        Inscripcion inscripcion = comun.buscar(id);
        if (!inscripcion.esDeEquipo()) {
            throw new ConflictoException(CodigoError.REEMPLAZO_INVALIDO,
                    "Una inscripción individual no tiene nómina para reemplazar");
        }
        if (inscripcion.getEstado() != EstadoInscripcion.CONFIRMADA) {
            throw new ConflictoException(CodigoError.INSCRIPCION_NO_EDITABLE,
                    "La inscripción " + id + " está " + inscripcion.getEstado() + ": solo se reemplaza en CONFIRMADA");
        }
        comun.validarTorneoAbierto(torneo);

        Long capitanId = inscripcion.titular().getId();
        Usuario entra = usuarioRepository.findById(request.entraUsuarioId())
                .orElseThrow(() -> new NoEncontradoException(CodigoError.USUARIO_NO_ENCONTRADO,
                        "No existe el usuario " + request.entraUsuarioId()));
        if (request.saleUsuarioId().equals(capitanId)
                || !nominaRepository.existsByIdInscripcionIdAndIdUsuarioId(id, request.saleUsuarioId())
                || nominaRepository.existsByIdInscripcionIdAndIdUsuarioId(id, request.entraUsuarioId())) {
            throw new ConflictoException(CodigoError.REEMPLAZO_INVALIDO,
                    "El jugador no se puede reemplazar en la nómina de la inscripción " + id);
        }

        Usuario sale = usuarioRepository.findById(request.saleUsuarioId())
                .orElseThrow(() -> new NoEncontradoException(CodigoError.USUARIO_NO_ENCONTRADO,
                        "No existe el usuario " + request.saleUsuarioId()));
        comun.validarUsuario(entra);
        comun.validarQueNoOrganiza(torneo, entra.getId());
        if (nominaRepository.figuraEnOtraInscripcion(torneoId, entra.getId(), InscripcionComun.ACTIVAS, id)) {
            throw new ConflictoException(CodigoError.JUGADOR_YA_INSCRIPTO,
                    "El jugador " + entra.getId() + " ya figura en otra inscripción activa del torneo " + torneoId)
                    .con("jugador", UsuarioPublico.desde(entra));
        }

        Equipo equipo = inscripcion.getEquipo();
        comun.sumarAlEquipo(equipo, List.of(entra));
        // el que entra hereda el % del que sale (CR-05); si no había reparto, tampoco lo hay
        BigDecimal pctDelQueSale = nominaRepository.findById(new NominaId(sale.getId(), equipo.getId(), id))
                .map(Nomina::getPctPremio).orElse(null);
        nominaRepository.deleteById(new NominaId(sale.getId(), equipo.getId(), id));
        nominaRepository.flush();
        nominaRepository.saveAndFlush(new Nomina(entra.getId(), equipo.getId(), id, pctDelQueSale));
        reemplazoRepository.saveAndFlush(new Reemplazo(inscripcion, equipo, sale, entra, inscripcion.titular()));
        return comun.detalleDe(inscripcion, usuarioId);
    }

    @Transactional(readOnly = true)
    public List<ReemplazoDetalle> reemplazos(Long usuarioId, Long id) {
        Inscripcion inscripcion = comun.buscar(id);
        boolean organiza = inscripcion.getTorneo().getOrganizador().getId().equals(usuarioId);
        if (!organiza && !comun.esDe(inscripcion, usuarioId) && !comun.figuraEnLaNomina(inscripcion, usuarioId)) {
            throw comun.noEncontrada(id);
        }
        return reemplazoRepository.findAllByInscripcionIdOrderByCreadoEnAscIdAsc(id).stream()
                .map(ReemplazoDetalle::de)
                .toList();
    }
}
