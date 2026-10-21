# Protocolo v1, congelado

Estos archivos son la forma exacta de cada mensaje del protocolo v1, congelado con la versión 1.0
(16 de octubre de 2026). `ProtocolFreezeTest` comprueba que el código los lee y los escribe igual.

Si una prueba falla aquí, el cambio rompe a los Trackers y servidores que ya existen. En v1 solo se
permite **agregar** campos opcionales, rutas y códigos de error; nunca renombrar, quitar ni cambiar el
tipo de un campo, ni lo que se firma. Un cambio incompatible es otra versión: `/api/v2`, junto a v1.

Las claves y firmas de estos ejemplos son texto inventado en Base64, no claves reales.
