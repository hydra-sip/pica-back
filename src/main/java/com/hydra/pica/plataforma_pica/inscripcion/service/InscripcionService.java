package com.hydra.pica.plataforma_pica.inscripcion.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.hydra.pica.plataforma_pica.billetera.domain.TipoMovimiento;
import com.hydra.pica.plataforma_pica.billetera.service.CobroService;
import com.hydra.pica.plataforma_pica.billetera.service.SolicitudCobro;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.common.error.ProhibidoException;
import com.hydra.pica.plataforma_pica.equipo.domain.Equipo;
import com.hydra.pica.plataforma_pica.equipo.domain.Nomina;
import com.hydra.pica.plataforma_pica.equipo.domain.NominaId;
import com.hydra.pica.plataforma_pica.equipo.domain.UsuarioEquipo;
import com.hydra.pica.plataforma_pica.equipo.domain.UsuarioEquipoId;
import com.hydra.pica.plataforma_pica.equipo.repository.EquipoRepository;
import com.hydra.pica.plataforma_pica.equipo.repository.NominaRepository;
import com.hydra.pica.plataforma_pica.equipo.repository.UsuarioEquipoRepository;
import com.hydra.pica.plataforma_pica.inscripcion.domain.EstadoInscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.domain.Inscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.domain.Reemplazo;
import com.hydra.pica.plataforma_pica.inscripcion.dto.InscripcionDetalle;
import com.hydra.pica.plataforma_pica.inscripcion.dto.InscripcionEquipoRequest;
import com.hydra.pica.plataforma_pica.inscripcion.dto.InscripcionResumen;
import com.hydra.pica.plataforma_pica.inscripcion.dto.ReemplazoDetalle;
import com.hydra.pica.plataforma_pica.inscripcion.dto.ReemplazoRequest;
import com.hydra.pica.plataforma_pica.inscripcion.dto.ResumenPago;
import com.hydra.pica.plataforma_pica.inscripcion.repository.InscripcionRepository;
import com.hydra.pica.plataforma_pica.inscripcion.repository.ReemplazoRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.TipoParticipante;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.dto.UsuarioPublico;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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

    private static final String UQ_ACTIVA = "uq_inscripcion_activa_torneo_usuario";
    private static final String UQ_EQUIPO = "uq_inscripcion_torneo_equipo";

    private static final List<EstadoInscripcion> ACTIVAS =
            List.of(EstadoInscripcion.PENDIENTE_PAGO, EstadoInscripcion.CONFIRMADA);

    private final InscripcionRepository inscripcionRepository;
    private final TorneoRepository torneoRepository;
    private final UsuarioRepository usuarioRepository;
    private final CobroService cobroService;
    private final EquipoRepository equipoRepository;
    private final NominaRepository nominaRepository;
    private final UsuarioEquipoRepository usuarioEquipoRepository;
    private final ReemplazoRepository reemplazoRepository;

    /**
     * Crea el borrador. En un torneo individual se inscribe el usuario y no lleva cuerpo; en uno por equipos
     * el cuerpo trae el equipo y la nómina, y el usuario es el capitán.
     */
    @Transactional
    public InscripcionDetalle crear(Long usuarioId, Long torneoId, InscripcionEquipoRequest request) {
        Torneo torneo = torneoRepository.findByIdAndEliminadoEnIsNull(torneoId)
                .orElseThrow(() -> new NoEncontradoException(CodigoError.TORNEO_NO_ENCONTRADO, "No existe el torneo " + torneoId));
        Usuario usuario = usuarioRepository.findById(usuarioId)
                .orElseThrow(() -> new NoEncontradoException(CodigoError.USUARIO_NO_ENCONTRADO, "No existe el usuario " + usuarioId));
        validarUsuario(usuario);
        validarTorneoAbierto(torneo);
        validarQueNoOrganiza(torneo, usuarioId);
        // no hay un índice que asegure un borrador por usuario (en uno de equipo el capitán está en el equipo y
        // no en la inscripción), así que dos pedidos simultáneos se serializan por la fila del torneo
        torneoBloqueado(torneoId);
        exigirSinBorrador(torneoId, usuarioId);
        return torneo.getTipoParticipante() == TipoParticipante.EQUIPO
                ? crearDeEquipo(usuario, torneo, request)
                : crearIndividual(usuario, torneo, request);
    }

    // quien se inscribe tiene un solo borrador por torneo (el suyo o, si es capitán, el de cualquiera de sus
    // equipos): si ya hay uno, lo retoma en vez de abrir otro
    private void exigirSinBorrador(Long torneoId, Long usuarioId) {
        List<Long> borradores = inscripcionRepository.borradoresDe(torneoId, usuarioId);
        if (!borradores.isEmpty()) {
            throw new ConflictoException(CodigoError.INSCRIPCION_DUPLICADA, "Ya tenés el borrador " + borradores.get(0)
                    + " en el torneo " + torneoId + ": retomalo o cancelalo antes de crear otro");
        }
    }

    private InscripcionDetalle crearIndividual(Usuario usuario, Torneo torneo, InscripcionEquipoRequest request) {
        if (request != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION,
                    "Un torneo individual no lleva equipo ni nómina");
        }
        if (inscripcionRepository.existsByTorneoIdAndUsuarioIdAndEstadoIn(torneo.getId(), usuario.getId(), ACTIVAS)) {
            throw new ConflictoException(CodigoError.INSCRIPCION_DUPLICADA,
                    "Ya tenés una inscripción activa en el torneo " + torneo.getId());
        }

        Inscripcion inscripcion = new Inscripcion();
        inscripcion.setTorneo(torneo);
        inscripcion.setUsuario(usuario);
        return detalleDe(inscripcionRepository.saveAndFlush(inscripcion));
    }

    private InscripcionDetalle crearDeEquipo(Usuario capitan, Torneo torneo, InscripcionEquipoRequest request) {
        Inscripcion inscripcion = new Inscripcion();
        inscripcion.setTorneo(torneo);
        return detalleDe(guardarEquipoYNomina(inscripcion, capitan, request));
    }

    // Valida todo antes de escribir (el equipo, y que cada jugador exista, esté activo y sin bloqueo y no figure
    // en otra inscripción activa) y recién después reemplaza el equipo y la nómina de la inscripción, nueva o ya
    // guardada.
    private Inscripcion guardarEquipoYNomina(Inscripcion inscripcion, Usuario capitan, InscripcionEquipoRequest request) {
        Torneo torneo = inscripcion.getTorneo();
        Long id = inscripcion.getId(); // nulo si es nueva
        validarPedidoDeEquipo(request);
        Equipo existente = request.equipoId() == null ? null : equipoDelCapitan(capitan, request.equipoId());
        if (existente != null) {
            validarEquipoSinInscripcion(torneo, existente, id);
        }
        List<Usuario> jugadores = jugadoresDe(capitan, request.nomina());
        validarJugadoresLibres(torneo, jugadores, id);

        if (id != null) {
            // la FK compuesta de la nómina ata cada fila al equipo de la inscripción: primero se borra la
            // nómina vieja y recién después se cambia el equipo
            nominaRepository.deleteAll(nominaRepository.findAllByIdInscripcionId(id));
            nominaRepository.flush();
        }
        Equipo equipo = existente != null ? existente : crearEquipo(capitan, request.nombreEquipoNuevo());
        sumarAlEquipo(equipo, jugadores);
        inscripcion.setEquipo(equipo);
        Inscripcion guardada = guardarTraduciendoDuplicada(inscripcion);
        guardarNomina(guardada, equipo, jugadores);
        return guardada;
    }

    // Dos pedidos simultáneos del mismo equipo pasan el chequeo y uno pierde contra la clave única de V9.
    private Inscripcion guardarTraduciendoDuplicada(Inscripcion inscripcion) {
        try {
            return inscripcionRepository.saveAndFlush(inscripcion);
        } catch (DataIntegrityViolationException e) {
            if (UQ_EQUIPO.equals(nombreDeConstraint(e))) {
                throw new ConflictoException(CodigoError.INSCRIPCION_DUPLICADA,
                        "El equipo " + inscripcion.getEquipo().getId() + " ya tiene una inscripción en el torneo "
                                + inscripcion.getTorneo().getId());
            }
            throw e;
        }
    }

    // La clave única de torneo y equipo de V9 deja una sola inscripción del equipo por torneo, en cualquier
    // estado: si ya hay otra, se edita esa. {@code editada}: la que se está editando (nulo si es nueva), que no cuenta.
    private void validarEquipoSinInscripcion(Torneo torneo, Equipo equipo, Long editada) {
        inscripcionRepository.findByTorneoIdAndEquipoId(torneo.getId(), equipo.getId())
                .filter(otra -> !otra.getId().equals(editada))
                .ifPresent(otra -> {
                    throw new ConflictoException(CodigoError.INSCRIPCION_DUPLICADA, "El equipo " + equipo.getId()
                            + " ya tiene la inscripción " + otra.getId() + " en el torneo (" + otra.getEstado() + ")");
                });
    }

    // un jugador figura en una sola inscripción activa por torneo: pendiente de pago o confirmada
    private void validarJugadoresLibres(Torneo torneo, List<Usuario> jugadores, Long editada) {
        for (Usuario jugador : jugadores) {
            if (nominaRepository.figuraEnOtraInscripcion(torneo.getId(), jugador.getId(), ACTIVAS, editada)) {
                throw new ConflictoException(CodigoError.JUGADOR_YA_INSCRIPTO,
                        "El jugador " + jugador.getId() + " ya figura en otra inscripción activa del torneo " + torneo.getId());
            }
        }
    }

    // exactamente uno de equipoId o nombreEquipoNuevo
    private void validarPedidoDeEquipo(InscripcionEquipoRequest request) {
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION,
                    "Un torneo por equipos pide el equipo y la nómina");
        }
        if ((request.equipoId() == null) == (request.nombreEquipoNuevo() == null)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION,
                    "Hay que mandar el equipoId de uno de tus equipos o el nombre de uno nuevo, no los dos");
        }
        if (request.nombreEquipoNuevo() != null && request.nombreEquipoNuevo().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION, "El equipo nuevo necesita un nombre");
        }
        if (request.nomina() != null && request.nomina().stream().anyMatch(Objects::isNull)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION, "La nómina tiene un jugador sin id");
        }
    }

    private Equipo equipoDelCapitan(Usuario capitan, Long equipoId) {
        return equipoRepository.findByIdAndCapitanIdAndEliminadoEnIsNull(equipoId, capitan.getId())
                .orElseThrow(() -> new NoEncontradoException(CodigoError.EQUIPO_NO_ENCONTRADO,
                        "No existe el equipo " + equipoId + " entre los tuyos"));
    }

    // el capitán va primero y siempre; los repetidos cuentan una vez
    private List<Usuario> jugadoresDe(Usuario capitan, List<Long> nomina) {
        Set<Long> ids = new LinkedHashSet<>();
        ids.add(capitan.getId());
        if (nomina != null) {
            ids.addAll(nomina);
        }
        List<Usuario> jugadores = new ArrayList<>();
        for (Long id : ids) {
            Usuario jugador = id.equals(capitan.getId()) ? capitan : usuarioRepository.findById(id)
                    .orElseThrow(() -> new NoEncontradoException(CodigoError.USUARIO_NO_ENCONTRADO, "No existe el usuario " + id));
            validarUsuario(jugador);
            jugadores.add(jugador);
        }
        return jugadores;
    }

    // el equipo nuevo nace con el capitán como capitán; su pertenencia la suma sumarAlEquipo
    private Equipo crearEquipo(Usuario capitan, String nombre) {
        Equipo equipo = new Equipo();
        equipo.setCapitan(capitan);
        equipo.setNombre(nombre.strip());
        return equipoRepository.save(equipo);
    }

    // los que todavía no son integrantes se suman; un integrante dado de baja que se vuelve a cargar se reactiva
    private void sumarAlEquipo(Equipo equipo, List<Usuario> jugadores) {
        for (Usuario jugador : jugadores) {
            UsuarioEquipoId id = new UsuarioEquipoId(jugador.getId(), equipo.getId());
            usuarioEquipoRepository.findById(id).ifPresentOrElse(
                    integrante -> integrante.setEliminadoEn(null),
                    () -> usuarioEquipoRepository.save(new UsuarioEquipo(jugador, equipo)));
        }
        usuarioEquipoRepository.flush();
    }

    private void guardarNomina(Inscripcion inscripcion, Equipo equipo, List<Usuario> jugadores) {
        for (Usuario jugador : jugadores) {
            nominaRepository.save(new Nomina(jugador.getId(), equipo.getId(), inscripcion.getId()));
        }
        nominaRepository.flush();
    }

    // los de equipo llevan además el equipo y la nómina
    private InscripcionDetalle detalleDe(Inscripcion inscripcion) {
        if (!inscripcion.esDeEquipo()) {
            return InscripcionDetalle.de(inscripcion);
        }
        List<UsuarioPublico> nomina = nominaRepository.jugadoresDe(inscripcion.getId()).stream()
                .map(UsuarioPublico::desde).toList();
        return InscripcionDetalle.de(inscripcion, nomina);
    }

    /**
     * La ven quien se inscribió, el organizador del torneo y los jugadores de la nómina; para cualquier otro
     * no existe.
     */
    @Transactional(readOnly = true)
    public InscripcionDetalle detalle(Long usuarioId, Long id) {
        Inscripcion inscripcion = buscar(id);
        boolean organiza = inscripcion.getTorneo().getOrganizador().getId().equals(usuarioId);
        if (!organiza && !esDe(inscripcion, usuarioId) && !figuraEnLaNomina(inscripcion, usuarioId)) {
            throw noEncontrada(id);
        }
        return detalleDe(inscripcion);
    }

    /**
     * La cancelación es una baja lógica: la inscripción queda en CANCELADA. Bloquea la fila del torneo antes de
     * leer la inscripción, igual que {@link #pagar}: así no cancela una que otro pedido acaba de pagar.
     */
    @Transactional
    public void cancelar(Long usuarioId, Long id) {
        Long torneoId = inscripcionRepository.torneoIdDeInscripcionPropia(id, usuarioId)
                .orElseThrow(() -> noEncontrada(id));
        torneoBloqueado(torneoId);
        Inscripcion inscripcion = buscar(id);
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
        exigirBorrador(id, inscripcion.getEstado());
        if (!reglamentoAceptado) {
            throw new ConflictoException(CodigoError.REGLAMENTO_NO_ACEPTADO, "Hay que aceptar el reglamento para confirmar");
        }
        Long torneoId = inscripcion.getTorneo().getId();
        boolean gratis = inscripcion.getTorneo().getValorInscripcion().signum() == 0;
        // Se bloquea la fila del torneo en dos casos: uno gratis confirma acá, así que el cupo se chequea en
        // firme; y uno de equipo, para que dos equipos que comparten un jugador no confirmen a la vez y lo
        // dejen en dos inscripciones activas. En uno pago e individual el cupo es un aviso temprano: el chequeo
        // en firme es el del pago.
        boolean bloquea = gratis || inscripcion.esDeEquipo();
        Torneo torneo = bloquea ? torneoBloqueado(torneoId) : inscripcion.getTorneo();
        if (bloquea) {
            // el estado de arriba se leyó antes de esperar el bloqueo: otro pedido pudo confirmarla mientras tanto
            exigirBorrador(id, inscripcionRepository.estadoDe(id).orElseThrow(() -> noEncontrada(id)));
        }
        validarUsuario(inscripcion.titular());
        validarTorneoAbierto(torneo);
        if (inscripcion.esDeEquipo()) {
            validarNomina(inscripcion, torneo);
        } else if (inscripcionRepository.existsByTorneoIdAndUsuarioIdAndEstadoIn(torneoId, usuarioId, ACTIVAS)) {
            throw new ConflictoException(CodigoError.INSCRIPCION_DUPLICADA,
                    "Ya tenés una inscripción activa en el torneo " + torneoId);
        }
        validarCupo(torneo);
        inscripcion.setEstado(gratis ? EstadoInscripcion.CONFIRMADA : EstadoInscripcion.PENDIENTE_PAGO);
        inscripcion.setReglamentoAceptadoEn(Instant.now());
        try {
            return detalleDe(inscripcionRepository.saveAndFlush(inscripcion));
        } catch (DataIntegrityViolationException e) {
            // otra confirmación individual del mismo usuario ganó la carrera entre el chequeo de arriba y este flush
            if (UQ_ACTIVA.equals(nombreDeConstraint(e))) {
                throw new ConflictoException(CodigoError.INSCRIPCION_DUPLICADA,
                        "Ya tenés una inscripción activa en el torneo " + torneoId);
            }
            throw e;
        }
    }

    private void exigirBorrador(Long id, EstadoInscripcion estado) {
        if (estado != EstadoInscripcion.BORRADOR) {
            throw new ConflictoException(CodigoError.INSCRIPCION_NO_EDITABLE,
                    "La inscripción " + id + " está " + estado + ": solo se confirma en BORRADOR");
        }
    }

    // El tamaño de la nómina se valida acá y no en el borrador, que puede estar incompleto. El capitán cuenta
    // dentro de la nómina, y cada jugador tiene que seguir activo, sin bloqueo y sin otra inscripción activa.
    private void validarNomina(Inscripcion inscripcion, Torneo torneo) {
        List<Usuario> jugadores = nominaRepository.jugadoresDe(inscripcion.getId());
        if (jugadores.size() < torneo.getTamanioEquipo() || jugadores.size() > torneo.getNominaMaxima()) {
            throw new ConflictoException(CodigoError.NOMINA_INVALIDA, "La nómina tiene " + jugadores.size()
                    + " jugadores y el torneo pide entre " + torneo.getTamanioEquipo() + " y " + torneo.getNominaMaxima());
        }
        Long capitanId = inscripcion.titular().getId();
        if (jugadores.stream().noneMatch(jugador -> jugador.getId().equals(capitanId))) {
            throw new ConflictoException(CodigoError.NOMINA_INVALIDA, "El capitán tiene que figurar en la nómina");
        }
        jugadores.forEach(this::validarUsuario);
        validarJugadoresLibres(torneo, jugadores, inscripcion.getId());
    }

    private static String nombreDeConstraint(DataIntegrityViolationException e) {
        Throwable causa = e;
        while (causa != null && !(causa instanceof ConstraintViolationException)) {
            causa = causa.getCause();
        }
        return causa == null ? null : ((ConstraintViolationException) causa).getConstraintName();
    }

    /**
     * Reemplaza el equipo y la nómina del borrador de un torneo por equipos. Solo el capitán, y solo en
     * BORRADOR. Se vuelven a chequear las mismas reglas que al crear. Bloquea la fila del torneo antes de
     * leer la inscripción: si no, pisaría el estado de una confirmación simultánea y cambiaría la nómina que
     * ella validó.
     */
    @Transactional
    public InscripcionDetalle editar(Long usuarioId, Long id, InscripcionEquipoRequest request) {
        Long torneoId = inscripcionRepository.torneoIdDeInscripcionPropia(id, usuarioId)
                .orElseThrow(() -> noEncontrada(id));
        torneoBloqueado(torneoId);
        Inscripcion inscripcion = buscar(id);
        if (!inscripcion.esDeEquipo()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION,
                    "La inscripción individual no tiene equipo ni nómina");
        }
        if (inscripcion.getEstado() != EstadoInscripcion.BORRADOR) {
            throw new ConflictoException(CodigoError.INSCRIPCION_NO_EDITABLE,
                    "La inscripción " + id + " está " + inscripcion.getEstado() + ": solo se edita en BORRADOR");
        }
        Usuario capitan = inscripcion.getEquipo().getCapitan();
        validarUsuario(capitan);
        validarTorneoAbierto(inscripcion.getTorneo());
        validarQueNoOrganiza(inscripcion.getTorneo(), capitan.getId());
        return detalleDe(guardarEquipoYNomina(inscripcion, capitan, request));
    }

    /** Lo que va a pagar el participante o el capitán; solo se ve mientras la inscripción está pendiente de pago. */
    @Transactional(readOnly = true)
    public ResumenPago resumenPago(Long usuarioId, Long id) {
        Inscripcion inscripcion = buscar(id);
        if (!esDe(inscripcion, usuarioId)) {
            throw noEncontrada(id);
        }
        if (inscripcion.getEstado() != EstadoInscripcion.PENDIENTE_PAGO) {
            throw new ConflictoException(CodigoError.INSCRIPCION_NO_EDITABLE,
                    "La inscripción " + id + " está " + inscripcion.getEstado() + ": el resumen es de una PENDIENTE_PAGO");
        }
        return ResumenPago.de(inscripcion, confirmadas(inscripcion.getTorneo().getId()));
    }

    /**
     * Cobra el valor de la inscripción por la interfaz de cobro y la deja confirmada. Primero bloquea la
     * fila del torneo y recién después lee la inscripción, así un pago simultáneo de la misma inscripción
     * o por el último lugar ve lo que dejó el anterior.
     *
     * <p>El bloqueo del torneo dura todo el cobro. Con {@code CobroSimulado} no se nota; con una pasarela
     * lenta, los pagos del mismo torneo esperan detrás del que está cobrando. Hay que revisarlo al
     * integrar la pasarela (billetera virtual, HU-602).
     */
    @Transactional
    public InscripcionDetalle pagar(Long usuarioId, Long id) {
        Long torneoId = inscripcionRepository.torneoIdDeInscripcionPropia(id, usuarioId)
                .orElseThrow(() -> noEncontrada(id));
        Torneo torneo = torneoBloqueado(torneoId);
        Inscripcion inscripcion = buscar(id);
        if (inscripcion.getEstado() == EstadoInscripcion.CONFIRMADA) {
            return detalleDe(inscripcion);
        }
        if (inscripcion.getEstado() != EstadoInscripcion.PENDIENTE_PAGO) {
            throw new ConflictoException(CodigoError.INSCRIPCION_NO_EDITABLE,
                    "La inscripción " + id + " está " + inscripcion.getEstado() + ": solo se paga en PENDIENTE_PAGO");
        }
        validarUsuario(usuarioRepository.findById(usuarioId)
                .orElseThrow(() -> new NoEncontradoException(CodigoError.USUARIO_NO_ENCONTRADO, "No existe el usuario " + usuarioId)));
        validarTorneoAbierto(torneo);
        validarCupo(torneo);
        // un torneo sin costo no pasa por el cobro: V9 exige monto > 0
        if (torneo.getValorInscripcion().signum() > 0) {
            cobroService.cobrar(new SolicitudCobro(usuarioId, TipoMovimiento.INSCRIPCION, torneo.getValorInscripcion(),
                    torneoId, id, "inscripcion-" + id, "Inscripción al torneo " + torneo.getNombre()));
        }
        inscripcion.setEstado(EstadoInscripcion.CONFIRMADA);
        return detalleDe(inscripcionRepository.saveAndFlush(inscripcion));
    }

    /** Las que armó el usuario, la más nueva primero; el orden no se elige desde afuera. */
    @Transactional(readOnly = true)
    public Page<InscripcionResumen> listarMias(Long usuarioId, Long torneoId, Pageable pageable) {
        Pageable masNuevaPrimero = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(Sort.Direction.DESC, "id"));
        return inscripcionRepository.buscarPropias(usuarioId, torneoId, masNuevaPrimero).map(InscripcionResumen::de);
    }

    /**
     * Vuelve una pendiente de pago a borrador: se pierde la aceptación del reglamento y el equipo y la nómina
     * quedan. Igual que {@link #pagar}, primero bloquea la fila del torneo y recién después lee la inscripción:
     * un pago simultáneo no puede cobrar y que después se lo pise con BORRADOR.
     */
    @Transactional
    public InscripcionDetalle reabrir(Long usuarioId, Long id) {
        Long torneoId = inscripcionRepository.torneoIdDeInscripcionPropia(id, usuarioId)
                .orElseThrow(() -> noEncontrada(id));
        torneoBloqueado(torneoId);
        Inscripcion inscripcion = buscar(id);
        if (inscripcion.getEstado() != EstadoInscripcion.PENDIENTE_PAGO) {
            throw new ConflictoException(CodigoError.INSCRIPCION_NO_EDITABLE,
                    "La inscripción " + id + " está " + inscripcion.getEstado() + ": solo se reabre en PENDIENTE_PAGO");
        }
        inscripcion.setEstado(EstadoInscripcion.BORRADOR);
        inscripcion.setReglamentoAceptadoEn(null);
        return detalleDe(inscripcionRepository.saveAndFlush(inscripcion));
    }

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
                .orElseThrow(() -> noEncontrada(id));
        Torneo torneo = torneoBloqueado(torneoId);
        Inscripcion inscripcion = buscar(id);
        if (!inscripcion.esDeEquipo()) {
            throw new ConflictoException(CodigoError.REEMPLAZO_INVALIDO,
                    "Una inscripción individual no tiene nómina para reemplazar");
        }
        if (inscripcion.getEstado() != EstadoInscripcion.CONFIRMADA) {
            throw new ConflictoException(CodigoError.INSCRIPCION_NO_EDITABLE,
                    "La inscripción " + id + " está " + inscripcion.getEstado() + ": solo se reemplaza en CONFIRMADA");
        }
        validarTorneoAbierto(torneo);

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
        validarUsuario(entra);
        validarQueNoOrganiza(torneo, entra.getId());
        if (nominaRepository.figuraEnOtraInscripcion(torneoId, entra.getId(), ACTIVAS, id)) {
            throw new ConflictoException(CodigoError.JUGADOR_YA_INSCRIPTO,
                    "El jugador " + entra.getId() + " ya figura en otra inscripción activa del torneo " + torneoId);
        }

        Equipo equipo = inscripcion.getEquipo();
        sumarAlEquipo(equipo, List.of(entra));
        nominaRepository.deleteById(new NominaId(sale.getId(), equipo.getId(), id));
        nominaRepository.flush();
        nominaRepository.saveAndFlush(new Nomina(entra.getId(), equipo.getId(), id));
        reemplazoRepository.saveAndFlush(new Reemplazo(inscripcion, equipo, sale, entra, inscripcion.titular()));
        return detalleDe(inscripcion);
    }

    @Transactional(readOnly = true)
    public List<ReemplazoDetalle> reemplazos(Long usuarioId, Long id) {
        Inscripcion inscripcion = buscar(id);
        boolean organiza = inscripcion.getTorneo().getOrganizador().getId().equals(usuarioId);
        if (!organiza && !esDe(inscripcion, usuarioId) && !figuraEnLaNomina(inscripcion, usuarioId)) {
            throw noEncontrada(id);
        }
        return reemplazoRepository.findAllByInscripcionIdOrderByCreadoEnAscIdAsc(id).stream()
                .map(ReemplazoDetalle::de)
                .toList();
    }

    private Inscripcion buscar(Long id) {
        return inscripcionRepository.findById(id).orElseThrow(() -> noEncontrada(id));
    }

    private boolean figuraEnLaNomina(Inscripcion inscripcion, Long usuarioId) {
        return inscripcion.esDeEquipo()
                && nominaRepository.existsByIdInscripcionIdAndIdUsuarioId(inscripcion.getId(), usuarioId);
    }

    // el dueño: el participante de la individual, o el capitán del equipo de la de equipo
    private boolean esDe(Inscripcion inscripcion, Long usuarioId) {
        Usuario titular = inscripcion.titular();
        return titular != null && titular.getId().equals(usuarioId);
    }

    private NoEncontradoException noEncontrada(Long id) {
        return new NoEncontradoException(CodigoError.INSCRIPCION_NO_ENCONTRADA, "No existe la inscripción " + id);
    }

    private void validarUsuario(Usuario usuario) {
        switch (usuario.getEstado()) {
            case ACTIVO -> { }
            case BLOQUEADO -> throw new ProhibidoException(CodigoError.USUARIO_BLOQUEADO,
                    "El usuario " + usuario.getId() + " está bloqueado");
            default -> throw new ConflictoException(CodigoError.USUARIO_NO_ACTIVO,
                    "El usuario " + usuario.getId() + " no está activo");
        }
    }

    private void validarTorneoAbierto(Torneo torneo) {
        if (torneo.getEliminadoEn() != null) {
            throw new NoEncontradoException(CodigoError.TORNEO_NO_ENCONTRADO, "No existe el torneo " + torneo.getId());
        }
        if (torneo.getEstado() != EstadoTorneo.INSCRIPCION_ABIERTA || !torneo.getCierreInscripcionEn().isAfter(Instant.now())) {
            throw new ConflictoException(CodigoError.INSCRIPCION_NO_ABIERTA,
                    "El torneo " + torneo.getId() + " no tiene la inscripción abierta");
        }
    }

    private Torneo torneoBloqueado(Long torneoId) {
        return torneoRepository.buscarBloqueado(torneoId)
                .orElseThrow(() -> new NoEncontradoException(CodigoError.TORNEO_NO_ENCONTRADO, "No existe el torneo " + torneoId));
    }

    // solo las confirmadas ocupan cupo
    private long confirmadas(Long torneoId) {
        return inscripcionRepository.countByTorneoIdAndEstado(torneoId, EstadoInscripcion.CONFIRMADA);
    }

    private void validarCupo(Torneo torneo) {
        if (confirmadas(torneo.getId()) >= torneo.getCupoMaximo()) {
            throw new ConflictoException(CodigoError.CUPO_COMPLETO, "El torneo " + torneo.getId() + " ya no tiene cupo");
        }
    }

    private void validarQueNoOrganiza(Torneo torneo, Long usuarioId) {
        if (torneo.getOrganizador().getId().equals(usuarioId) || torneoRepository.esArbitro(torneo.getId(), usuarioId)) {
            throw new ProhibidoException(CodigoError.INSCRIPCION_NO_PERMITIDA_EN_TORNEO,
                    "El organizador y los árbitros del torneo no se inscriben en él");
        }
    }
}
