# PrivateTracker Relay

Un relay deja que los Trackers lleguen a un servidor PrivateTracker que no tiene dirección pública: un teléfono con datos móviles, una casa detrás de CGNAT o un router sin redirección de puertos. Corre en un equipo que sí la tiene, normalmente un VPS barato, con un dominio.

El servidor abre un túnel hacia el relay y lo mantiene. Cuando un Tracker llega al relay, el relay le pide al servidor una conexión más por ese túnel y pasa por ella los bytes del Tracker tal como llegan.

**El relay no abre nada.** La conexión TLS del Tracker termina en el servidor, con la clave que el Tracker fijó al emparejarse, como sin relay; dentro va además el cifrado de extremo a extremo. El relay solo lee el nombre que pide cada conexión (`9e9d7678d67df283.relay.example.org`, la huella del servidor) y ve direcciones IP, horarios y tamaños. No ve rutas, dispositivos, posiciones ni cabeceras. Un relay malicioso puede cortar el servicio, pero no leer ni falsificar nada: el Tracker rechaza cualquier clave que no sea la de su servidor.

## Lo que necesitas

- Un equipo con IP pública y el puerto 443/TCP abierto.
- Un dominio en el que puedas crear dos registros DNS hacia ese equipo: el nombre del relay y todos los nombres bajo él.

    ```
    relay.example.org.     A     203.0.113.7
    *.relay.example.org.   A     203.0.113.7
    ```

- Java 17 o más nuevo, o Docker.

El relay no necesita certificado de ninguna autoridad: los servidores fijan su clave por su huella, y a los Trackers no les presenta ninguna.

## Compilar

```sh
export JAVA_HOME=/opt/android-studio/jbr   # o cualquier JDK 17 o más nuevo
./gradlew :server:relay:installDist
```

Queda en `server/relay/build/install/privatetracker-relay/`.

## Con Docker

Edita `server/relay/docker-compose.yml` (dominio y huellas) y en esa carpeta:

```sh
docker compose up -d
docker exec privatetracker-relay privatetracker-relay fingerprint
```

La última orden imprime la huella del relay, que necesita cada servidor. El contenedor escucha en el 8443 y Compose lo publica como 443; corre sin capacidades, con el sistema de archivos de solo lectura.

## Con systemd, sin Docker

```sh
sudo useradd --system --home-dir /var/lib/privatetracker-relay --shell /usr/sbin/nologin privatetracker-relay
sudo cp -r server/relay/build/install/privatetracker-relay /opt/
sudo cp server/relay/packaging/privatetracker-relay.service /etc/systemd/system/
sudoedit /etc/systemd/system/privatetracker-relay.service   # dominio y huellas
sudo systemctl enable --now privatetracker-relay
sudo -u privatetracker-relay /opt/privatetracker-relay/bin/privatetracker-relay fingerprint --data /var/lib/privatetracker-relay
```

La unidad da al servicio solo `CAP_NET_BIND_SERVICE`, para el puerto 443.

## Conectar un servidor

1. Agrega la huella del servidor a la lista del relay (`--allow` o `PRIVATETRACKER_RELAY_ALLOW`) y reinícialo. Un servidor que no está en la lista no entra.
2. En el servidor:
    - **Linux:** `--relay relay.example.org --relay-fingerprint <huella del relay>`, o `PRIVATETRACKER_RELAY` y `PRIVATETRACKER_RELAY_FINGERPRINT`. `privatetracker-server status` dice si el túnel está conectado.
    - **Teléfono:** Ajustes › Servidor › Relay: nombre y huella. La pantalla Servidor muestra el estado.

Los códigos QR nuevos llevan la dirección del relay después de las de la red local. Los Trackers ya emparejados la aprenden solos: el servidor firma sus direcciones en cada comprobación de identidad, y un Tracker la repite al menos una vez al día. Para eso tiene que alcanzar al servidor una vez después de activar el relay, normalmente en casa. Fuera de casa, cuando la dirección de la red local deja de responder, el Tracker pasa a la del relay.

### La app web por el relay

Un servidor Linux puede ofrecer también su app web por el relay, con `--relay-web true`. Queda en `https://web-<huella>.relay.example.org`. El navegador avisará por el certificado, salvo que el servidor tenga uno válido para ese nombre. Esto expone la página de inicio de sesión a internet: usa una contraseña larga.

**La app web por el relay confía en quien opera el relay.** Los Trackers fijan la clave de su servidor; un navegador no. Quien controla el DNS del dominio del relay puede conseguir un certificado válido para `web-<huella>.relay.example.org`, terminar TLS él mismo y ver la contraseña y las posiciones, y el navegador mostraría menos avisos que con el certificado propio del servidor. Activa `--relay-web` solo con un relay y un dominio tuyos.

## Órdenes y ajustes

| Orden | Qué hace |
| --- | --- |
| `serve` | Arranca el relay |
| `fingerprint` | La huella de la clave del relay; la crea si no existe |
| `health [--quiet]` | Pregunta al relay de este equipo si responde, como haría un servidor (lo usa el HEALTHCHECK) |

| Opción | Variable | Por omisión |
| --- | --- | --- |
| `--data DIR` | `PRIVATETRACKER_RELAY_DATA` | `~/.local/share/privatetracker-relay` (`/data` en Docker) |
| `--domain NOMBRE` | `PRIVATETRACKER_RELAY_DOMAIN` | obligatoria |
| `--port N` | `PRIVATETRACKER_RELAY_PORT` | 443 (8443 en la imagen de Docker) |
| `--bind IP` | `PRIVATETRACKER_RELAY_BIND` | 0.0.0.0 |
| `--allow HUELLA` (repetible) | `PRIVATETRACKER_RELAY_ALLOW` (separadas por comas) | obligatoria |
| | `PRIVATETRACKER_LOG_LEVEL` | info |

Límites: 1024 conexiones a la vez; 32 por dirección IPv4 o por bloque /64 de IPv6 hasta que la conexión resulta ser de un servidor de la lista; 15 s para decir a qué nombre va cada conexión; 256 Trackers a la vez por servidor (el servidor acepta 64). El relay le pasa al servidor la dirección de cada Tracker, para que sus límites cuenten por Tracker y no todos juntos. Registra cuándo se conecta y se desconecta cada servidor y los servidores rechazados; de los Trackers no registra nada.

## Respaldo

El directorio de datos guarda `relay-identity.p12`, la clave del relay. Si se pierde, el relay crea otra al arrancar y cada servidor tiene que configurar la huella nueva. Los Trackers no se enteran: nunca confían en la clave del relay.
