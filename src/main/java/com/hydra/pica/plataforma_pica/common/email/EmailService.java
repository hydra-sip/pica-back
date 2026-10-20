package com.hydra.pica.plataforma_pica.common.email;

public interface EmailService {

    void enviarVerificacion(String email, String url);
}
