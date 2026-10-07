# Simulador de Trackers

Herramienta de PC para el criterio de salida de la 0.1: **10 Trackers simulados durante 24 h sin perder posiciones, en 3 teléfonos reales**. El teléfono corre el modo Servidor; la PC simula los 10 Trackers.

Cada Tracker simulado usa los mismos casos de uso y el mismo cliente HTTP (OkHttp) que la app: cola, lotes, reintentos, re-registro y firma de cada petición. Solo cambian tres cosas: el GPS es una ruta aleatoria, la cola vive en memoria y la clave de firma es de software, porque la PC no tiene Keystore.

Desde la 0.2, el servidor solo acepta posiciones de dispositivos aprobados. Al arrancar, `run` imprime el nombre y la huella de cada Tracker simulado; apruébalos en la app, en **Dispositivos**. Hasta entonces, sus posiciones esperan en la cola, y los fallos `DEVICE_PENDING_APPROVAL` del inicio son normales.

Desde la 0.4, el servidor solo habla HTTPS y los Trackers lo reconocen por su clave. `run` pide `--fingerprint` con la **huella del servidor** que muestra la pantalla **Servidor** de la app, igual que un teléfono configurado a mano. Con otra huella, la corrida no empieza (`SERVER_IDENTITY_MISMATCH`).

- `run` envía posiciones y anota cada una en `ledger.csv`, junto con lo que respondió el servidor.
- `verify` copia `server.db` del teléfono y comprueba que cada posición generada está ahí una sola vez, con la misma hora y coordenadas.

## Compilar

```sh
export JAVA_HOME=/opt/android-studio/jbr   # o cualquier JDK 17 o más nuevo
./gradlew :tools:simulator:installDist
```

El ejecutable queda en `tools/simulator/build/install/simulator/bin/simulator` (`simulator.bat` en Windows). `simulator help` lista todas las opciones.

No ejecutes `installDist` ni `clean` mientras una corrida está en marcha, porque reemplazan los `.jar` que está usando.

## Prueba rápida en el emulador

1. Instala el build debug, elige el modo Servidor y pulsa **Iniciar servidor**.
2. Redirige el puerto: `adb forward tcp:8787 tcp:8787`.
3. Corre 10 minutos con fallas de red inyectadas:

   ```sh
   simulator run --server https://127.0.0.1:8787 --fingerprint 3F9A-01BC-77D2-E410 \
       --interval 2s --duration 10m --drop 0.05 --lost-ack 0.1 --report-every 1m \
       --out simulator-runs/emulador
   ```

   Cambia `3F9A-01BC-77D2-E410` por la huella de la pantalla **Servidor**. Aprueba los dispositivos «Simulador NN» en **Dispositivos**; las huellas deben coincidir con las de la consola. A mitad de la corrida, detén y vuelve a iniciar el servidor desde la app, y pulsa **Rotar clave ahora** en la tarjeta «Cifrado de extremo a extremo»: los Trackers deben tomar la clave nueva sin perder posiciones.

   Desde la 0.5 el simulador cifra cada envío igual que un teléfono, así que debe ser de la misma versión que la app: recompila con `installDist` después de actualizar el código. Uno de otra versión falla con `SERVER_IDENTITY_MISMATCH` o `ENCRYPTION_REQUIRED`.
4. Al terminar, detén el servidor en la app y verifica:

   ```sh
   simulator verify --run simulator-runs/emulador --pull
   ```

## Criterio de salida: 24 h en 3 teléfonos

### Preparar cada teléfono

- [ ] Build **debug** instalado. `verify --pull` usa `run-as`, que solo funciona en builds debug.
- [ ] Modo Servidor iniciado, con **Aceptar dispositivos nuevos** encendido.
- [ ] Conectado a la corriente, con la optimización de batería desactivada para la app.
- [ ] En la misma Wi-Fi que la PC. Conviene una reserva DHCP para que su IP no cambie.
- [ ] Hora automática en el teléfono y en la PC: el servidor rechaza posiciones con más de 5 min de adelanto.
- [ ] En Android 17, acceso a la red local concedido.
- [ ] La PC no debe suspenderse durante las 24 h.

### Correr

Una instancia por teléfono. Pueden correr las tres a la vez en la misma PC, cada una con su propio `--out`:

```sh
simulator run --server https://192.168.1.50:8787 --fingerprint 3F9A-01BC-77D2-E410 --out simulator-runs/pixel7-24h
```

Cada teléfono tiene su propia huella: cópiala de su pantalla **Servidor**.

Los valores por defecto ya son los del criterio: 10 Trackers, una posición cada 60 s, 24 h. Equivalen a unas 0.17 peticiones por segundo y 14 400 posiciones.

Apenas arranque, aprueba los 10 dispositivos en **Dispositivos** del teléfono, comparando cada huella con la que imprime la consola.

Durante las 24 h, provoca estas situaciones y anota la hora de cada una:

- [ ] Pantalla del teléfono apagada varias horas seguidas, para que entre en Doze.
- [ ] Wi-Fi del teléfono apagado unos 5 minutos.
- [ ] Servidor detenido y vuelto a iniciar desde la app.
- [ ] Teléfono reiniciado, con **Iniciar al encender el teléfono** activado.

Los Trackers guardan las posiciones en su cola mientras el servidor no responde y las reenvían cuando vuelve. Al terminar, o con Ctrl+C, dejan de generar posiciones y vacían su cola durante hasta 10 minutos (`--drain-timeout`). En ese vaciado ya no se inyectan fallas.

### Verificar

1. Espera el resumen final. La última línea debe decir «Lado del Tracker: sin pérdidas».
2. Detén el servidor en la app, para que la copia de la base sea consistente.
3. Ejecuta `simulator verify --run simulator-runs/pixel7-24h --pull --serial <serial de adb>`.
4. El teléfono pasa si el resultado es **APROBADA** y la línea «Corrida» marca 24 h sin «detenida a mano». Guarda `summary.txt` y `verification.txt` de la carpeta de la corrida.

`verify` copia `server.db` dos veces y solo la acepta si ambas copias son idénticas; si el servidor está escribiendo, pide detenerlo. Si el simulador se cerró de golpe, sin vaciar las colas, el resultado es **INCOMPLETA** aunque no falte nada de lo registrado.

| Resultado | Qué significa |
| --- | --- |
| Perdidas | El servidor confirmó la posición pero no está en `server.db`. Es un bug del servidor. |
| Sin entregar | La posición seguía en la cola al terminar: el servidor no respondió durante el vaciado final (`--drain-timeout`). |
| Descartadas | La cola de un Tracker se llenó (`--max-queue`) y tiró las más antiguas. |
| Rechazadas | El servidor la consideró inválida; casi siempre es el reloj de la PC adelantado. |
| Alteradas | Está guardada con otra hora o con otras coordenadas. |
| Inesperadas | Está en `server.db` bajo un dispositivo simulado, pero esta corrida no la generó. |
| Repetidas | Tiene más de una fila en `server.db`. El índice único lo impide; si aparece, es un bug. |

Cada corrida registra 10 dispositivos nuevos. Bórralos desde **Dispositivos** en la app antes de la siguiente.

## Criterio de salida de 0.6: 30 días en menos de 2 s

`history` llena de una vez el historial de un Tracker: 30 días hasta ahora, una posición por minuto (43 200), con una rutina de casa, trayecto al trabajo, paseo al mediodía y salidas de fin de semana. Lo sube cifrado, como un teléfono, al ritmo que permite el límite del servidor (60 envíos de 100 posiciones por minuto, unos 8 minutos).

```sh
simulator history --server https://192.168.1.50:8787 --fingerprint 3F9A-01BC-77D2-E410 --out simulator-runs/historial
```

1. Aprueba «Simulador 01» en **Dispositivos** cuando lo pida.
2. Al terminar, en **Dispositivos** abre «Simulador 01» → **Ver recorridos** → **30 días**.
3. El tiempo hasta que el recorrido queda dibujado sale en logcat: `adb logcat -s DeviceHistory`, en la línea `Historial: 43200 posiciones (… dibujadas), datos … ms, en pantalla … ms`. El criterio es que «en pantalla» quede por debajo de 2000 ms en un teléfono de gama media.
4. `simulator verify --run simulator-runs/historial --pull` confirma que las 43 200 están en server.db.

Con `--days` y `--interval` se cambia el tamaño; `--trackers` da varios dispositivos, cada uno con su historial.

## Archivos de una corrida

| Archivo | Contenido |
| --- | --- |
| `ledger.csv` | Una línea por evento: dispositivo, posición generada, aceptada, duplicada, rechazada o descartada, y el cierre de la corrida. Se escribe sobre la marcha, así que una corrida interrumpida también se puede verificar. |
| `summary.txt` | El resumen que imprime `run`. |
| `server-db/` | La copia de `server.db` que hizo `verify --pull`. |
| `verification.txt` | El resultado de `verify`. |

Códigos de salida, también tras Ctrl+C: 0 si todo salió bien, 1 si hubo pérdidas, la corrida quedó incompleta o el servidor no respondió, 2 si las opciones son inválidas.
