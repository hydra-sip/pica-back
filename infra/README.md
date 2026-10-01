# Staging en EC2

Una instancia con Docker Compose: Postgres, la API, la web y Caddy adelante con HTTPS de Let's Encrypt. La web y la API salen del mismo dominio, así que no hay CORS de por medio.

```
Internet ─ 443 ─► caddy ─┬─ /api/*, /oauth2/*, /login/oauth2/*, /.well-known/jwks.json, /actuator/health ─► api:8080 ─► db:5432
                         └─ el resto ─► web:80 (nginx con el build de React)
```

Cada merge a `dev` corre el CI, publica la imagen en GHCR (`ghcr.io/hydra-sip/pica-back:dev`), entra por SSH, actualiza la API y pega a `/actuator/health`. pica-web hace lo mismo con la web.

Costo aproximado: t3.small + 30 GB + IP pública ≈ 21 USD por mes, de los créditos.

## 1. La instancia (una sola vez)

1. AWS, región `us-east-1` → EC2 → Launch instance:
   - Ubuntu Server 24.04 LTS, `t3.small` (2 GB), disco de 30 GB gp3.
   - Key pair nuevo (`pica-staging`): es para entrar vos.
   - Security group: 22, 80 y 443 desde cualquier lado. El 22 tiene que estar abierto porque el CI de GitHub no tiene IP fija; Ubuntu ya viene sin login por contraseña.
2. Elastic IP → Allocate → Associate a la instancia. Sin esto la IP cambia cada vez que se reinicia.
3. Billing → Budgets: alerta al llegar a 20 USD en el mes.
4. [DuckDNS](https://www.duckdns.org): un subdominio (por ejemplo `pica-hydra`) apuntando a la Elastic IP.

## 2. Preparar la máquina

```bash
ssh -i pica-staging.pem ubuntu@pica-hydra.duckdns.org

# Docker desde el repositorio oficial
sudo apt-get update && sudo apt-get install -y ca-certificates curl
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo $VERSION_CODENAME) stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list
sudo apt-get update && sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin

# 2 GB de swap, por si la JVM y Postgres se juntan
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab

# Usuario con el que entra el CI
sudo adduser --disabled-password --gecos "" deploy
sudo usermod -aG docker deploy
sudo mkdir -p /opt/pica && sudo chown deploy:deploy /opt/pica
```

## 3. La clave del CI

En tu máquina, una clave solo para el CI:

```bash
ssh-keygen -t ed25519 -N "" -C ci-pica -f ci-pica
ssh-keyscan pica-hydra.duckdns.org > known_hosts-pica
```

En la instancia, la pública (`ci-pica.pub`) va en `authorized_keys` de `deploy`:

```bash
sudo install -d -m 700 -o deploy -g deploy /home/deploy/.ssh
echo "<contenido de ci-pica.pub>" | sudo tee /home/deploy/.ssh/authorized_keys
sudo chown deploy:deploy /home/deploy/.ssh/authorized_keys && sudo chmod 600 /home/deploy/.ssh/authorized_keys
```

En GitHub, en pica-back y en pica-web, Settings → Secrets and variables → Actions:

| Tipo | Nombre | Valor |
|---|---|---|
| Secret | `STAGING_SSH_KEY` | el contenido de `ci-pica` (la privada) |
| Secret | `STAGING_SSH_KNOWN_HOSTS` | el contenido de `known_hosts-pica` |
| Variable | `STAGING_HOST` | `pica-hydra.duckdns.org` |
| Variable | `STAGING_SSH_USER` | `deploy` |

Después borrar `ci-pica` de tu máquina. Mientras `STAGING_HOST` no exista, el job `deploy` se saltea.

## 4. Secretos y primer arranque

Las imágenes tienen que estar publicadas: aparecen con el primer merge a `dev` de cada repo. En github.com/orgs/hydra-sip/packages, `pica-back` y `pica-web` → Package settings → Change visibility → Public, así la instancia las baja sin login.

```bash
sudo -iu deploy
cd /opt/pica
# docker-compose.yml, Caddyfile y .env.example de este directorio (con scp, o pegándolos)
cp .env.example .env && chmod 600 .env

# Claves del JWT, en una sola línea cada una
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out jwt.pem
sed -i "s|^JWT_PRIVATE_KEY=.*|JWT_PRIVATE_KEY=$(tr -d '\n' < jwt.pem)|" .env
sed -i "s|^JWT_PUBLIC_KEY=.*|JWT_PUBLIC_KEY=$(openssl pkey -in jwt.pem -pubout | tr -d '\n')|" .env
rm jwt.pem

# Clave de la base
sed -i "s|^POSTGRES_PASSWORD=.*|POSTGRES_PASSWORD=$(openssl rand -hex 24)|" .env

nano .env    # DOMINIO, ADMIN_INITIAL_PASSWORD, GOOGLE_* y, si hay, MAIL_*
docker compose up -d
curl https://pica-hydra.duckdns.org/actuator/health
```

`GOOGLE_CLIENT_ID` y `GOOGLE_CLIENT_SECRET` son obligatorios: fuera de `dev`, `GoogleClientCheck` no deja arrancar la API sin ellos. Se sacan de Google Cloud → APIs & Services → Credentials → OAuth client ID (Web application), con la redirect URI `https://<dominio>/login/oauth2/code/google`.

## Comandos útiles

```bash
cd /opt/pica
docker compose ps
docker compose logs -f api          # sin MAIL_HOST, acá sale el link de verificación
docker compose restart api
docker compose exec db psql -U pica pica
df -h /                             # el disco, de vez en cuando
```
