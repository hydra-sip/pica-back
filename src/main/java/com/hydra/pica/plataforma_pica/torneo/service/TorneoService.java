package com.hydra.pica.plataforma_pica.torneo.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.hydra.pica.plataforma_pica.common.config.TorneoConfig.TorneoProperties;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.ErrorCampo;
import com.hydra.pica.plataforma_pica.control.domain.AccionAuditoria;
import com.hydra.pica.plataforma_pica.control.domain.Auditoria;
import com.hydra.pica.plataforma_pica.control.repository.AuditoriaRepository;
import com.hydra.pica.plataforma_pica.control.service.ParametrosPlataforma;
import com.hydra.pica.plataforma_pica.torneo.domain.Cancha;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Formato;
import com.hydra.pica.plataforma_pica.torneo.domain.Modalidad;
import com.hydra.pica.plataforma_pica.torneo.domain.TipoParticipante;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.dto.CheckinTorneoRequest;
import com.hydra.pica.plataforma_pica.torneo.dto.InformacionTorneoRequest;
import com.hydra.pica.plataforma_pica.torneo.dto.OpcionesTorneo;
import com.hydra.pica.plataforma_pica.torneo.dto.TorneoInformacion;
import com.hydra.pica.plataforma_pica.torneo.repository.CanchaRepository;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.auditing.AuditingHandler;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pasos 1 y 2 del asistente de crear torneo (HU-201, PICA-199). Quién puede organizar lo decide el permiso
 * TORNEO_GESTIONAR en {@link com.hydra.pica.plataforma_pica.torneo.controller.TorneoController}; acá cada uno ve y
 * edita solo los suyos, y el de otro responde 404 como si no existiera.
 *
 * Publicado, se sigue editando hasta que empieza (HU-202, PICA-206): lo que ya no se puede cambiar lo decide
 * {@link EdicionTorneo}. El front manda todo cada vez, así que se rechaza solo lo que cambia (409
 * CAMPOS_BLOQUEADOS), y "no puede ser anterior a hoy" se mira solo en las fechas que cambian: si no, un torneo con el
 * cierre ya pasado no se podría guardar.
 *
 * En BORRADOR se guarda incompleto (V18): solo el nombre y la disciplina son obligatorios, y lo que viene se
 * valida contra lo demás que viene. Que esté todo (sede si es presencial, tamaño si es por equipos, la parte
 * económica) lo exige la publicación.
 *
 * Las canchas se cargan por nombre y son de la sede: la que no existía en esa sede se crea.
 */
@Service
@RequiredArgsConstructor
public class TorneoService {

    private static final int CUPO_MINIMO = 2;
    private static final Set<EstadoTorneo> CHECKIN_CONFIGURABLE =
            EnumSet.of(EstadoTorneo.INSCRIPCION_ABIERTA, EstadoTorneo.INSCRIPCION_CERRADA, EstadoTorneo.EN_CURSO);

    private final TorneoRepository torneoRepository;
    private final TorneosPropios torneosPropios;
    private final EdicionTorneo edicionTorneo;
    private final CanchaRepository canchaRepository;
    private final UsuarioRepository usuarioRepository;
    private final TorneoProperties torneoProperties;
    private final ParametrosPlataforma parametrosPlataforma;
    private final AuditingHandler auditingHandler;
    private final AuditoriaRepository auditoriaRepository;

    /** {@code sede} es opcional: con ella vienen también las canchas que ya existen ahí. */
    @Transactional(readOnly = true)
    public OpcionesTorneo opciones(String sede) {
        List<String> canchas = limpiar(sede) == null ? List.of()
                : canchaRepository.findBySedeIgnoreCaseAndEliminadoEnIsNullOrderByNombreAsc(sede.strip()).stream()
                        .map(Cancha::getNombre)
                        .toList();
        return new OpcionesTorneo(CatalogoTorneo.DISCIPLINAS, CatalogoTorneo.FORMATOS, List.of(Modalidad.values()),
                torneoProperties.cupoMaximo(), parametrosPlataforma.gastosAdministrativos(), canchas);
    }

    @Transactional(readOnly = true)
    public TorneoInformacion detalle(Long organizadorId, Long id) {
        return torneosPropios.informacion(torneosPropios.buscarPropio(organizadorId, id));
    }

    /** Nace en BORRADOR con lo que se haya cargado del paso 1. */
    @Transactional
    public TorneoInformacion crear(Long organizadorId, InformacionTorneoRequest request) {
        validar(request, EdicionTorneo.CAMPOS);

        Torneo torneo = new Torneo();
        torneo.setOrganizador(usuarioRepository.getReferenceById(organizadorId));
        aplicar(torneo, request);
        return torneosPropios.informacion(torneoRepository.saveAndFlush(torneo));
    }

    /** Reemplaza los datos de los pasos 1 y 2, canchas incluidas. */
    @Transactional
    public TorneoInformacion actualizarInformacion(Long organizadorId, Long id, InformacionTorneoRequest request) {
        Torneo torneo = torneosPropios.buscarBloqueado(organizadorId, id);
        if (!EdicionTorneo.editable(torneo.getEstado())) {
            throw new ConflictoException(CodigoError.TORNEO_NO_EDITABLE,
                    "El torneo " + id + " está " + torneo.getEstado() + ": ya no se modifica");
        }
        rechazarOtros(request);
        boolean borrador = torneo.getEstado() == EstadoTorneo.BORRADOR;
        List<String> cambiados = borrador ? EdicionTorneo.CAMPOS : cambiados(torneo, request);
        List<String> bloqueados = edicionTorneo.bloqueados(torneo).stream().filter(cambiados::contains).toList();
        if (!bloqueados.isEmpty()) {
            throw new ConflictoException(CodigoError.CAMPOS_BLOQUEADOS,
                    "El torneo " + id + " ya no admite cambios en " + bloqueados)
                    .con("campos", bloqueados);
        }
        List<ErrorCampo> errores = erroresEntreCampos(request, cambiados);
        if (!borrador) {
            agregarErroresDePublicado(torneo, request, cambiados, errores);
        }
        lanzarSiHay(errores);

        aplicar(torneo, request);
        // si solo cambian las canchas no se entera la auditoría; el flush es para que modificadoEn salga en la respuesta
        auditingHandler.markModified(torneo);
        return torneosPropios.informacion(torneoRepository.saveAndFlush(torneo));
    }

    private void aplicar(Torneo torneo, InformacionTorneoRequest request) {
        torneo.setNombre(request.nombre().strip());
        torneo.setDisciplina(request.disciplina().strip());
        torneo.setDescripcion(limpiar(request.descripcion()));
        torneo.setFormato(request.formato() == null ? Formato.ELIMINACION_DIRECTA : request.formato());
        torneo.setModalidad(request.modalidad());
        torneo.setTipoParticipante(request.tipoParticipante());
        torneo.setSede(limpiar(request.sede()));
        torneo.setFechaInicio(request.fechaInicio());
        torneo.setFechaFin(request.fechaFin());
        torneo.setCierreInscripcionEn(request.cierreInscripcionEn());
        torneo.setReglamentoTexto(limpiar(request.reglamentoTexto()));
        torneo.setCupoMaximo(request.cupoMaximo());
        torneo.setCupoMinimo(request.cupoMinimo());
        torneo.setTamanioEquipo(request.tamanioEquipo());
        torneo.setNominaMaxima(request.nominaMaxima());
        reemplazarCanchas(torneo, nombresDeCanchas(request.canchas()));
    }

    private void reemplazarCanchas(Torneo torneo, List<String> nombres) {
        torneo.getCanchas().clear();
        for (String nombre : nombres) {
            Cancha cancha = canchaRepository
                    .findFirstBySedeIgnoreCaseAndNombreIgnoreCaseAndEliminadoEnIsNull(torneo.getSede(), nombre)
                    .orElseGet(() -> nuevaCancha(nombre, torneo.getSede()));
            torneo.getCanchas().add(cancha);
        }
    }

    private Cancha nuevaCancha(String nombre, String sede) {
        Cancha cancha = new Cancha();
        cancha.setNombre(nombre);
        cancha.setSede(sede);
        return canchaRepository.save(cancha);
    }

    /** Sin repetidos (sin mayúsculas), en el orden en que vinieron. */
    private static List<String> nombresDeCanchas(List<String> canchas) {
        if (canchas == null) {
            return List.of();
        }
        Map<String, String> porClave = new LinkedHashMap<>();
        canchas.forEach(c -> porClave.putIfAbsent(c.strip().toLowerCase(Locale.ROOT), c.strip()));
        return List.copyOf(porClave.values());
    }

    /**
     * La ventana de check-in de todos los cruces (HU-308): abre {@code minAntes} minutos antes y dura
     * {@code duracion}, sin llegar al horario del partido. Con el torneo publicado y sin terminar; rige desde ahora,
     * aun para una ventana abierta, y los check-ins hechos quedan.
     */
    @Transactional
    public TorneoInformacion configurarCheckin(Long organizadorId, Long id, CheckinTorneoRequest request) {
        Torneo torneo = torneosPropios.buscarBloqueado(organizadorId, id);
        if (!CHECKIN_CONFIGURABLE.contains(torneo.getEstado())) {
            throw new ConflictoException(CodigoError.TORNEO_NO_EDITABLE,
                    "El torneo " + id + " está " + torneo.getEstado() + ": el check-in se configura publicado");
        }
        validarCheckin(request);
        torneo.setCheckinMinAntes(request.minAntes());
        torneo.setCheckinDuracionMin(request.duracion());

        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("cambio", "CHECKIN");
        detalle.put("minAntes", request.minAntes());
        detalle.put("duracion", request.duracion());
        Auditoria auditoria = new Auditoria();
        auditoria.setUsuario(usuarioRepository.getReferenceById(organizadorId));
        auditoria.setAccion(AccionAuditoria.MODIFICACION);
        auditoria.setEntidad("TORNEO");
        auditoria.setEntidadId(String.valueOf(id));
        auditoria.setDetalle(detalle);
        auditoriaRepository.save(auditoria);
        return torneosPropios.informacion(torneo);
    }

    private static void validarCheckin(CheckinTorneoRequest request) {
        List<ErrorCampo> errores = new ArrayList<>();
        positivo("minAntes", request.minAntes(), errores);
        positivo("duracion", request.duracion(), errores);
        if (errores.isEmpty() && request.duracion() > request.minAntes()) {
            errores.add(new ErrorCampo("duracion", ErrorCampo.Codigo.VENTANA_INVALIDA,
                    "La ventana se superpondría con el cruce: tiene que durar como mucho lo que anticipa"));
        }
        if (!errores.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION, "Hay campos inválidos")
                    .con("errores", errores);
        }
    }

    private static void positivo(String campo, Integer valor, List<ErrorCampo> errores) {
        if (valor == null) {
            errores.add(new ErrorCampo(campo, ErrorCampo.Codigo.REQUERIDO, "Falta en minutos"));
        } else if (valor <= 0) {
            errores.add(new ErrorCampo(campo, ErrorCampo.Codigo.RANGO_INVALIDO, "Tiene que ser mayor que 0"));
        }
    }

    // --- qué cambia en un torneo publicado ------------------------------------

    private static List<String> cambiados(Torneo t, InformacionTorneoRequest r) {
        Formato formato = r.formato() == null ? Formato.ELIMINACION_DIRECTA : r.formato();
        Map<String, Boolean> cambia = Map.ofEntries(
                Map.entry("nombre", !r.nombre().strip().equals(t.getNombre())),
                Map.entry("disciplina", !r.disciplina().strip().equals(t.getDisciplina())),
                Map.entry("descripcion", !Objects.equals(limpiar(r.descripcion()), t.getDescripcion())),
                Map.entry("formato", formato != t.getFormato()),
                Map.entry("modalidad", r.modalidad() != t.getModalidad()),
                Map.entry("tipoParticipante", r.tipoParticipante() != t.getTipoParticipante()),
                Map.entry("sede", !Objects.equals(limpiar(r.sede()), t.getSede())),
                Map.entry("canchas", !claves(nombresDeCanchas(r.canchas())).equals(
                        claves(t.getCanchas().stream().map(Cancha::getNombre).toList()))),
                Map.entry("fechaInicio", !mismoInstante(r.fechaInicio(), t.getFechaInicio())),
                Map.entry("fechaFin", !mismoInstante(r.fechaFin(), t.getFechaFin())),
                Map.entry("cierreInscripcionEn", !mismoInstante(r.cierreInscripcionEn(), t.getCierreInscripcionEn())),
                Map.entry("reglamentoTexto", !Objects.equals(limpiar(r.reglamentoTexto()), t.getReglamentoTexto())),
                Map.entry("cupoMaximo", !Objects.equals(r.cupoMaximo(), t.getCupoMaximo())),
                Map.entry("cupoMinimo", !Objects.equals(r.cupoMinimo(), t.getCupoMinimo())),
                Map.entry("tamanioEquipo", !Objects.equals(r.tamanioEquipo(), t.getTamanioEquipo())),
                Map.entry("nominaMaxima", !Objects.equals(r.nominaMaxima(), t.getNominaMaxima())));
        return EdicionTorneo.CAMPOS.stream().filter(cambia::get).toList();
    }

    private static Set<String> claves(List<String> nombres) {
        return nombres.stream().map(n -> n.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    }

    /** La base guarda microsegundos. */
    private static boolean mismoInstante(Instant a, Instant b) {
        return a == null || b == null ? a == b
                : a.truncatedTo(ChronoUnit.MICROS).equals(b.truncatedTo(ChronoUnit.MICROS));
    }

    /**
     * Publicado no se puede vaciar lo que pedía la publicación (si no, salta el CHECK de la base), ni dejar el cupo
     * por debajo de los que ya se inscribieron.
     */
    private void agregarErroresDePublicado(Torneo torneo, InformacionTorneoRequest request, List<String> cambiados,
            List<ErrorCampo> errores) {
        requerido(errores, "modalidad", request.modalidad());
        requerido(errores, "fechaInicio", request.fechaInicio());
        requerido(errores, "cierreInscripcionEn", request.cierreInscripcionEn());
        requerido(errores, "cupoMaximo", request.cupoMaximo());
        requerido(errores, "cupoMinimo", request.cupoMinimo());
        if (request.tipoParticipante() == TipoParticipante.EQUIPO) {
            requerido(errores, "tamanioEquipo", request.tamanioEquipo());
            requerido(errores, "nominaMaxima", request.nominaMaxima());
        }
        if (cambiados.contains("cupoMaximo") && request.cupoMaximo() != null) {
            long inscriptos = edicionTorneo.inscriptos(torneo.getId());
            if (request.cupoMaximo() < inscriptos) {
                errores.add(new ErrorCampo("cupoMaximo", ErrorCampo.Codigo.RANGO_INVALIDO,
                        "Ya hay " + inscriptos + " inscriptos"));
            }
        }
    }

    private static void requerido(List<ErrorCampo> errores, String campo, Object valor) {
        if (valor == null) {
            errores.add(new ErrorCampo(campo, ErrorCampo.Codigo.REQUERIDO, "Un torneo publicado no lo puede dejar vacío"));
        }
    }

    // --- validaciones entre campos ------------------------------------------

    /**
     * Junta todos los errores y los devuelve en un solo 400, como Bean Validation. {@code cambiados}: las fechas
     * donde se mira que no sean anteriores a hoy.
     */
    private void validar(InformacionTorneoRequest request, Collection<String> cambiados) {
        rechazarOtros(request);
        lanzarSiHay(erroresEntreCampos(request, cambiados));
    }

    private List<ErrorCampo> erroresEntreCampos(InformacionTorneoRequest request, Collection<String> cambiados) {
        List<ErrorCampo> errores = new ArrayList<>();
        validarFechas(request, cambiados, errores);
        validarSede(request, errores);
        validarCupos(request, errores);
        validarNomina(request, errores);
        return errores;
    }

    /** El valor de la inscripción o el reparto, por ejemplo: van por el paso 3 y solo en BORRADOR. */
    private static void rechazarOtros(InformacionTorneoRequest request) {
        lanzarSiHay(request.otros().keySet().stream()
                .map(campo -> new ErrorCampo(campo, ErrorCampo.Codigo.VALOR_INVALIDO,
                        "No es un dato de los pasos 1 y 2: la parte económica se carga en el paso 3, antes de publicar"))
                .toList());
    }

    private static void validarFechas(InformacionTorneoRequest request, Collection<String> cambiados,
            List<ErrorCampo> errores) {
        Instant ahora = Instant.now();
        Instant inicio = request.fechaInicio();
        if (inicio != null && cambiados.contains("fechaInicio") && !inicio.isAfter(ahora)) {
            errores.add(fechas("fechaInicio", "No puede ser anterior a hoy"));
        }
        if (inicio != null && request.fechaFin() != null && request.fechaFin().isBefore(inicio)) {
            errores.add(fechas("fechaFin", "No puede ser anterior a la fecha de inicio"));
        }
        Instant cierre = request.cierreInscripcionEn();
        if (cierre != null && cambiados.contains("cierreInscripcionEn") && !cierre.isAfter(ahora)) {
            errores.add(fechas("cierreInscripcionEn", "No puede ser anterior a hoy"));
        } else if (cierre != null && inicio != null && cierre.isAfter(inicio)) {
            errores.add(fechas("cierreInscripcionEn", "La inscripción tiene que cerrar antes del inicio"));
        }
    }

    private static void validarSede(InformacionTorneoRequest request, List<ErrorCampo> errores) {
        boolean sinSede = limpiar(request.sede()) == null;
        boolean conCanchas = request.canchas() != null && !request.canchas().isEmpty();
        if (request.modalidad() == Modalidad.ONLINE && conCanchas) {
            errores.add(new ErrorCampo("canchas", ErrorCampo.Codigo.VALOR_INVALIDO, "Un torneo online no tiene canchas"));
        } else if (sinSede && (request.modalidad() == Modalidad.PRESENCIAL || conCanchas)) {
            errores.add(new ErrorCampo("sede", ErrorCampo.Codigo.REQUERIDO,
                    conCanchas ? "Las canchas son de una sede" : "Un torneo presencial necesita sede"));
        }
    }

    private void validarCupos(InformacionTorneoRequest request, List<ErrorCampo> errores) {
        int tope = torneoProperties.cupoMaximo();
        Integer maximo = request.cupoMaximo();
        Integer minimo = request.cupoMinimo();
        if (maximo != null && (maximo < CUPO_MINIMO || maximo > tope)) {
            errores.add(new ErrorCampo("cupoMaximo", ErrorCampo.Codigo.RANGO_INVALIDO,
                    "Tiene que estar entre " + CUPO_MINIMO + " y " + tope));
        }
        if (minimo != null && minimo < CUPO_MINIMO) {
            errores.add(new ErrorCampo("cupoMinimo", ErrorCampo.Codigo.RANGO_INVALIDO,
                    "Hacen falta al menos " + CUPO_MINIMO + " para iniciar"));
        } else if (minimo != null && maximo != null && minimo > maximo) {
            errores.add(new ErrorCampo("cupoMinimo", ErrorCampo.Codigo.RANGO_INVALIDO,
                    "No puede ser mayor que el cupo máximo"));
        }
    }

    private static void validarNomina(InformacionTorneoRequest request, List<ErrorCampo> errores) {
        Integer tamanio = request.tamanioEquipo();
        Integer nomina = request.nominaMaxima();
        if (request.tipoParticipante() != TipoParticipante.EQUIPO) {
            if (tamanio != null || nomina != null) {
                errores.add(new ErrorCampo(tamanio != null ? "tamanioEquipo" : "nominaMaxima",
                        ErrorCampo.Codigo.VALOR_INVALIDO, "Solo en torneos por equipos"));
            }
        } else if (tamanio != null && nomina != null && nomina < tamanio) {
            errores.add(new ErrorCampo("nominaMaxima", ErrorCampo.Codigo.RANGO_INVALIDO,
                    "No puede ser menor que el tamaño de equipo"));
        }
    }

    private static ErrorCampo fechas(String campo, String mensaje) {
        return new ErrorCampo(campo, ErrorCampo.Codigo.FECHAS_INVALIDAS, mensaje);
    }

    private static void lanzarSiHay(List<ErrorCampo> errores) {
        if (!errores.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION, "Hay campos inválidos")
                    .con("errores", errores);
        }
    }

    private static String limpiar(String texto) {
        return texto == null || texto.isBlank() ? null : texto.strip();
    }
}
