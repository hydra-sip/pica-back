package com.hydra.pica.plataforma_pica.notificacion.aviso;

import java.util.Map;

import com.hydra.pica.plataforma_pica.notificacion.domain.TipoNotificacion;

/** Una notificación para mandar: se arma dentro de la transacción de lectura y se manda después. */
record Aviso(Long usuarioId, TipoNotificacion tipo, String titulo, String cuerpo, String enlace,
             Map<String, Object> datos) {
}
