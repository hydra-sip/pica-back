package com.hydra.pica.plataforma_pica.rifa.service;

import java.time.Instant;

import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.partido.domain.SorteoBracket;
import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;
import com.hydra.pica.plataforma_pica.rifa.repository.RifaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * La semilla del sorteo de una rifa (HU-502, PICA-263): solo se obtiene con el compromiso ya publicado (409
 * COMPROMISO_NO_PUBLICADO), así {@code semillaObtenidaEn} siempre es posterior a {@code compromisoPublicadoEn}.
 * Una vez obtenida no cambia. Por ahora la genera el sistema como la del bracket; PICA-299 la reemplaza por la del
 * proveedor externo, con su firma en {@code semillaFirma}.
 */
@Service
@RequiredArgsConstructor
public class SemillaRifaService {

    private final RifaRepository rifaRepository;

    @Transactional
    public Rifa obtener(Long rifaId) {
        Rifa rifa = rifaRepository.buscarBloqueado(rifaId)
                .orElseThrow(() -> new NoEncontradoException(CodigoError.RIFA_NO_ENCONTRADA, "No existe la rifa " + rifaId));
        if (rifa.getCompromisoPublicadoEn() == null) {
            throw new ConflictoException(CodigoError.COMPROMISO_NO_PUBLICADO,
                    "La rifa " + rifaId + " no publicó su compromiso: la semilla se obtiene después de cerrar la venta");
        }
        if (rifa.getSemilla() == null) {
            rifa.setSemilla(SorteoBracket.nuevaSemilla());
            rifa.setSemillaObtenidaEn(Instant.now());
        }
        return rifa;
    }
}
