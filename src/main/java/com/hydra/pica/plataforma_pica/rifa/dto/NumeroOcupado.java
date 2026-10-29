package com.hydra.pica.plataforma_pica.rifa.dto;

import com.hydra.pica.plataforma_pica.rifa.domain.EstadoNumero;
import com.hydra.pica.plataforma_pica.rifa.domain.Numero;

/**
 * Un número de la grilla que no se puede elegir (HU-403): VENDIDO, RESERVADO mientras alguien lo paga o
 * REINTEGRADO. Los que no vienen en la lista están disponibles. No dice quién lo tiene.
 */
public record NumeroOcupado(int numero, EstadoNumero estado) {

    public static NumeroOcupado de(Numero numero) {
        return new NumeroOcupado(numero.getId().getNumero(), numero.getEstado());
    }
}
