package com.hydra.pica.plataforma_pica.common.config;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({CriptoConfig.CriptoProperties.class, CriptoConfig.RetirosCriptoProperties.class})
public class CriptoConfig {

    /**
     * La pasarela de cargas en Base Sepolia (HU-609, PICA-333).
     *
     * @param rpcUrl                el nodo JSON-RPC con el que se leen las transacciones
     * @param contrato              la dirección de PasarelaCargas (PICA-328)
     * @param chainId               la red del contrato, para que el front pida esa
     * @param confirmacionesMinimas bloques que tiene que tener la transacción, contando el suyo, para acreditarla
     * @param timeout               lo que se espera al nodo antes de responder que la red no está disponible
     * @param weiPorPeso            la cotización fija de testnet; múltiplo de 100 para que un monto en centavos
     *                              dé una cantidad entera de wei
     * @param exploradorUrl         donde cualquiera ve la transacción de una carga
     * @param bloqueDeploy          el bloque donde se desplegó el contrato: la conciliación lee los eventos desde ahí
     *                              (HU-608)
     * @param exploradorApiUrl      la API de Etherscan (v2, multi-red) con la que la conciliación trae los eventos
     *                              del contrato: el nodo público solo los da de a 200 bloques
     * @param exploradorApiKey      la API key de BaseScan/Etherscan; sin ella no se puede conciliar
     */
    @ConfigurationProperties(prefix = "app.cripto")
    public record CriptoProperties(
            @DefaultValue("https://sepolia.base.org") String rpcUrl,
            @DefaultValue("0x36e633eDD1E79Ef2b9A3c6A099D873D52e44EDF0") String contrato,
            @DefaultValue("84532") long chainId,
            @DefaultValue("3") int confirmacionesMinimas,
            @DefaultValue("5s") Duration timeout,
            @DefaultValue("10000000000") BigInteger weiPorPeso,
            @DefaultValue("https://sepolia.basescan.org") String exploradorUrl,
            @DefaultValue("55324950") long bloqueDeploy,
            @DefaultValue("https://api.etherscan.io/v2/api") String exploradorApiUrl,
            @DefaultValue("") String exploradorApiKey) {

        private static final BigInteger CIEN = BigInteger.valueOf(100);

        public CriptoProperties {
            if (weiPorPeso.signum() <= 0 || weiPorPeso.mod(CIEN).signum() != 0) {
                throw new IllegalArgumentException(
                        "app.cripto.wei-por-peso tiene que ser un múltiplo positivo de 100, y es " + weiPorPeso);
            }
        }

        /** Lo que hay que pagar en el contrato por ese monto en pesos (con 2 decimales como mucho). */
        public BigInteger weiPara(BigDecimal pesos) {
            return pesos.movePointRight(2).toBigIntegerExact().multiply(weiPorPeso).divide(CIEN);
        }
    }

    /**
     * La pasarela de retiros a la wallet en Base Sepolia (HU-610, PICA-455): el back firma un permiso EIP-712 y el
     * usuario lo cobra en el contrato PasarelaRetiros. Sin contrato o sin clave, los retiros a la wallet responden 503.
     *
     * @param contrato          la dirección de PasarelaRetiros (PICA-457)
     * @param claveFirmante     la clave privada con la que se firman los permisos, en hex. No tiene fondos: el contrato
     *                          solo paga lo que ella firma. Es un secreto: nunca va al repo ni a los logs
     * @param vigencia          cuánto tiempo tiene el usuario para cobrar un permiso
     * @param margenDevolucion  cuánto se espera después del vencimiento para devolver el saldo de un permiso sin cobrar
     */
    @ConfigurationProperties(prefix = "app.cripto.retiros")
    public record RetirosCriptoProperties(
            @DefaultValue("") String contrato,
            @DefaultValue("") String claveFirmante,
            @DefaultValue("1h") Duration vigencia,
            @DefaultValue("5m") Duration margenDevolucion) {

        public boolean habilitados() {
            return !contrato.isBlank() && !claveFirmante.isBlank();
        }

        // el toString de un record mostraría la clave en cualquier log de la configuración
        @Override
        public String toString() {
            return "RetirosCriptoProperties[contrato=" + contrato + ", claveFirmante="
                    + (claveFirmante.isBlank() ? "" : "***") + ", vigencia=" + vigencia
                    + ", margenDevolucion=" + margenDevolucion + "]";
        }
    }
}
