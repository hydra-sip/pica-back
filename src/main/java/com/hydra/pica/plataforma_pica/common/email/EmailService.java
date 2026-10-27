package com.hydra.pica.plataforma_pica.common.email;

public interface EmailService {

    void enviarVerificacion(String email, String url);

    /** Un aviso de texto plano: una objeción para resolver, por ejemplo. */
    void enviarAviso(String email, String asunto, String texto);
}
