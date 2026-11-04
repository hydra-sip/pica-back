package com.hydra.pica.plataforma_pica.common.almacenamiento;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.common.config.AlmacenamientoConfig.AlmacenamientoProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** En una carpeta del servidor: para dev y los tests. En Railway se pierde con cada deploy. */
@Component
@ConditionalOnProperty(name = "app.almacenamiento.tipo", havingValue = "local", matchIfMissing = true)
class AlmacenLocal implements AlmacenArchivos {

    private final Path raiz;

    AlmacenLocal(AlmacenamientoProperties properties) {
        this.raiz = properties.local().carpeta().toAbsolutePath().normalize();
    }

    @Override
    public void guardar(String clave, byte[] contenido, String tipoContenido) {
        Path archivo = ruta(clave);
        try {
            Files.createDirectories(archivo.getParent());
            Files.write(archivo, contenido);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @Override
    public Optional<byte[]> leer(String clave) {
        Path archivo = ruta(clave);
        try {
            return Files.exists(archivo) ? Optional.of(Files.readAllBytes(archivo)) : Optional.empty();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @Override
    public void borrar(String clave) {
        try {
            Files.deleteIfExists(ruta(clave));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private Path ruta(String clave) {
        Path archivo = raiz.resolve(clave).normalize();
        if (!archivo.startsWith(raiz)) {
            throw new IllegalArgumentException("Clave fuera de la carpeta: " + clave);
        }
        return archivo;
    }
}
