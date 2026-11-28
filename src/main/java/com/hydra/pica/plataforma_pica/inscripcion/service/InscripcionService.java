package com.hydra.pica.plataforma_pica.inscripcion.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.common.error.NombreDeConstraint;
import com.hydra.pica.plataforma_pica.control.domain.AccionAuditoria;
import com.hydra.pica.plataforma_pica.control.domain.Auditoria;
import com.hydra.pica.plataforma_pica.control.domain.EntidadAuditoria;
import com.hydra.pica.plataforma_pica.control.repository.AuditoriaRepository;
import com.hydra.pica.plataforma_pica.equipo.domain.Equipo;
import com.hydra.pica.plataforma_pica.equipo.domain.Nomina;
import com.hydra.pica.plataforma_pica.equipo.repository.EquipoRepository;
import com.hydra.pica.plataforma_pica.equipo.repository.NominaRepository;
import com.hydra.pica.plataforma_pica.inscripcion.domain.EstadoInscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.domain.Inscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.domain.RepartoPremio;
import com.hydra.pica.plataforma_pica.inscripcion.dto.InscripcionDetalle;
import com.hydra.pica.plataforma_pica.inscripcion.dto.InscripcionEquipoRequest;
import com.hydra.pica.plataforma_pica.inscripcion.dto.InscripcionResumen;
import com.hydra.pica.plataforma_pica.inscripcion.dto.RepartoIntegrante;
import com.hydra.pica.plataforma_pica.inscripcion.repository.InscripcionRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.TipoParticipante;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.dto.UsuarioPublico;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
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
 *
 * <p>El pago está en {@link PagoInscripcionService} y los reemplazos en {@link ReemplazoInscripcionService}.
 */
@Service
@RequiredArgsConstructor
public class InscripcionService {

    private static final String UQ_ACTIVA = "uq_inscripcion_activa_torneo_usuario";
    private static final String UQ_EQUIPO = "uq_inscripcion_torneo_equipo";

    private static final List<EstadoInscripcion> REPARTO_EDITABLE =
            List.of(EstadoInscripcion.BORRADOR, EstadoInscripcion.PENDIENTE_PAGO, EstadoInscripcion.CONFIRMADA);

    private static final List<EstadoInscripcion> ACTIVAS = InscripcionComun.ACTIVAS;

    private final InscripcionRepository inscripcionRepository;
    private final TorneoRepository torneoRepository;
    private final UsuarioRepository usuarioRepository;
    private final EquipoRepository equipoRepository;
    private final NominaRepository nominaRepository;
    private final AuditoriaRepository auditoriaRepository;
    private final InscripcionComun comun;

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
        comun.validarUsuario(usuario);
        comun.validarTorneoAbierto(torneo);
        comun.validarOrganizadorNoBloqueado(torneo);
        comun.validarQueNoOrganiza(torneo, usuarioId);
        // no hay un índice que asegure un borrador por usuario (en uno de equipo el capitán está en el equipo y
        // no en la inscripción), así que dos pedidos simultáneos se serializan por la fila del torneo
        comun.torneoBloqueado(torneoId);
        exigirSinBorrador(torneoId, usuarioId);
        return torneo.getTipoParticipante() == TipoParticipante.EQUIPO
                ? crearDeEquipo(usuario, torneo, request)
                : crearIndividual(usuario, torneo, request);
    }

    // quien se inscribe tiene un solo borrador por torneo (el suyo o, si es capitán, el de cualquiera de sus
    // equipos): si ya hay uno, lo retoma en vez de abrir otro
    /**
     * 409 porque el usuario ya tiene una inscripción activa en el torneo; si se la encuentra, el error trae su id
     * en {@code inscripcionId} para que el front no tenga que leerlo del detalle.
     */
    private ApiException duplicadaPorActiva(Long torneoId, Long usuarioId) {
        ApiException error = new ConflictoException(CodigoError.INSCRIPCION_DUPLICADA,
                "Ya tenés una inscripción activa en el torneo " + torneoId);
        inscripcionRepository.findFirstByTorneoIdAndUsuarioIdAndEstadoIn(torneoId, usuarioId, ACTIVAS)
                .ifPresent(activa -> error.con("inscripcionId", activa.getId()));
        return error;
    }

    private void exigirSinBorrador(Long torneoId, Long usuarioId) {
        List<Long> borradores = inscripcionRepository.borradoresDe(torneoId, usuarioId);
        if (!borradores.isEmpty()) {
            throw new ConflictoException(CodigoError.INSCRIPCION_DUPLICADA, "Ya tenés el borrador " + borradores.get(0)
                    + " en el torneo " + torneoId + ": retomalo o cancelalo antes de crear otro")
                    .con("inscripcionId", borradores.get(0));
        }
    }

    private InscripcionDetalle crearIndividual(Usuario usuario, Torneo torneo, InscripcionEquipoRequest request) {
        if (request != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION,
                    "Un torneo individual no lleva equipo ni nómina");
        }
        if (inscripcionRepository.existsByTorneoIdAndUsuarioIdAndEstadoIn(torneo.getId(), usuario.getId(), ACTIVAS)) {
            throw duplicadaPorActiva(torneo.getId(), usuario.getId());
        }

        Inscripcion inscripcion = new Inscripcion();
        inscripcion.setTorneo(torneo);
        inscripcion.setUsuario(usuario);
        return comun.detalleDe(inscripcionRepository.saveAndFlush(inscripcion), usuario.getId());
    }

    private InscripcionDetalle crearDeEquipo(Usuario capitan, Torneo torneo, InscripcionEquipoRequest request) {
        Inscripcion inscripcion = new Inscripcion();
        inscripcion.setTorneo(torneo);
        return comun.detalleDe(guardarEquipoYNomina(inscripcion, capitan, request), capitan.getId());
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
        comun.sumarAlEquipo(equipo, jugadores);
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
            if (UQ_EQUIPO.equals(NombreDeConstraint.de(e))) {
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
                            + " ya tiene la inscripción " + otra.getId() + " en el torneo (" + otra.getEstado() + ")")
                            .con("inscripcionId", otra.getId());
                });
    }

    // un jugador figura en una sola inscripción activa por torneo: pendiente de pago o confirmada. El organizador
    // y los árbitros del torneo no figuran en ninguna, como en el reemplazo
    private void validarJugadoresLibres(Torneo torneo, List<Usuario> jugadores, Long editada) {
        for (Usuario jugador : jugadores) {
            comun.validarQueNoOrganiza(torneo, jugador.getId());
            if (nominaRepository.figuraEnOtraInscripcion(torneo.getId(), jugador.getId(), ACTIVAS, editada)) {
                throw new ConflictoException(CodigoError.JUGADOR_YA_INSCRIPTO,
                        "El jugador " + jugador.getId() + " ya figura en otra inscripción activa del torneo " + torneo.getId())
                        // para que el front diga cuál (CE4-4)
                        .con("jugador", UsuarioPublico.desde(jugador));
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
            comun.validarUsuario(jugador);
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

    // el reparto del premio arranca en partes iguales, con el sobrante al capitán (CR-05)
    private void guardarNomina(Inscripcion inscripcion, Equipo equipo, List<Usuario> jugadores) {
        Map<Long, BigDecimal> reparto = RepartoPremio.partesIguales(jugadores.stream().map(Usuario::getId).toList(),
                equipo.getCapitan().getId());
        for (Usuario jugador : jugadores) {
            nominaRepository.save(new Nomina(jugador.getId(), equipo.getId(), inscripcion.getId(),
                    reparto.get(jugador.getId())));
        }
        nominaRepository.flush();
    }

    /**
     * El capitán cambia el reparto del premio de su equipo (CR-05) mientras la inscripción del torneo siga abierta,
     * aunque ya esté pagada. Cada cambio queda en la auditoría con el reparto de antes y de después; uno que no cambia
     * nada no deja entrada. Bloquea la fila del torneo como el reemplazo, así no pisa un cierre simultáneo.
     */
    @Transactional
    public InscripcionDetalle cambiarReparto(Long usuarioId, Long id, List<RepartoIntegrante> pedido) {
        Long torneoId = inscripcionRepository.torneoIdDeInscripcionPropia(id, usuarioId)
                .orElseThrow(() -> comun.noEncontrada(id));
        Torneo torneo = comun.torneoBloqueado(torneoId);
        Inscripcion inscripcion = comun.buscar(id);
        if (!inscripcion.esDeEquipo()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION,
                    "La inscripción individual no tiene reparto del premio");
        }
        comun.validarTorneoAbierto(torneo);
        if (!REPARTO_EDITABLE.contains(inscripcion.getEstado())) {
            throw new ConflictoException(CodigoError.INSCRIPCION_NO_EDITABLE, "La inscripción " + id + " está "
                    + inscripcion.getEstado() + ": el reparto se cambia en BORRADOR, PENDIENTE_PAGO o CONFIRMADA");
        }
        List<Nomina> nomina = nominaRepository.findAllByIdInscripcionId(id);
        Map<Long, BigDecimal> nuevo = validarReparto(pedido, nomina);

        Map<Long, BigDecimal> vigente = new HashMap<>(); // HashMap: admite el % nulo de un reparto viejo
        nomina.forEach(n -> vigente.put(n.getId().getUsuarioId(), n.getPctPremio()));
        Map<String, String> antes = repartoComoTexto(vigente);
        Map<String, String> despues = repartoComoTexto(nuevo);
        if (!antes.equals(despues)) {
            nomina.forEach(n -> n.setPctPremio(nuevo.get(n.getId().getUsuarioId())));
            nominaRepository.flush();
            auditarReparto(inscripcion, usuarioId, antes, despues);
        }
        return comun.detalleDe(inscripcion, usuarioId);
    }

    // están todos los de la nómina una vez cada uno y nadie más; % entre 0 y 100 con hasta dos decimales; suma 100
    private Map<Long, BigDecimal> validarReparto(List<RepartoIntegrante> pedido, List<Nomina> nomina) {
        if (pedido == null || pedido.stream().anyMatch(p -> p == null || p.usuarioId() == null || p.pct() == null)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION,
                    "El reparto es una lista de usuarioId y pct, sin faltantes");
        }
        Map<Long, BigDecimal> reparto = new HashMap<>();
        for (RepartoIntegrante parte : pedido) {
            if (reparto.put(parte.usuarioId(), parte.pct()) != null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION,
                        "El usuario " + parte.usuarioId() + " está repetido en el reparto");
            }
            if (parte.pct().signum() < 0 || parte.pct().compareTo(RepartoPremio.CIEN) > 0
                    || parte.pct().stripTrailingZeros().scale() > 2) {
                throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION,
                        "El % del usuario " + parte.usuarioId() + " tiene que estar entre 0 y 100, con hasta dos decimales");
            }
        }
        Set<Long> integrantes = nomina.stream().map(n -> n.getId().getUsuarioId()).collect(Collectors.toSet());
        if (!integrantes.equals(reparto.keySet())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION,
                    "El reparto tiene que incluir a todos los integrantes de la nómina, una vez cada uno, y a nadie más");
        }
        BigDecimal suma = reparto.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (suma.compareTo(RepartoPremio.CIEN) != 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION,
                    "El reparto suma " + suma.stripTrailingZeros().toPlainString() + " y tiene que sumar 100");
        }
        reparto.replaceAll((usuario, pct) -> pct.setScale(2));
        return reparto;
    }

    // usuario → % con dos decimales, ordenado por usuario; un % nulo (reparto viejo) queda como null
    private static Map<String, String> repartoComoTexto(Map<Long, BigDecimal> reparto) {
        Map<String, String> texto = new LinkedHashMap<>();
        new TreeMap<>(reparto).forEach((usuario, pct) ->
                texto.put(String.valueOf(usuario), pct == null ? null : pct.setScale(2, RoundingMode.HALF_UP).toPlainString()));
        return texto;
    }

    private void auditarReparto(Inscripcion inscripcion, Long actorId, Map<String, String> antes,
            Map<String, String> despues) {
        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("campo", "reparto");
        detalle.put("antes", antes);
        detalle.put("despues", despues);
        Auditoria auditoria = new Auditoria();
        auditoria.setUsuario(usuarioRepository.getReferenceById(actorId));
        auditoria.setAccion(AccionAuditoria.MODIFICACION);
        auditoria.setEntidad(EntidadAuditoria.INSCRIPCION.name());
        auditoria.setEntidadId(String.valueOf(inscripcion.getId()));
        auditoria.setDetalle(detalle);
        auditoriaRepository.save(auditoria);
    }

    /**
     * La ven quien se inscribió, el organizador del torneo y los jugadores de la nómina; para cualquier otro
     * no existe.
     */
    @Transactional(readOnly = true)
    public InscripcionDetalle detalle(Long usuarioId, Long id) {
        Inscripcion inscripcion = comun.buscar(id);
        boolean organiza = inscripcion.getTorneo().getOrganizador().getId().equals(usuarioId);
        if (!organiza && !comun.esDe(inscripcion, usuarioId) && !comun.figuraEnLaNomina(inscripcion, usuarioId)) {
            throw comun.noEncontrada(id);
        }
        return comun.detalleDe(inscripcion, usuarioId);
    }

    /**
     * La cancelación es una baja lógica: la inscripción queda en CANCELADA. Bloquea la fila del torneo antes de
     * leer la inscripción, igual que {@link PagoInscripcionService#pagar}: así no cancela una que otro pedido acaba de pagar.
     */
    @Transactional
    public void cancelar(Long usuarioId, Long id) {
        Long torneoId = inscripcionRepository.torneoIdDeInscripcionPropia(id, usuarioId)
                .orElseThrow(() -> comun.noEncontrada(id));
        comun.torneoBloqueado(torneoId);
        Inscripcion inscripcion = comun.buscar(id);
        if (inscripcion.getEstado() != EstadoInscripcion.BORRADOR && inscripcion.getEstado() != EstadoInscripcion.PENDIENTE_PAGO) {
            throw new ConflictoException(CodigoError.INSCRIPCION_NO_EDITABLE,
                    "La inscripción " + id + " está " + inscripcion.getEstado() + ": solo se cancela en BORRADOR o PENDIENTE_PAGO");
        }
        inscripcion.setEstado(EstadoInscripcion.CANCELADA);
    }

    /** Pasa el borrador a pendiente de pago y deja la fecha en que se aceptó el reglamento. */
    @Transactional
    public InscripcionDetalle confirmar(Long usuarioId, Long id, boolean reglamentoAceptado) {
        Inscripcion inscripcion = comun.buscar(id);
        if (!comun.esDe(inscripcion, usuarioId)) {
            throw comun.noEncontrada(id);
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
        Torneo torneo = bloquea ? comun.torneoBloqueado(torneoId) : inscripcion.getTorneo();
        if (bloquea) {
            // el estado de arriba se leyó antes de esperar el bloqueo: otro pedido pudo confirmarla mientras tanto
            exigirBorrador(id, inscripcionRepository.estadoDe(id).orElseThrow(() -> comun.noEncontrada(id)));
        }
        comun.validarUsuario(inscripcion.titular());
        comun.validarTorneoAbierto(torneo);
        comun.validarOrganizadorNoBloqueado(torneo);
        if (inscripcion.esDeEquipo()) {
            validarNomina(inscripcion, torneo);
        } else if (inscripcionRepository.existsByTorneoIdAndUsuarioIdAndEstadoIn(torneoId, usuarioId, ACTIVAS)) {
            throw duplicadaPorActiva(torneoId, usuarioId);
        }
        comun.validarCupo(torneo);
        inscripcion.setEstado(gratis ? EstadoInscripcion.CONFIRMADA : EstadoInscripcion.PENDIENTE_PAGO);
        inscripcion.setReglamentoAceptadoEn(Instant.now());
        Inscripcion guardada;
        try {
            guardada = inscripcionRepository.saveAndFlush(inscripcion);
        } catch (DataIntegrityViolationException e) {
            // otra confirmación individual del mismo usuario ganó la carrera entre el chequeo de arriba y este flush
            if (UQ_ACTIVA.equals(NombreDeConstraint.de(e))) {
                throw new ConflictoException(CodigoError.INSCRIPCION_DUPLICADA,
                        "Ya tenés una inscripción activa en el torneo " + torneoId);
            }
            throw e;
        }
        if (gratis) {
            comun.cerrarSiSeCompletoElCupo(torneo);
        }
        return comun.detalleDe(guardada, usuarioId);
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
        jugadores.forEach(comun::validarUsuario);
        validarJugadoresLibres(torneo, jugadores, inscripcion.getId());
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
                .orElseThrow(() -> comun.noEncontrada(id));
        comun.torneoBloqueado(torneoId);
        Inscripcion inscripcion = comun.buscar(id);
        if (!inscripcion.esDeEquipo()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION,
                    "La inscripción individual no tiene equipo ni nómina");
        }
        if (inscripcion.getEstado() != EstadoInscripcion.BORRADOR) {
            throw new ConflictoException(CodigoError.INSCRIPCION_NO_EDITABLE,
                    "La inscripción " + id + " está " + inscripcion.getEstado() + ": solo se edita en BORRADOR");
        }
        Usuario capitan = inscripcion.getEquipo().getCapitan();
        comun.validarUsuario(capitan);
        comun.validarTorneoAbierto(inscripcion.getTorneo());
        comun.validarQueNoOrganiza(inscripcion.getTorneo(), capitan.getId());
        return comun.detalleDe(guardarEquipoYNomina(inscripcion, capitan, request), usuarioId);
    }

    /** Mis inscripciones (HU-209), la más nueva primero; el orden no se elige desde afuera. */
    @Transactional(readOnly = true)
    public Page<InscripcionResumen> listarMias(Long usuarioId, Long torneoId, Pageable pageable) {
        Pageable masNuevaPrimero = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(Sort.Direction.DESC, "id"));
        return inscripcionRepository.buscarMias(usuarioId, torneoId, masNuevaPrimero).map(i -> InscripcionResumen.de(i, usuarioId));
    }

    /**
     * Vuelve una pendiente de pago a borrador: se pierde la aceptación del reglamento y el equipo y la nómina
     * quedan. Igual que {@link PagoInscripcionService#pagar}, primero bloquea la fila del torneo y recién después lee la inscripción:
     * un pago simultáneo no puede cobrar y que después se lo pise con BORRADOR.
     */
    @Transactional
    public InscripcionDetalle reabrir(Long usuarioId, Long id) {
        Long torneoId = inscripcionRepository.torneoIdDeInscripcionPropia(id, usuarioId)
                .orElseThrow(() -> comun.noEncontrada(id));
        comun.torneoBloqueado(torneoId);
        Inscripcion inscripcion = comun.buscar(id);
        if (inscripcion.getEstado() != EstadoInscripcion.PENDIENTE_PAGO) {
            throw new ConflictoException(CodigoError.INSCRIPCION_NO_EDITABLE,
                    "La inscripción " + id + " está " + inscripcion.getEstado() + ": solo se reabre en PENDIENTE_PAGO");
        }
        inscripcion.setEstado(EstadoInscripcion.BORRADOR);
        inscripcion.setReglamentoAceptadoEn(null);
        return comun.detalleDe(inscripcionRepository.saveAndFlush(inscripcion), usuarioId);
    }

}
