// Prueba de carga de PICA con k6 (PICA-441): el catálogo público, la inscripción a un torneo y la compra de números
// de una rifa, con usuarios virtuales en paralelo. Va contra un ambiente local o de prueba, nunca contra producción:
// crea usuarios, torneos y rifas, y carga saldo simulado.
//
//   docker run --rm --network host -u "$(id -u)" -v "$PWD/carga:/carga" grafana/k6:1.8.1 run \
//     --summary-export /carga/resultado.json /carga/pica.js
//
// Necesita una API con el perfil dev y el seed (el admin y dev_organizador con PicaDev2026), como la de
// pica-web/e2e/docker-compose.yml. PICA_API cambia la URL.
import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';

const API = __ENV.PICA_API || 'http://localhost:8080/api/v1';
const CLAVE = 'PicaDev2026';
// Un jugador por inscripción: el cupo de cada torneo es de 64 (app.torneos.cupo-maximo)
const JUGADORES = 60;

export const options = {
  scenarios: {
    catalogo: {
      executor: 'constant-vus',
      exec: 'catalogo',
      vus: 20,
      duration: '30s',
    },
    inscripcion: {
      executor: 'shared-iterations',
      exec: 'inscripcion',
      vus: 10,
      iterations: JUGADORES,
      maxDuration: '60s',
    },
    rifa: {
      executor: 'shared-iterations',
      exec: 'rifa',
      vus: 10,
      iterations: JUGADORES,
      maxDuration: '60s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{escenario:catalogo}': ['p(95)<500'],
    'http_req_duration{escenario:inscripcion}': ['p(95)<500'],
    'http_req_duration{escenario:rifa}': ['p(95)<500'],
  },
};

const json = (token) => ({
  headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
  tags: { escenario: 'setup' },
});

function login(usuario) {
  const res = http.post(`${API}/auth/login`, JSON.stringify({ identificador: usuario, password: CLAVE }), json());
  if (res.status !== 200) throw new Error(`login de ${usuario}: ${res.status} ${res.body}`);
  return res.json('accessToken');
}

function pedir(metodo, ruta, token, cuerpo) {
  const res = http.request(metodo, `${API}${ruta}`, cuerpo === undefined ? null : JSON.stringify(cuerpo), json(token));
  if (res.status >= 300) throw new Error(`${metodo} ${ruta}: ${res.status} ${res.body}`);
  return res.body ? res.json() : null;
}

const enHoras = (h) => new Date(Date.now() + h * 3600000).toISOString();

// Arrange: el torneo, la rifa y los jugadores con saldo. No cuenta para los umbrales.
export function setup() {
  const admin = login('admin');
  const organizador = login('dev_organizador');
  const sufijo = Date.now().toString(36);

  const torneo = pedir('POST', '/torneos', organizador, {
    nombre: `Carga ${sufijo}`,
    disciplina: 'Ajedrez',
    tipoParticipante: 'INDIVIDUAL',
    modalidad: 'PRESENCIAL',
    sede: 'Club Carga',
    fechaInicio: enHoras(3),
    fechaFin: enHoras(24),
    cierreInscripcionEn: enHoras(1),
    cupoMaximo: 64,
    cupoMinimo: 2,
    reglamentoTexto: 'Reglamento de la prueba de carga.',
  });
  pedir('PUT', `/torneos/${torneo.id}/economia`, organizador, { valorInscripcion: 1000, premios: [100] });
  pedir('POST', `/torneos/${torneo.id}/publicar`, organizador, { aceptaTerminos: true });

  const rifa = pedir('POST', '/rifas', organizador, {
    titulo: `Rifa carga ${sufijo}`,
    numeroDesde: 1,
    numeroHasta: 1000,
    precioNumero: 100,
    ventaHasta: enHoras(24),
    fechaSorteo: enHoras(48),
  });
  pedir('PUT', `/rifas/${rifa.id}/premios`, organizador, { premios: [{ pct: 100, descripcion: 'Premio' }] });
  pedir('POST', `/rifas/${rifa.id}/publicar`, organizador, { aceptaTerminos: true, declaraAutorizacionLoteria: true });

  const tokens = [];
  for (let i = 0; i < JUGADORES; i++) {
    const persona = pedir('POST', '/admin/personas', admin, {
      nombres: 'Carga',
      apellidos: 'Jugador',
      tipoDoc: 'DNI',
      nroDoc: String(30000000 + Math.floor(Math.random() * 50000000)),
      fechaNacimiento: '1995-05-05',
    });
    const username = `carga_${sufijo}_${i}`;
    pedir('POST', '/admin/usuarios', admin, {
      username,
      email: `${username}@pica.local`,
      passwordTemporal: CLAVE,
      personaId: persona.id,
      roles: [6],
    });
    const token = login(username);
    pedir('POST', '/billetera/cargas', token, { monto: 10000 });
    tokens.push(token);
  }
  return { torneoId: torneo.id, rifaId: rifa.id, tokens };
}

// Lo que más se pide: la primera página del catálogo, sin sesión
export function catalogo() {
  const res = http.get(`${API}/torneos/resumen?page=0&size=12`, { tags: { escenario: 'catalogo' } });
  check(res, { 'catálogo 200': (r) => r.status === 200 });
}

// Cada iteración es un jugador distinto: crea el borrador, confirma y paga con saldo
export function inscripcion(datos) {
  const token = datos.tokens[exec.scenario.iterationInTest];
  const params = { ...json(token), tags: { escenario: 'inscripcion' } };
  const alta = http.post(`${API}/torneos/${datos.torneoId}/inscripciones`, null, params);
  if (!check(alta, { 'inscripción 201': (r) => r.status === 201 })) return;
  const id = alta.json('id');
  const confirmada = http.put(`${API}/inscripciones/${id}/confirmar`, JSON.stringify({ reglamentoAceptado: true }), params);
  check(confirmada, { 'confirmar 200': (r) => r.status === 200 });
  const pago = http.post(`${API}/inscripciones/${id}/pago`, null, params);
  check(pago, { 'pago 200': (r) => r.status === 200 });
}

// Cada iteración compra un número distinto: reserva y paga con saldo
export function rifa(datos) {
  const i = exec.scenario.iterationInTest;
  const token = datos.tokens[i];
  const params = { ...json(token), tags: { escenario: 'rifa' } };
  const numeros = JSON.stringify({ numeros: [i + 1] });
  const reserva = http.post(`${API}/rifas/${datos.rifaId}/reservas`, numeros, params);
  if (!check(reserva, { 'reserva 2xx': (r) => r.status >= 200 && r.status < 300 })) return;
  // La compra pide una clave de idempotencia: reintentar con la misma no cobra dos veces
  const compra = http.post(`${API}/rifas/${datos.rifaId}/comprar`, numeros, {
    ...params,
    headers: { ...params.headers, 'Idempotency-Key': `carga-${datos.rifaId}-${i}` },
  });
  check(compra, { 'compra 2xx': (r) => r.status >= 200 && r.status < 300 });
}
