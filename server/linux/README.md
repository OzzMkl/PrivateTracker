# PrivateTracker Server para Linux y Docker

El mismo servidor que corre un teléfono en modo Servidor, pero en una PC, un servidor casero, una Raspberry Pi de 64 bits o un VPS. Habla el mismo protocolo, cifra igual y los Trackers lo reconocen igual: por la clave que fijan al emparejarse. Se administra desde el navegador, con la [app web](#app-web), o desde la línea de comandos.

Diferencias con el servidor del teléfono:

- **Claves en archivos.** Linux no tiene el Keystore de Android. La clave de identidad (que también es la de TLS) y las claves de cifrado viven en el directorio de datos, en archivos que solo puede leer el usuario del servidor (permisos 0600, dentro de un directorio 0700). Las protege el sistema de archivos, igual que a la clave TLS de nginx; no tienen contraseña.
- **Sin anuncio en la red local (mDNS).** Los Trackers llegan por las URL de su invitación. Si la dirección del servidor cambia, empareja el teléfono otra vez.
- **Geocercas.** Se dibujan en la app web. Los avisos llegan a las páginas abiertas de la app web y, si configuras un webhook, a ntfy, Gotify o Home Assistant aunque no haya ninguna abierta.
- **Sin API de lectura.** `GET /api/v1/devices` responde 403 siempre, también desde el propio equipo: en un servidor con varias cuentas, «local» no significa «el dueño». Lo que hay en el servidor se consulta en la app web, que pide iniciar sesión, o con `privatetracker-server devices`.

## Compilar

```sh
export JAVA_HOME=/opt/android-studio/jbr   # o cualquier JDK 17 o más nuevo
./gradlew :server:linux:installDist
```

Queda en `server/linux/build/install/privatetracker-server/`: `bin/privatetracker-server` y sus bibliotecas (unos 28 MB). Para correrlo fuera de Docker basta un Java 17 o más nuevo (en Debian, `apt install openjdk-21-jre-headless`).

## Con Docker

```sh
./gradlew :server:linux:installDist
docker build -t privatetracker-server server/linux
docker run -d --name privatetracker --restart unless-stopped \
    -p 8787:8787 -p 8788:8788 \
    -e PRIVATETRACKER_PUBLIC_URLS=https://192.168.1.20:8787 \
    -e TZ=America/Mexico_City \
    -v privatetracker-data:/data \
    privatetracker-server
```

O con `server/linux/docker-compose.yml`: edita `PRIVATETRACKER_PUBLIC_URLS` y corre `docker compose up -d` en esa carpeta. Ese archivo ya corre el contenedor endurecido: sistema de archivos de solo lectura (salvo `/data` y un `/tmp` en memoria), sin capacidades y con `no-new-privileges`.

`PRIVATETRACKER_PUBLIC_URLS` es obligatoria en Docker: dentro del contenedor el servidor solo ve las direcciones del contenedor, que los teléfonos no alcanzan. Pon la URL con la que los teléfonos llegan al equipo: su IP en la red local, su IP de la VPN (WireGuard, Tailscale) o un nombre público. Si pones varias, sepáralas con comas; el Tracker las prueba en orden.

Las órdenes de administración se corren dentro del contenedor:

```sh
docker exec -it privatetracker privatetracker-server pair
docker exec privatetracker privatetracker-server devices
docker exec -it privatetracker privatetracker-server users add ana
```

La imagen pesa unos 470 MB, casi todo la base de Java (`eclipse-temurin:21-jre`). `docker stop` lo apaga en orden: durante un segundo responde 503 para que los Trackers guarden sus posiciones, cierra las sesiones y sale.

## Con systemd, sin Docker

```sh
sudo useradd --system --home-dir /var/lib/privatetracker --shell /usr/sbin/nologin privatetracker
sudo cp -r server/linux/build/install/privatetracker-server /opt/
sudo cp server/linux/packaging/privatetracker-server.service /etc/systemd/system/
sudoedit /etc/systemd/system/privatetracker-server.service   # PRIVATETRACKER_PUBLIC_URLS
sudo systemctl enable --now privatetracker-server
journalctl -u privatetracker-server -f
```

Las órdenes se corren como ese usuario:

```sh
sudo -u privatetracker /opt/privatetracker-server/bin/privatetracker-server --data /var/lib/privatetracker pair
```

Sin `PRIVATETRACKER_PUBLIC_URLS`, fuera de un contenedor, las invitaciones llevan las direcciones de red local y de VPN del equipo.

## Emparejar un teléfono

```sh
privatetracker-server pair
```

Muestra un código QR en la terminal. En el teléfono: **PrivateTracker > Rastreo > Escanear QR**. El teléfono queda emparejado y aprobado, sin escribir nada. La orden espera hasta que el teléfono lo usa (vale 10 minutos). Si la cancelas con Ctrl+C, el código deja de servir.

- `pair --png qr.png` guarda además el código como imagen, por si la terminal no lo muestra bien.
- La orden también imprime el enlace `privatetracker://pair?...`: abierto en el teléfono, hace lo mismo que escanear.

Para configurar un Tracker a mano: en **Ajustes** del teléfono escribe la URL del servidor y su huella (`privatetracker-server fingerprint`). El dispositivo queda **pendiente** hasta que lo apruebes:

```sh
privatetracker-server devices
privatetracker-server approve 6f1c2a8e     # o su nombre exacto: approve "Pixel de Ana"
```

También puedes emparejar desde la app web: **Dispositivos > Emparejar un teléfono** muestra el mismo código QR en el navegador.

Antes de aprobar, compara la huella de la lista con la que muestra el teléfono en **Rastreo**. Cualquiera que alcance el puerto puede pedir entrar con el nombre que quiera, así que no apruebes por el nombre: `approve --all-pending` solo conviene cuando acabas de revisar la lista. Si todos tus teléfonos se emparejan con `pair`, arranca el servidor con `PRIVATETRACKER_ACCEPT_NEW_DEVICES=false` y nadie más podrá pedir entrar.

## App web

El servidor sirve una app web en su propio puerto, el **8788**: el mapa en vivo, los dispositivos (aprobar, rechazar, borrar, emparejar con QR), el historial con descarga GPX y CSV, el editor de geocercas con sus eventos y el estado del servidor (huellas, clave de cifrado y su rotación). Funciona en el navegador de una PC o de un teléfono.

1. Crea una cuenta en el equipo del servidor. Pide la contraseña dos veces, sin mostrarla:

    ```sh
    privatetracker-server users add ana
    ```

    La contraseña necesita **15 caracteres o más** y ninguna regla de mayúsculas o símbolos: una frase de varias palabras sirve. Se rechazan las que alguien probaría primero (secuencias de teclado, el nombre de la cuenta o de la app, contraseñas filtradas conocidas). Sin terminal (un script, `docker exec` sin `-t`), la contraseña se lee de la primera línea de la entrada estándar.

2. Abre `https://<dirección del equipo>:8788` e inicia sesión.

Todas las cuentas pueden hacer todo; existen para que cada persona tenga su propia contraseña. Cada cuenta cambia la suya en la app web (su nombre, arriba a la derecha); `users passwd` la cambia desde el equipo y `users remove` borra la cuenta. Las dos cierran todas sus sesiones. Una sesión dura 30 días. Cada dirección tiene 10 intentos de inicio de sesión cada 15 minutos, y cada cuenta 50. Si alguien gasta los intentos de tu cuenta probando contraseñas, un navegador desde el que ya entraste sigue entrando con la contraseña correcta: lleva una cookie firmada por el servidor que lo reconoce (sin la contraseña no abre nada).

### El aviso del navegador

Sin más ajustes, el puerto web presenta el mismo certificado autofirmado que ven los Trackers, y el navegador avisa la primera vez porque nadie más lo firmó. Para comprobar que es tu servidor, compara el SHA-256 del certificado que muestra el navegador con el de `privatetracker-server status` (línea «Certificado web») o el de la página **Servidor**.

Para que el navegador no avise, dale al puerto web un certificado en el que confíe, con `--web-cert` y `--web-key` (o `PRIVATETRACKER_WEB_CERT` y `PRIVATETRACKER_WEB_KEY`) en formato PEM y la clave sin contraseña:

- **Tailscale:** activa MagicDNS y **HTTPS Certificates** en la página DNS de la consola de Tailscale, y `tailscale cert casa.tail1234.ts.net` crea un certificado de Let's Encrypt para el nombre del equipo en tu tailnet. Abre la app web con ese nombre. Vale 90 días y renovarlo es cosa tuya (vuelve a correr la orden, por ejemplo con un temporizador de systemd). Ese nombre queda en el registro público de Certificate Transparency: no uses uno que diga algo de ti.
- **Let's Encrypt** con el reto DNS (`certbot certonly --manual --preferred-challenges dns -d casa.example.org`, o el plugin de tu proveedor DNS): sirve aunque el equipo no esté expuesto a internet. Usa `fullchain.pem` y `privkey.pem`.

El servidor revisa los archivos cada minuto y usa el certificado renovado sin reiniciar. Con un certificado propio también envía HSTS. Los Trackers no se enteran: siguen en el 8787 con la clave que fijaron.

La clave se lee en PKCS#8 (`BEGIN PRIVATE KEY`), SEC1 (`BEGIN EC PRIVATE KEY`) o PKCS#1 (`BEGIN RSA PRIVATE KEY`). El usuario del servidor debe poder leer los dos archivos; con systemd, fuera de `/home` (la unidad usa `ProtectHome`).

### El mapa

Las teselas vienen de `tile.openstreetmap.org`, como en la app del teléfono: el navegador las pide directamente a OpenStreetMap, que ve tu dirección IP y la zona del mapa que miras, pero no las posiciones de los dispositivos. Para usar otro servidor de teselas (uno propio, por ejemplo) define `PRIVATETRACKER_TILE_URL=https://teselas.example.org/{z}/{x}/{y}.png` y su `PRIVATETRACKER_TILE_ATTRIBUTION`.

### Avisos de geocercas

Con la app web abierta, cada entrada o salida aparece en una tarjeta en la esquina y, si lo permites con **Activar avisos**, como notificación del navegador. Con todas las páginas cerradas, el cruce se anota en **Eventos recientes** sin avisar, salvo que configures un webhook:

```sh
PRIVATETRACKER_ALERT_WEBHOOK=https://ntfy.sh/casa-alertas-7f3k          # ntfy: el texto, con título
PRIVATETRACKER_ALERT_WEBHOOK=https://gotify.casa.lan/message?token=...  # Gotify: JSON con title, message y priority
PRIVATETRACKER_ALERT_WEBHOOK=http://homeassistant.local:8123/api/webhook/privatetracker   # Home Assistant: JSON
```

El formato es `ntfy` si el host es `ntfy.sh` o empieza con `ntfy.`, y JSON en los demás casos; `PRIVATETRACKER_ALERT_WEBHOOK_FORMAT` lo fija. `PRIVATETRACKER_ALERT_WEBHOOK_TOKEN` se envía como `Authorization: Bearer` (para un ntfy con control de acceso). El aviso lleva los nombres del dispositivo y de la geocerca, entrada o salida y la hora, **nunca coordenadas**; aun así, un tema público de ntfy.sh lo puede leer cualquiera que adivine su nombre, así que usa uno largo o tu propio ntfy. Cada aviso se intenta tres veces; la página **Servidor** muestra el último envío y el último error.

## Órdenes

| Orden | Qué hace |
| --- | --- |
| `serve` | Arranca el servidor; es lo que corre la imagen de Docker |
| `pair [--png ARCHIVO]` | Código QR para emparejar un teléfono; espera a que lo use |
| `devices` | Dispositivos con su estado, aprobación, última vez y huella |
| `approve <disp.>` / `approve --all-pending` | Aprueba uno, o todos los que esperan |
| `reject <disp.>` | Rechaza: conserva su historial y no acepta sus posiciones |
| `remove <disp.>` | Borra el dispositivo con todo su historial |
| `rotate-key` | Cambia ya la clave de cifrado y borra las anteriores; los Trackers piden la nueva solos |
| `status [--quiet]` | Estado del servidor; con `--quiet`, solo el código de salida (lo usa el HEALTHCHECK) |
| `fingerprint` | La huella de la clave del servidor |
| `users` | Las cuentas de la app web |
| `users add <nombre>` | Crea una cuenta; pide la contraseña |
| `users passwd <nombre>` | Cambia la contraseña de una cuenta y cierra sus sesiones |
| `users remove <nombre>` | Borra una cuenta y cierra sus sesiones |

`<disp.>` es el id del dispositivo, sus primeros caracteres (4 o más) o su nombre exacto. Todas las órdenes aceptan `--data DIR`; las que no son `serve` hablan con el servidor en marcha por el socket `admin.sock` de ese directorio. Códigos de salida: 0 bien, 1 error, 2 uso incorrecto, 3 el servidor no está corriendo.

## Ajustes de `serve`

Cada opción se puede dar como argumento o con su variable de entorno. El argumento gana.

| Opción | Variable | Por omisión |
| --- | --- | --- |
| `--data DIR` | `PRIVATETRACKER_DATA` | `~/.local/share/privatetracker` (`/data` en Docker) |
| `--port N` | `PRIVATETRACKER_PORT` | 8787 |
| `--bind IP` | `PRIVATETRACKER_BIND` | 0.0.0.0 |
| `--name NOMBRE` | `PRIVATETRACKER_NAME` | PrivateTracker Server |
| `--public-url URL` (repetible) | `PRIVATETRACKER_PUBLIC_URLS` (separadas por comas) | ninguna |
| `--retention-days N` | `PRIVATETRACKER_RETENTION_DAYS` | 30 |
| `--online-threshold S` | `PRIVATETRACKER_ONLINE_THRESHOLD` | 300 |
| `--accept-new-devices true\|false` | `PRIVATETRACKER_ACCEPT_NEW_DEVICES` | true; con false solo entran los emparejados con `pair` |
| `--web-port N` | `PRIVATETRACKER_WEB_PORT` | 8788; con 0 no hay app web |
| `--web-bind IP` | `PRIVATETRACKER_WEB_BIND` | la de `--bind` |
| `--web-cert ARCHIVO` y `--web-key ARCHIVO` | `PRIVATETRACKER_WEB_CERT` y `PRIVATETRACKER_WEB_KEY` | el certificado autofirmado del servidor |
| `--tile-url URL` | `PRIVATETRACKER_TILE_URL` | las teselas de OpenStreetMap |
| `--tile-attribution TEXTO` | `PRIVATETRACKER_TILE_ATTRIBUTION` | © OpenStreetMap |
| `--alert-webhook URL` | `PRIVATETRACKER_ALERT_WEBHOOK` | ninguno |
| `--alert-webhook-format json\|ntfy` | `PRIVATETRACKER_ALERT_WEBHOOK_FORMAT` | ntfy si el host es `ntfy.*`; si no, json |
| `--alert-webhook-token TOKEN` | `PRIVATETRACKER_ALERT_WEBHOOK_TOKEN` | ninguno; mejor por variable, porque otros usuarios del equipo ven los argumentos con `ps` |
| | `PRIVATETRACKER_LOG_LEVEL` | info |

## Red

- El servidor solo habla HTTPS, con un certificado autofirmado. Los Trackers no confían en autoridades ni en nombres: confían en la clave que fijaron al emparejarse.
- **No pongas delante del puerto 8787 un proxy que termine TLS** (nginx, Caddy, Traefik con certificados propios): el Tracker vería otra clave y lo rechazaría. Un reenvío TCP sin descifrar (`stream` de nginx, el `tcp` de Traefik, una redirección de puertos del router) sí funciona. Para el puerto web basta `--web-cert`; un proxy delante de él no es necesario.
- Abre el puerto en el firewall del equipo solo hacia donde estén los teléfonos (la LAN o la VPN). Exponerlo a internet es posible, porque todo va cifrado y firmado, pero la recomendación sigue siendo una VPN; si lo expones, usa `PRIVATETRACKER_ACCEPT_NEW_DEVICES=false`.
- El puerto web muestra todas las posiciones a quien inicie sesión: ábrelo solo hacia donde lo usas, mejor por VPN que a internet. `--web-bind` lo deja escuchar en una sola interfaz, por ejemplo la de Tailscale.
- **Docker se salta el firewall del equipo** (ufw, firewalld) en los puertos publicados. Para escuchar solo en una interfaz, publica el puerto con su dirección: `-p 192.168.1.20:8787:8787`, o con la de la VPN.
- En Kubernetes, un Service llamado `privatetracker` crea la variable `PRIVATETRACKER_PORT=tcp://...`, y uno llamado `privatetracker-web`, `PRIVATETRACKER_WEB_PORT`: el servidor las rechaza como puertos. Llama distinto a los Services o usa `enableServiceLinks: false`.

## Respaldos y migración

Todo está en el directorio de datos:

| Archivo | Qué es |
| --- | --- |
| `identity.p12` | La clave de identidad y de TLS. **Si se pierde, hay que emparejar otra vez todos los teléfonos.** |
| `encryption-vault.p12`, `encryption-keys.json` | Las claves de cifrado de la última semana |
| `server.db` | Dispositivos, posiciones, sesiones y geocercas (el mismo formato que en el teléfono) |
| `web-accounts.json` | Las cuentas de la app web, con sus contraseñas en hash (PBKDF2) |
| `web-sessions.json` | Las sesiones abiertas de la app web; solo el hash de cada una, que no abre ninguna sesión |
| `web-browser.key` | Firma las cookies de los navegadores conocidos; si se pierde, cada navegador cuenta como nuevo una vez |
| `admin.sock` | El socket de las órdenes; existe solo mientras el servidor corre |

Para respaldar, detén el servidor y copia el directorio completo; guarda la copia con el mismo cuidado que una clave privada. Para mudarlo a otro equipo, copia el directorio con sus permisos y arranca ahí el servidor: los teléfonos no notan nada si la URL sigue siendo la misma; si cambia, empareja de nuevo.

Si `identity.p12` existe pero no abre, el servidor no arranca y no crea otra clave, porque los teléfonos emparejados no la reconocerían. Recupéralo del respaldo.

## Probarlo con el simulador

```sh
privatetracker-server serve --data /tmp/pt --port 8789 --web-port 0 &
simulator run --server https://127.0.0.1:8789 --fingerprint "$(privatetracker-server fingerprint --data /tmp/pt)" \
    --interval 2s --duration 10m --out simulator-runs/linux
privatetracker-server approve --all-pending --data /tmp/pt     # cuando aparezcan los «Simulador NN»
# al terminar:
kill %1
simulator verify --run simulator-runs/linux --db /tmp/pt/server.db
```

La ruta del directorio de datos debe ser corta (Linux admite 107 bytes para la ruta del socket).
