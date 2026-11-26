package com.hydra.pica.plataforma_pica.billetera.service;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import com.hydra.pica.plataforma_pica.common.config.CriptoConfig.CriptoProperties;
import com.hydra.pica.plataforma_pica.common.config.CriptoConfig.RetirosCriptoProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

/**
 * Firma los permisos de retiro a la wallet (HU-610, PICA-455) con el formato EIP-712 que valida PasarelaRetiros:
 * {@code Retiro(bytes32 referencia, address destino, uint256 monto, uint256 vence)} con el dominio
 * {@code PasarelaRetiros}, versión {@code 1}, el chain id y la dirección del contrato. El hash se arma a mano: si
 * cambia el formato de un lado, {@code FirmanteDeRetirosTest} y el {@code PermisoDeReferenciaTest} del contrato
 * dejan de coincidir. La clave no tiene fondos ni manda transacciones: solo firma.
 */
@Component
public class FirmanteDeRetiros {

    static final byte[] TIPO_DOMINIO = keccak(
            "EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)");
    static final byte[] TIPO_RETIRO = keccak(
            "Retiro(bytes32 referencia,address destino,uint256 monto,uint256 vence)");
    private static final byte[] NOMBRE = keccak("PasarelaRetiros");
    private static final byte[] VERSION = keccak("1");

    private final ECKeyPair claves;
    private final String contrato;
    private final long chainId;

    @Autowired
    FirmanteDeRetiros(CriptoProperties cripto, RetirosCriptoProperties retiros) {
        this(retiros.claveFirmante(), retiros.contrato(), cripto.chainId());
    }

    FirmanteDeRetiros(String claveFirmante, String contrato, long chainId) {
        this.claves = claveFirmante.isBlank() ? null : ECKeyPair.create(Numeric.toBigInt(claveFirmante));
        this.contrato = contrato;
        this.chainId = chainId;
    }

    public boolean habilitado() {
        return claves != null && !contrato.isBlank();
    }

    /** La dirección que tiene que figurar como {@code firmante} en el contrato. */
    public String direccion() {
        return "0x" + Keys.getAddress(claves.getPublicKey());
    }

    /** El hash que firma el back y que el contrato calcula en {@code digestDelRetiro}. */
    public byte[] digest(String referencia, String destino, BigInteger montoWei, long vence) {
        byte[] dominio = keccak(concatenar(TIPO_DOMINIO, NOMBRE, VERSION, palabra(BigInteger.valueOf(chainId)),
                direccion(contrato)));
        byte[] estructura = keccak(concatenar(TIPO_RETIRO, Numeric.hexStringToByteArray(referencia),
                direccion(destino), palabra(montoWei), palabra(BigInteger.valueOf(vence))));
        return keccak(concatenar(new byte[] {0x19, 0x01}, dominio, estructura));
    }

    /** La firma de 65 bytes (r, s y v) en hex, como la recibe {@code cobrar}. */
    public String firmar(String referencia, String destino, BigInteger montoWei, long vence) {
        Sign.SignatureData firma = Sign.signMessage(digest(referencia, destino, montoWei, vence), claves, false);
        return Numeric.toHexString(concatenar(firma.getR(), firma.getS(), firma.getV()));
    }

    private static byte[] keccak(String texto) {
        return Hash.sha3(texto.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] keccak(byte[] bytes) {
        return Hash.sha3(bytes);
    }

    // un uint256 de abi.encode: 32 bytes, big endian
    private static byte[] palabra(BigInteger valor) {
        return Numeric.toBytesPadded(valor, 32);
    }

    // un address de abi.encode: 20 bytes alineados a la derecha en 32
    private static byte[] direccion(String direccion) {
        return palabra(Numeric.toBigInt(direccion.toLowerCase(Locale.ROOT)));
    }

    private static byte[] concatenar(byte[]... partes) {
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        for (byte[] parte : partes) {
            salida.writeBytes(parte);
        }
        return salida.toByteArray();
    }
}
