package com.hydra.pica.plataforma_pica.billetera.service;

import java.math.BigDecimal;

import com.hydra.pica.plataforma_pica.billetera.domain.Movimiento;

/**
 * Por acá cobran inscripciones y rifas. Lo implementa {@link CobroConSaldo}, que paga con el disponible de la
 * billetera (HU-603); {@link CobroSimulado} queda para los tests que no prueban el cobro. Cuál se usa lo dice
 * {@code app.cobro.modo}: quien cobra no se entera.
 */
public interface CobroService {

    /**
     * Cobra y devuelve el movimiento CONFIRMADO. Corre dentro de la transacción de quien llama: si
     * algo falla después, el movimiento también se deshace. Con una clave ya usada devuelve el mismo
     * movimiento, sin cobrar de nuevo.
     *
     * @throws CobroRechazadoException si el pago no se aprueba ({@link SaldoInsuficienteException} si no alcanza el saldo)
     */
    Movimiento cobrar(SolicitudCobro solicitud);

    /**
     * Antes de una operación que cobra en más de un movimiento (el valor y los gastos administrativos): si el total
     * no alcanza, falla sin cobrar nada y con el faltante del total, no el de un solo cobro. Corre en la transacción
     * de quien llama, que después cobra cada parte.
     *
     * @throws CobroRechazadoException si no se podría cobrar
     */
    default void exigirSaldo(Long usuarioId, BigDecimal total) {
    }
}
