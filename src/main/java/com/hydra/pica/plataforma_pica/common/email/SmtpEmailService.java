package com.hydra.pica.plataforma_pica.common.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class SmtpEmailService implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailService.class);

    private final ObjectProvider<JavaMailSender> mailSender;
    private final String mailHost;
    private final String from;

    public SmtpEmailService(ObjectProvider<JavaMailSender> mailSender,
                            @Value("${spring.mail.host:}") String mailHost,
                            @Value("${spring.mail.username:no-reply@pica.local}") String from) {
        this.mailSender = mailSender;
        this.mailHost = mailHost;
        this.from = from;
    }

    @Override
    public void enviarVerificacion(String email, String url) {
        if (mailHost.isBlank() || mailSender.getIfAvailable() == null) {
            log.info("URL de verificacion para {}: {}", email, url);
            return;
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject("Verificá tu correo electrónico");
        message.setText("Completá la verificación desde este enlace: " + url);
        mailSender.getObject().send(message);
    }
}
