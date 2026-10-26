package com.hydra.pica.plataforma_pica.torneo.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.hydra.pica.plataforma_pica.common.config.TorneoConfig.TorneoProperties;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
<<<<<<< HEAD
import com.hydra.pica.plataforma_pica.common.error.ErrorCampo;
import com.hydra.pica.plataforma_pica.torneo.domain.Cancha;
=======
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.ErrorCampo;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.torneo.domain.Cancha;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
>>>>>>> origin/dev
import com.hydra.pica.plataforma_pica.torneo.domain.Formato;
import com.hydra.pica.plataforma_pica.torneo.domain.Modalidad;
import com.hydra.pica.plataforma_pica.torneo.domain.TipoParticipante;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
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
 * edita solo los suyos, y el de otro responde 404 como si no existiera. Por ahora solo se modifica en BORRADOR:
 * qué se puede editar de un torneo publicado es la HU-202.
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

    private final TorneoRepository torneoRepository;
<<<<<<< HEAD
    private final TorneosPropios torneosPropios;
=======
>>>>>>> origin/dev
    private final CanchaRepository canchaRepository;
    private final UsuarioRepository usuarioRepository;
    private final TorneoProperties torneoProperties;
    private final AuditingHandler auditingHandler;

    /** {@code sede} es opcional: con ella vienen también las canchas que ya existen ahí. */
    @Transactional(readOnly = true)
    public OpcionesTorneo opciones(String sede) {
        List<String> canchas = limpiar(sede) == null ? List.of()
                : canchaRepository.findBySedeIgnoreCaseAndEliminadoEnIsNullOrderByNombreAsc(sede.strip()).stream()
                        .map(Cancha::getNombre)
                        .toList();
        return new OpcionesTorneo(CatalogoTorneo.DISCIPLINAS, CatalogoTorneo.FORMATOS, List.of(Modalidad.values()),
<<<<<<< HEAD
                torneoProperties.cupoMaximo(), torneoProperties.comisionMaxima(), canchas);
=======
                torneoProperties.cupoMaximo(), canchas);
>>>>>>> origin/dev
    }

    @Transactional(readOnly = true)
    public TorneoInformacion detalle(Long organizadorId, Long id) {
<<<<<<< HEAD
        return torneosPropios.informacion(torneosPropios.buscarPropio(organizadorId, id));
=======
        return TorneoInformacion.de(buscarPropio(organizadorId, id));
>>>>>>> origin/dev
    }

    /** Nace en BORRADOR con lo que se haya cargado del paso 1. */
    @Transactional
    public TorneoInformacion crear(Long organizadorId, InformacionTorneoRequest request) {
        validar(request);

        Torneo torneo = new Torneo();
        torneo.setOrganizador(usuarioRepository.getReferenceById(organizadorId));
        aplicar(torneo, request);
<<<<<<< HEAD
        return torneosPropios.informacion(torneoRepository.saveAndFlush(torneo));
=======
        return TorneoInformacion.de(torneoRepository.saveAndFlush(torneo));
>>>>>>> origin/dev
    }

    /** Reemplaza los datos de los pasos 1 y 2, canchas incluidas. */
    @Transactional
    public TorneoInformacion actualizarInformacion(Long organizadorId, Long id, InformacionTorneoRequest request) {
<<<<<<< HEAD
        Torneo torneo = torneosPropios.buscarEditable(organizadorId, id);
=======
        Torneo torneo = buscarEditable(organizadorId, id);
>>>>>>> origin/dev
        validar(request);

        aplicar(torneo, request);
        // si solo cambian las canchas no se entera la auditoría; el flush es para que modificadoEn salga en la respuesta
        auditingHandler.markModified(torneo);
<<<<<<< HEAD
        return torneosPropios.informacion(torneoRepository.saveAndFlush(torneo));
=======
        return TorneoInformacion.de(torneoRepository.saveAndFlush(torneo));
    }

    private Torneo buscarPropio(Long organizadorId, Long id) {
        return torneoRepository.findByIdAndOrganizadorIdAndEliminadoEnIsNull(id, organizadorId)
                .orElseThrow(() -> new NoEncontradoException(CodigoError.TORNEO_NO_ENCONTRADO, "No existe el torneo " + id));
    }

    private Torneo buscarEditable(Long organizadorId, Long id) {
        Torneo torneo = buscarPropio(organizadorId, id);
        if (torneo.getEstado() != EstadoTorneo.BORRADOR) {
            throw new ConflictoException(CodigoError.TORNEO_NO_EDITABLE,
                    "El torneo " + id + " está " + torneo.getEstado() + ": solo se modifica en BORRADOR");
        }
        return torneo;
>>>>>>> origin/dev
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

    // --- validaciones entre campos ------------------------------------------

    /** Junta todos los errores y los devuelve en un solo 400, como Bean Validation. */
    private void validar(InformacionTorneoRequest request) {
        List<ErrorCampo> errores = new ArrayList<>();
        validarFechas(request, errores);
        validarSede(request, errores);
        validarCupos(request, errores);
        validarNomina(request, errores);
        lanzarSiHay(errores);
    }

    private static void validarFechas(InformacionTorneoRequest request, List<ErrorCampo> errores) {
        Instant ahora = Instant.now();
        Instant inicio = request.fechaInicio();
        if (inicio != null && !inicio.isAfter(ahora)) {
            errores.add(fechas("fechaInicio", "No puede ser anterior a hoy"));
        }
        if (inicio != null && request.fechaFin() != null && request.fechaFin().isBefore(inicio)) {
            errores.add(fechas("fechaFin", "No puede ser anterior a la fecha de inicio"));
        }
        Instant cierre = request.cierreInscripcionEn();
        if (cierre != null && !cierre.isAfter(ahora)) {
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
