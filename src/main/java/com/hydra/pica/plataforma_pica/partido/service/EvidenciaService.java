package com.hydra.pica.plataforma_pica.partido.service;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.hydra.pica.plataforma_pica.common.almacenamiento.AlmacenArchivos;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.ErrorCampo;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.common.error.ProhibidoException;
import com.hydra.pica.plataforma_pica.partido.domain.Evidencia;
import com.hydra.pica.plataforma_pica.partido.domain.Juega;
import com.hydra.pica.plataforma_pica.partido.domain.Lado;
import com.hydra.pica.plataforma_pica.partido.domain.Partido;
import com.hydra.pica.plataforma_pica.partido.dto.EvidenciaDetalle;
import com.hydra.pica.plataforma_pica.partido.repository.EvidenciaRepository;
import com.hydra.pica.plataforma_pica.partido.repository.JuegaRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.SelloTorneo;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * La evidencia de un partido (HU-301, PICA-241): fotos, capturas o videos cortos que sube una de las partes, con o sin
 * resultado cargado. Es opcional al cargar el resultado; al objetarlo (HU-302) es obligatoria.
 *
 * <ul>
 * <li>Sube el capitán de uno de los dos lados, mientras el partido no esté cerrado. Cada archivo queda del lado de quien
 * lo subió.</li>
 * <li>El formato se reconoce por el contenido, no por la extensión: JPG, PNG o WEBP hasta 5 MB; MP4, MOV o WEBM hasta
 * 20 MB.</li>
 * <li>La ven las partes, los árbitros del torneo y su organizador.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class EvidenciaService {

    public static final int MAXIMO_IMAGEN = 5 * 1024 * 1024;
    /** También es el tope de {@code spring.servlet.multipart.max-file-size}. */
    public static final int MAXIMO_VIDEO = 20 * 1024 * 1024;

    private final Partidos partidos;
    private final JuegaRepository juegaRepository;
    private final EvidenciaRepository evidenciaRepository;
    private final TorneoRepository torneoRepository;
    private final AlmacenArchivos almacen;

    /** @param urlBase la del POST, absoluta: el archivo se lee en {@code <urlBase>/<id>/archivo} */
    @Transactional
    public EvidenciaDetalle subir(Long usuarioId, Long partidoId, byte[] archivo, String urlBase) {
        Partido partido = partidos.buscar(partidoId);
        List<Juega> lados = juegaRepository.delPartido(partidoId);
        Juega mio = Partidos.exigirCapitan(lados, usuarioId, partidoId);
        if (partido.getTorneo().getEstado() != EstadoTorneo.EN_CURSO || lados.size() < 2
                || Partidos.CERRADOS.contains(partido.getEstado())) {
            throw new ConflictoException(CodigoError.PARTIDO_CERRADO,
                    "El partido " + partidoId + " está " + partido.getEstado() + ": ya no se le suma evidencia");
        }
        String tipo = tipo(archivo);

        Evidencia evidencia = new Evidencia();
        evidencia.setPartido(partido);
        evidencia.setInscripcion(mio.getInscripcion());
        evidencia.setTipoContenido(tipo);
        evidencia.setTamanioBytes((long) archivo.length);
        evidencia.setHash(SelloTorneo.sha256(archivo));
        // la URL lleva el id: primero la fila, después la URL y el archivo
        evidencia.setUrl("");
        evidencia = evidenciaRepository.saveAndFlush(evidencia);
        evidencia.setUrl(urlBase + "/" + evidencia.getId() + "/archivo");
        almacen.guardar(clave(partidoId, evidencia.getId()), archivo, tipo);
        return detalle(evidencia, mio);
    }

    @Transactional(readOnly = true)
    public List<EvidenciaDetalle> listar(Long usuarioId, Long partidoId) {
        Partido partido = partidos.buscar(partidoId);
        List<Juega> lados = juegaRepository.delPartido(partidoId);
        exigirAcceso(partido, lados, usuarioId);
        Map<Long, Juega> porInscripcion =
                lados.stream().collect(Collectors.toMap(j -> j.getId().getInscripcionId(), j -> j));
        return evidenciaRepository.findByPartidoIdOrderByCreadoEnAscIdAsc(partidoId).stream()
                .map(e -> detalle(e, porInscripcion.get(e.getInscripcion().getId())))
                .toList();
    }

    /** El contenido y su tipo. */
    @Transactional(readOnly = true)
    public Archivo archivo(Long usuarioId, Long partidoId, Long evidenciaId) {
        Partido partido = partidos.buscar(partidoId);
        exigirAcceso(partido, juegaRepository.delPartido(partidoId), usuarioId);
        Evidencia evidencia = evidenciaRepository.findById(evidenciaId)
                .filter(e -> e.getPartido().getId().equals(partidoId))
                .orElseThrow(() -> noEncontrada(evidenciaId));
        byte[] contenido = almacen.leer(clave(partidoId, evidenciaId)).orElseThrow(() -> noEncontrada(evidenciaId));
        return new Archivo(contenido, evidencia.getTipoContenido());
    }

    public record Archivo(byte[] contenido, String tipoContenido) {
    }

    private void exigirAcceso(Partido partido, List<Juega> lados, Long usuarioId) {
        boolean puede = Partidos.deCapitan(lados, usuarioId).isPresent()
                || partido.getTorneo().getOrganizador().getId().equals(usuarioId)
                || torneoRepository.esArbitro(partido.getTorneo().getId(), usuarioId);
        if (!puede) {
            throw new ProhibidoException(CodigoError.SIN_PERMISO,
                    "La evidencia del partido " + partido.getId() + " la ven sus partes, sus árbitros y el organizador");
        }
    }

    private static EvidenciaDetalle detalle(Evidencia evidencia, Juega lado) {
        Lado deQuien = lado == null ? null : lado.getLado();
        String nombre = lado == null ? null : Partidos.nombre(lado.getInscripcion());
        return new EvidenciaDetalle(evidencia.getId(), deQuien, nombre, evidencia.getTipoContenido(),
                evidencia.getTamanioBytes(), evidencia.getHash(), evidencia.getUrl(), evidencia.getCreadoEn());
    }

    // --- formato ----------------------------------------------------------------

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final byte[] RIFF = "RIFF".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] WEBP = "WEBP".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] FTYP = "ftyp".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] QUICKTIME = "qt  ".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] EBML = {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3};

    /** Por los primeros bytes; 400 en el campo {@code archivo} si no es uno de los admitidos o pasa su tope. */
    static String tipo(byte[] archivo) {
        String tipo;
        if (empieza(archivo, 0, JPEG)) {
            tipo = "image/jpeg";
        } else if (empieza(archivo, 0, PNG)) {
            tipo = "image/png";
        } else if (empieza(archivo, 0, RIFF) && empieza(archivo, 8, WEBP)) {
            tipo = "image/webp";
        } else if (empieza(archivo, 4, FTYP)) {
            tipo = empieza(archivo, 8, QUICKTIME) ? "video/quicktime" : "video/mp4";
        } else if (empieza(archivo, 0, EBML)) {
            tipo = "video/webm";
        } else {
            throw archivoInvalido(ErrorCampo.Codigo.FORMATO_INVALIDO,
                    "Tiene que ser una foto (JPG, PNG o WEBP) o un video (MP4, MOV o WEBM)");
        }
        boolean video = tipo.startsWith("video/");
        int maximo = video ? MAXIMO_VIDEO : MAXIMO_IMAGEN;
        if (archivo.length > maximo) {
            throw archivoInvalido(ErrorCampo.Codigo.LONGITUD,
                    (video ? "Un video" : "Una foto") + " puede tener hasta " + maximo / (1024 * 1024) + " MB");
        }
        return tipo;
    }

    private static boolean empieza(byte[] archivo, int desde, byte[] firma) {
        return archivo.length >= desde + firma.length
                && Arrays.equals(archivo, desde, desde + firma.length, firma, 0, firma.length);
    }

    /** 400 VALIDACION con el error en el campo {@code archivo}, el nombre de la parte del multipart. */
    public static ApiException archivoInvalido(ErrorCampo.Codigo codigo, String mensaje) {
        return new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION, "Hay campos inválidos")
                .con("errores", List.of(new ErrorCampo("archivo", codigo, mensaje)));
    }

    private static String clave(Long partidoId, Long evidenciaId) {
        return "partidos/" + partidoId + "/evidencias/" + evidenciaId;
    }

    private static NoEncontradoException noEncontrada(Long id) {
        return new NoEncontradoException(CodigoError.EVIDENCIA_NO_ENCONTRADA, "No existe la evidencia " + id);
    }
}
