package com.hydra.pica.plataforma_pica.rifa.dto;

import com.hydra.pica.plataforma_pica.rifa.domain.Numero;

/**
 * Una línea de la lista sellada al cerrar la venta (HU-407): un número vendido y el código de su ticket, como entra
 * en {@code listaHash} ({@code número;código}, ver SelloRifa).
 */
public record LineaListaSellada(int numero, String ticketCodigo) {

    public static LineaListaSellada de(Numero numero) {
        return new LineaListaSellada(numero.getId().getNumero(), numero.getTicket().getCodigo());
    }
}
