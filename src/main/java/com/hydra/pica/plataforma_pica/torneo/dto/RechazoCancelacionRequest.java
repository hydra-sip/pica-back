package com.hydra.pica.plataforma_pica.torneo.dto;

/** El motivo con el que soporte rechaza los gastos de una cancelación (HU-605); lo valida el servicio. */
public record RechazoCancelacionRequest(String motivo) {
}
