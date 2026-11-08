package com.hydra.pica.plataforma_pica.control.service;

import java.math.BigDecimal;
import java.time.Duration;

import com.hydra.pica.plataforma_pica.control.domain.ClaveParametro;
import com.hydra.pica.plataforma_pica.control.repository.ParametroPlataformaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Los parámetros de la plataforma (HU-601, PICA-281), para que torneo y rifa lean el mismo valor. Quien publica
 * copia el valor vigente; lo publicado no vuelve a leerlo. Los edita el admin ({@link ParametrosAdminService}).
 */
@Service
@RequiredArgsConstructor
public class ParametrosPlataforma {

    private final ParametroPlataformaRepository parametroPlataformaRepository;

    /** % que paga aparte quien se inscribe en un torneo o compra un número de rifa. */
    @Transactional(readOnly = true)
    public BigDecimal gastosAdministrativos() {
        return valor(ClaveParametro.GASTOS_ADMINISTRATIVOS);
    }

    /** Lo mínimo que tiene que pasar entre el cierre de la inscripción y el inicio del torneo (V33). */
    @Transactional(readOnly = true)
    public Duration margenCierreInicio() {
        return Duration.ofMinutes(valor(ClaveParametro.MARGEN_CIERRE_INICIO_MIN).longValueExact());
    }

    /** Lo máximo que puede durar la ventana de check-in que configura el organizador (V35). */
    @Transactional(readOnly = true)
    public Duration checkinDuracionMaxima() {
        return Duration.ofMinutes(valor(ClaveParametro.CHECKIN_DURACION_MAX_MIN).longValueExact());
    }

    /** Monto mínimo de un retiro de la billetera, en pesos (V35, para HU-607). */
    @Transactional(readOnly = true)
    public BigDecimal retiroMinimo() {
        return valor(ClaveParametro.RETIRO_MINIMO);
    }

    /** Monto máximo de un retiro de la billetera, en pesos (V35, para HU-607). */
    @Transactional(readOnly = true)
    public BigDecimal retiroMaximo() {
        return valor(ClaveParametro.RETIRO_MAXIMO);
    }

    private BigDecimal valor(ClaveParametro clave) {
        return parametroPlataformaRepository.findById(clave.name())
                .orElseThrow(() -> new IllegalStateException("Falta el parámetro " + clave + " en la base"))
                .getValor();
    }
}
