# Actualización en Tiempo Real (WebSocket / STOMP) y RNF-TRE-01

Este documento describe la arquitectura, protocolo, eventos y validación de rendimiento en tiempo real del backend de Plataforma PICA (**HU-305** y **RNF-TRE-01**).

---

## 1. Objetivos

1. **HU-305 (Actualización en tiempo real de bracket, pozo y estado):**
   - Reflejar el avance del bracket y ganadores sin recarga de página.
   - Reflejar incrementos del pozo del torneo en tiempo real tras compras de rifas.
   - Reflejar cambios de estado del torneo (ej. de `EN_CURSO` a `FINALIZADO`).
   - Sincronizar el estado del torneo tras una desconexión o reconexión del cliente.
2. **RNF-TRE-01 (Requisito No Funcional de Rendimiento):**
   - Garantizar que la propagación de eventos a 2 clientes concurrentes tenga un **percentil 95 (p95) de latencia menor o igual a 5 segundos** sobre **100 repeticiones**.

---

## 2. Arquitectura y Protocolo

El backend expone un endpoint WebSocket STOMP sobre SockJS:

- **Endpoint de conexión:** `/ws`
  - Soporta clientes WebSocket nativos y fallback SockJS.
  - Orígenes CORS habilitados según `app.cors.allowed-origins` (`http://localhost:5173`, `http://localhost:3000`, etc.).
  - Acceso público configurado en `SecurityConfig` para permitir el handshake inicial.
- **Broker de mensajería:** Prefijo `/topic`
  - In-memory broker (`SimpleBroker`) de Spring Framework para distribución pub/sub concurrente y de ultra baja latencia.
- **Prefijo de aplicación (destino entrante):** `/app`
  - Enrutamiento hacia métodos `@MessageMapping` y `@SubscribeMapping` de controladores Spring.

```
       [ Cliente 1 ]           [ Cliente 2 ]
             ▲                       ▲
             │ (STOMP /ws)           │ (STOMP /ws)
             └───────────┬───────────┘
                         │
                 [ Caddy / Nginx ]
                         │
       ┌─────────────────┴─────────────────┐
       │     Spring Boot WebSocket Broker   │
       │                                   │
       │  /topic/torneos/{id}              │
       │  /topic/rifas/{id}                │
       └─────────────────▲─────────────────┘
                         │
                 [ RealtimeNotifier ]
                         │
             [ RealtimeEventListener ]
           (Spring ApplicationEventBus)
            ▲                        ▲
            │                        │
  [ ResultadoConfirmado ]   [ NumerosVendidos ]
```

---

## 3. Canales (Topics) y Formato de Mensajes

### 3.1. Topic de Torneo: `/topic/torneos/{id}`

Notifica eventos que alteran el desarrollo de un torneo (bracket, estado y pozo acumulado).

#### Payload (`EventoTorneoMessage`):
```json
{
  "tipo": "RESULTADO_CONFIRMADO",
  "torneoId": 101,
  "estado": "EN_CURSO",
  "partidoId": 15,
  "ganadorParticipanteId": 42,
  "torneoFinalizado": false,
  "bracket": {
    "torneoId": 101,
    "seedOriginal": "abc123seed",
    "hashIntegridad": "f0a1b2c3...",
    "partidos": [
      {
        "id": 15,
        "ronda": 1,
        "posicion": 1,
        "participanteLocalId": 42,
        "participanteVisitanteId": 18,
        "ganadorId": 42,
        "estado": "FINALIZADO"
      }
    ]
  },
  "pozoActual": 150000.00
}
```

#### Tipos de evento (`tipo`):
- `RESULTADO_CONFIRMADO`: Se confirmó el resultado de un partido y avanzó la llave del bracket.
- `RESULTADO_OBJETADO`: Un resultado fue objetado y el bracket volvió a su estado previo.
- `PARTIDO_SIN_PRESENTES`: Al vencer la ventana de check-in no se presentó ninguno de los dos (HU-309): el partido quedó sin fecha o descalificaron a los dos, según el criterio del torneo. Un walkover y un pase directo llegan como `RESULTADO_CONFIRMADO`.
- `ESTADO_CAMBIADO`: Cambió el estado del torneo (ej. `INSCRIPCION_CERRADA` $\to$ `EN_CURSO` $\to$ `FINALIZADO`).
- `POZO_ACTUALIZADO`: Se incrementó el pozo del torneo tras la venta de números de una rifa asociada.
- `SYNC`: Snapshot completo enviado a un cliente que se reconecta.

---

### 3.2. Topic de Rifa: `/topic/rifas/{id}`

Notifica ventas de números de una rifa y su acumulado actual.

#### Payload (`EventoRifaMessage`):
```json
{
  "tipo": "NUMEROS_VENDIDOS",
  "rifaId": 55,
  "torneoId": 101,
  "estado": "EN_VENTA",
  "numerosVendidos": [7, 8, 9],
  "totalVendidos": 42,
  "pozoRifa": 21000.00,
  "pozoTorneo": 150000.00
}
```

#### Tipos de evento (`tipo`):
- `NUMEROS_VENDIDOS`: Compra confirmada de uno o más números.
- `ESTADO_CAMBIADO`: Cambio de estado de la rifa (ej. `EN_VENTA` $\to$ `SORTEADA`).
- `SYNC`: Snapshot de la rifa tras suscripción o reconexión.

---

## 4. Sincronización y Recuperación ante Desconexión

Para cumplir el Criterio 4 de la HU-305 (*"Simular una desconexión momentánea y sincronizar al reconectar"*):

1. **Suscripción directa con snapshot inicial (`@SubscribeMapping`):**
   - Cuando un cliente se suscribe a `/app/torneos/{id}`, el servidor le responde inmediatamente en su canal privado con el estado actual del torneo (`SYNC`).
   - Cuando se suscribe a `/app/rifas/{id}`, recibe el snapshot actual de la rifa.
2. **Petición explícita de resincronización (`@MessageMapping`):**
   - El cliente puede enviar un frame a `/app/torneos/{id}/sync` o `/app/rifas/{id}/sync` en cualquier momento tras reconectar la conexión de red.

---

## 5. Prueba de Rendimiento RNF-TRE-01

### 5.1. Definición del Requisito
- **Concurrencia:** Mínimo 2 clientes conectados simultáneamente.
- **Muestreo:** 100 repeticiones consecutivas de eventos.
- **Umbral de Aceptación:** $p95 \le 5{,}0\text{ s}$ ($5000\text{ ms}$).

### 5.2. Ejecución de la Prueba

Podés ejecutar la prueba en cualquier momento mediante el script automatizado:

```bash
./scripts/probar-rnf-tre-01.sh
```

O directamente mediante Maven:

```bash
mvn test -Dtest=RnfTre01Test
```

### 5.3. Métricas y Resultados Reales Obtenidos

La prueba en [RnfTre01Test.java](file:///home/baiza/Documentos/Programacion/SIP/pica-back/src/test/java/com/hydra/pica/plataforma_pica/realtime/RnfTre01Test.java) evalúa ambos topics (`/topic/torneos/101` y `/topic/rifas/55`) con 2 clientes conectados:

| Métrica | Rifa (`/topic/rifas/55`) | Torneo (`/topic/torneos/101`) | Límite RNF-TRE-01 |
|---|---|---|---|
| **Clientes concurrentes** | 2 | 2 | $\ge 2$ |
| **Repeticiones** | 100 | 100 | 100 |
| **Latencia mínima** | 0.089 ms | 0.072 ms | - |
| **Latencia promedio** | 0.417 ms | 0.148 ms | - |
| **p50 (Mediana)** | 0.118 ms | 0.104 ms | - |
| **p90** | 0.168 ms | 0.127 ms | - |
| **p95** | **0.274 ms** | **0.145 ms** | **$\le$ 5000.0 ms (5.0 s)** |
| **p99** | 0.465 ms | 0.197 ms | - |
| **Latencia máxima** | 28.177 ms | 4.427 ms | - |
| **Veredicto** | **CUMPLE ✓** | **CUMPLE ✓** | **Aprobado** |

*El percentil 95 obtenido (menor a 0.5 milisegundos) se encuentra más de 10.000 veces por debajo del límite de 5 segundos exigido por la cátedra.*
