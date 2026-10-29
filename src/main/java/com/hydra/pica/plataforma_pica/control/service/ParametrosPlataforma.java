package com.hydra.pica.plataforma_pica.control.service;

import java.math.BigDecimal;

import com.hydra.pica.plataforma_pica.control.repository.ParametroPlataformaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Los parámetros de la plataforma (HU-601, PICA-281), para que torneo y rifa lean el mismo valor. Quien publica
 * copia el valor vigente; lo publicado no vuelve a leerlo.
 */
@Service
@RequiredArgsConstructor
public class ParametrosPlataforma {

    static final String GASTOS_ADMINISTRATIVOS = "GASTOS_ADMINISTRATIVOS";

    private final ParametroPlataformaRepository parametroPlataformaRepository;

    /** % que paga aparte quien se inscribe en un torneo o compra un número de rifa. */
    @Transactional(readOnly = true)
    public BigDecimal gastosAdministrativos() {
        return valor(GASTOS_ADMINISTRATIVOS);
    }

    private BigDecimal valor(String clave) {
        return parametroPlataformaRepository.findById(clave)
                .orElseThrow(() -> new IllegalStateException("Falta el parámetro " + clave + " de V26"))
                .getValor();
    }
}
