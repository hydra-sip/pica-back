package com.hydra.pica.plataforma_pica.billetera.service;

import java.math.BigDecimal;

/**
 * Lo que la liquidación (HU-606) le acredita a una billetera.
 *
 * @param usuarioId     null en la de la plataforma
 * @param inscripcionId la inscripción ganadora, en un premio del torneo
 * @param puesto        el puesto, en un premio
 * @param referencia    única: la base no deja repetirla (V38), así nada se paga dos veces
 */
public record Acreditacion(Rol rol, Long usuarioId, BigDecimal monto, Long inscripcionId, Integer puesto,
                           String referencia, String descripcion) {

    public enum Rol { PREMIO, ORGANIZADOR, PLATAFORMA, HONORARIO }
}
