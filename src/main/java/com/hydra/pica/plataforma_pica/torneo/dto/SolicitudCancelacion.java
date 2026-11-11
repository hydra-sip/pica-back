package com.hydra.pica.plataforma_pica.torneo.dto;

import java.util.List;

/** Lo que manda el organizador para cancelar su torneo (HU-605): el motivo y los gastos que quiere retener. */
public record SolicitudCancelacion(String motivo, List<GastoDeclarado> gastos) {
}
