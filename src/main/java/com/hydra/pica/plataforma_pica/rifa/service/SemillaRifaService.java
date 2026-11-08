package com.hydra.pica.plataforma_pica.rifa.service;

import java.time.Instant;

import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;
import com.hydra.pica.plataforma_pica.rifa.repository.RifaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * La semilla del sorteo de una rifa (HU-502, PICA-263): solo se obtiene con el compromiso ya publicado (409
 * COMPROMISO_NO_PUBLICADO), así {@code semillaObtenidaEn} siempre es posterior a {@code compromisoPublicadoEn}.
 * Una vez obtenida no cambia. Sale de drand (HU-503, PICA-264): es el {@code randomness} de la ronda vigente en la
 * fecha del sorteo, que forma parte del compromiso, así la ronda queda fijada antes de que exista su valor. Esa
 * ronda siempre sale después del compromiso: el cierre de la venta corre la fecha si hace falta
 * ({@link CierreVentaService}).
 */
@Service
@RequiredArgsConstructor
public class SemillaRifaService {

    private final RifaRepository rifaRepository;
    private final Drand drand;

    @Transactional
    public Rifa obtener(Long rifaId) {
        Rifa rifa = rifaRepository.buscarBloqueado(rifaId)
                .orElseThrow(() -> new NoEncontradoException(CodigoError.RIFA_NO_ENCONTRADA, "No existe la rifa " + rifaId));
        if (rifa.getCompromisoPublicadoEn() == null) {
            throw new ConflictoException(CodigoError.COMPROMISO_NO_PUBLICADO,
                    "La rifa " + rifaId + " no publicó su compromiso: la semilla se obtiene después de cerrar la venta");
        }
        if (rifa.getSemilla() == null) {
            long ronda = drand.ronda(rifa.getSorteadaEn());
            Drand.Pulso pulso = drand.pulso(ronda);
            rifa.setSemilla(pulso.randomness());
            rifa.setSemillaFirma(pulso.signature());
            rifa.setSemillaRonda(ronda);
            rifa.setSemillaObtenidaEn(Instant.now());
        }
        return rifa;
    }
}
