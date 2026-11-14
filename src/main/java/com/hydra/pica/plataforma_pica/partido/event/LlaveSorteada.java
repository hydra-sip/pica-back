package com.hydra.pica.plataforma_pica.partido.event;

/**
 * Se sorteó la llave del torneo (HU-207): por primera vez o, con {@code regenerada}, de nuevo sobre la anterior. Para
 * el aviso al canal de Discord (PICA-371).
 */
public record LlaveSorteada(Long torneoId, boolean regenerada) {
}
