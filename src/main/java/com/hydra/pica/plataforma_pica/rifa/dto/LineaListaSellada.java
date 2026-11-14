package com.hydra.pica.plataforma_pica.rifa.dto;

import com.hydra.pica.plataforma_pica.rifa.domain.Numero;

/**
 * Una línea de la lista sellada al cerrar la venta (HU-407): un número vendido y el código de su ticket, como entra
 * en {@code listaHash} (ver SelloRifa). Desde la versión 2 del sorteo (CR-06), también la etiqueta anónima de quien lo
 * compró ({@code número;código;comprador}), para recalcular que nadie gane dos veces.
 *
 * @param comprador C1, C2...; null en la versión 1
 */
public record LineaListaSellada(int numero, String ticketCodigo, String comprador) {

    public static LineaListaSellada de(Numero numero, String comprador) {
        return new LineaListaSellada(numero.getId().getNumero(), numero.getTicket().getCodigo(), comprador);
    }
}
