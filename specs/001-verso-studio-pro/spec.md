# Especificación: Verso Studio Pro (v1.5.0)

**Feature Directory**: `specs/001-verso-studio-pro`
**Estado**: En implementación

## Resumen
Mejora integral de **Verso** para llevar la experiencia de creación de historias musicales al nivel de un estudio creativo completo, destacando una textura de tiza realista tipo Instagram, pincel flecha, filtros de color para la foto, alineación y tamaño de texto, y persistencia automática del estilo favorito del usuario.

## Requisitos Funcionales

1. **Textura de Tiza Realista (Stamp Procedural)**
   - El pincel `Tiza` debe renderizar una textura porosa y orgánica de tiza seca sobre pizarra/papel rugoso, sin parpadeo entre frames del MP4 exportado.
2. **Pincel Flecha**
   - El usuario debe poder dibujar trazos que terminan automáticamente con una cabeza de flecha orientada según la dirección del trazo.
3. **Filtros de Foto**
   - El usuario debe poder aplicar filtros (`Original`, `Cálido`, `Frío`, `B&N`, `Vintage`, `Nocturno`) sobre su foto tanto en el preview como en el MP4 final.
4. **Alineación y Tamaño de Letras**
   - El usuario debe poder alinear el texto (`Izquierda`, `Centro`, `Derecha`) y ajustar el tamaño (`0.6x..1.8x`) desde un slider además del gesto *pinch-to-zoom*.
5. **Persistencia de Preferencias**
   - Al cerrar y reabrir la app, se deben restaurar el último estilo, animación, efecto, tipografía, alineación, filtro y estado de tarjeta de canción.

## Criterios de Éxito
- El MP4 exportado refleja exactamente la textura de tiza, filtros, alineación y colores vistos en pantalla.
- Las pruebas unitarias (`testDebugUnitTest`) y el empaquetado (`assembleDebug`) terminan sin errores.
