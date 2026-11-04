package com.hydra.pica.plataforma_pica.torneo.service;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.common.almacenamiento.AlmacenArchivos;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ErrorCampo;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.SelloTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.dto.TorneoInformacion;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * El PDF del reglamento, en el paso 1 del asistente (HU-201, PICA-200). Se sube y se quita solo en BORRADOR; su
 * SHA-256 queda en {@code reglamentoHash} desde la subida, y es lo que se sella al publicar en lugar del texto.
 *
 * El archivo va al {@link AlmacenArchivos} con una clave por torneo, así que subir otro reemplaza al anterior.
 */
@Service
@RequiredArgsConstructor
public class ReglamentoTorneoService {

    /** El multipart admite más (los videos de evidencia, PICA-241): el tope del PDF es este. */
    public static final int TAMANIO_MAXIMO = 5 * 1024 * 1024;
    private static final byte[] FIRMA_PDF = "%PDF-".getBytes(StandardCharsets.US_ASCII);

    private final TorneosPropios torneosPropios;
    private final TorneoRepository torneoRepository;
    private final AlmacenArchivos almacen;

    /**
     * @param url donde se va a leer, {@code GET /torneos/{id}/reglamento}: la arma el controller porque es absoluta
     */
    @Transactional
    public TorneoInformacion subir(Long organizadorId, Long id, byte[] pdf, String url) {
        validar(pdf);
        Torneo torneo = torneosPropios.buscarEditable(organizadorId, id);
        almacen.guardar(clave(id), pdf, "application/pdf");
        torneo.setReglamentoUrl(url);
        torneo.setReglamentoHash(SelloTorneo.sha256(pdf));
        return torneosPropios.informacion(torneoRepository.saveAndFlush(torneo));
    }

    /** Sin PDF no hace nada. Si tampoco hay texto, el paso 1 vuelve a quedar incompleto. */
    @Transactional
    public TorneoInformacion quitar(Long organizadorId, Long id) {
        Torneo torneo = torneosPropios.buscarEditable(organizadorId, id);
        if (torneo.getReglamentoUrl() != null) {
            torneo.setReglamentoUrl(null);
            torneo.setReglamentoHash(null);
            torneoRepository.saveAndFlush(torneo);
            // después de la base: si falla, el rollback deja el torneo como estaba
            almacen.borrar(clave(id));
        }
        return torneosPropios.informacion(torneo);
    }

    /**
     * Publicado lo ve cualquiera, como la ficha del torneo; en BORRADOR, solo su organizador. Para los demás no
     * existe: 404.
     */
    @Transactional(readOnly = true)
    public byte[] pdf(Optional<Long> usuarioId, Long id) {
        Torneo torneo = torneoRepository.findById(id)
                .filter(t -> t.getEliminadoEn() == null)
                .filter(t -> t.getEstado() != EstadoTorneo.BORRADOR
                        || usuarioId.filter(t.getOrganizador().getId()::equals).isPresent())
                .orElseThrow(() -> new NoEncontradoException(CodigoError.TORNEO_NO_ENCONTRADO,
                        "No existe el torneo " + id));
        return Optional.ofNullable(torneo.getReglamentoUrl())
                .flatMap(url -> almacen.leer(clave(id)))
                .orElseThrow(() -> new NoEncontradoException(CodigoError.REGLAMENTO_NO_ENCONTRADO,
                        "El torneo " + id + " no tiene el reglamento en PDF"));
    }

    /** Se mira el contenido y no la extensión ni el tipo que manda el navegador: un PDF empieza con "%PDF-". */
    private static void validar(byte[] pdf) {
        if (pdf.length > TAMANIO_MAXIMO) {
            throw archivoInvalido(ErrorCampo.Codigo.LONGITUD, "Hasta " + TAMANIO_MAXIMO / (1024 * 1024) + " MB");
        }
        if (pdf.length < FIRMA_PDF.length || !Arrays.equals(pdf, 0, FIRMA_PDF.length, FIRMA_PDF, 0, FIRMA_PDF.length)) {
            throw archivoInvalido(ErrorCampo.Codigo.FORMATO_INVALIDO, "Tiene que ser un PDF");
        }
    }

    /** 400 VALIDACION con el error en el campo {@code archivo}, el nombre de la parte del multipart. */
    public static ApiException archivoInvalido(ErrorCampo.Codigo codigo, String mensaje) {
        return new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION, "Hay campos inválidos")
                .con("errores", List.of(new ErrorCampo("archivo", codigo, mensaje)));
    }

    private static String clave(Long id) {
        return "torneos/" + id + "/reglamento.pdf";
    }
}
