package com.hydra.pica.plataforma_pica.billetera.service;

import com.hydra.pica.plataforma_pica.billetera.domain.Movimiento;

/**
 * Por acá cobran inscripciones y rifas. Hoy la implementa {@link CobroSimulado}; cuando llegue la
 * billetera o la pasarela, se cambia la implementación y quien cobra no se entera.
 */
public interface CobroService {

    /**
     * Cobra y devuelve el movimiento CONFIRMADO. Corre dentro de la transacción de quien llama: si
     * algo falla después, el movimiento también se deshace. Con una clave ya usada devuelve el mismo
     * movimiento, sin cobrar de nuevo.
     *
     * @throws CobroRechazadoException si el pago no se aprueba
     */
    Movimiento cobrar(SolicitudCobro solicitud);
}
