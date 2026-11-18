# Instalador PWA

App Android que toma la dirección de una PWA y le crea un acceso directo propio (nombre e ícono propios, pantalla completa, sin barra de direcciones y con su propia entrada en recientes).

## Cómo se usa

1. Abre la app y pega la URL de la PWA (o compártela desde el navegador hacia **Instalador PWA**).
2. Pulsa **Buscar app**: lee el `manifest` de la página (nombre, ícono, color).
3. Cambia el nombre si quieres y pulsa **Instalar en pantalla de inicio**.
4. Confirma el diálogo del sistema.

## Descargar el APK

Cada push a `main` compila el APK con GitHub Actions: pestaña **Actions** → última corrida → artefacto `instalador-pwa-apk`.

## Limitaciones

- Usa el `WebView` del sistema, no el motor completo de Chrome: notificaciones push, cámara/micrófono y otras APIs avanzadas pueden no funcionar.
- Los accesos directos son de esta app; no son APKs independientes como los WebAPK de Chrome.
