# Especificación: Verso Studio 2.0 — Rediseño Integral de Interfaz

**Feature Directory**: `specs/002-verso-ui-redesign`
**Versión objetivo**: `2.0.0` (`versionCode = 16`)
**Estado**: En implementación

## Auditoría Base (Código + REA APK)
- **Inspección REA sobre `app-debug.apk`**: 475 nodos de manifiesto, 554 entradas en el paquete, 0 contradicciones de integridad.
- **Problemas detectados en la UX actual (`v1.11.0`)**:
  1. **Dock inferior con scroll horizontal (6 elementos)** que mezcla selectores de medios (`Foto`, `Música`) con herramientas de edición (`Tramo`, `Estilo`, `Texto`, `Dibujar`).
  2. **Pestaña `Estilo` sobrecargada** con 2 interruptores grandes + botón de contorno + 3 filas de chips horizontales + switch de tarjeta de canción en un mismo scroll vertical.
  3. **Pestaña `Texto` saturada** mezclando tipografías, selector de color dual, barra de tono, tamaño, alineación en texto abreviado (`Izq`, `Cen`, `Der`), filtros de foto y oscurecido de fondo.
  4. **Falta de presets rápidos** para lograr estilos populares (IG Apilado, Cine 3D, Neón Club, Karaoke Pop) en un solo toque.
  5. **Barra de reproducción del lienzo no interactiva** (`LinearProgressIndicator` sin soporte para deslizar/buscar dentro del tramo).
  6. **Cabecera superior recargada** con botones secundarios dispersos (`Carpeta .lrc`, `Actualizaciones`).

## Requisitos Funcionales y de UX
1. **Dock Inferior Fijo de 5 Pestañas Equilibradas**:
   - `Música`, `Estilos`, `Texto`, `Foto`, `Dibujar` distribuidas uniformemente sin scroll horizontal.
2. **Presets Creativos de Un Toque (`VERSO_PRESETS`)**:
   - 6 recetas curadas aplicables al instante desde `Estilos → Presets`.
3. **Sub-pestañas Segmentadas (`StudioSegmentedBar`)**:
   - `Estilos`: `Presets` | `Diseño` | `Efectos`
   - `Texto`: `Fuente` | `Color`
4. **Pestaña Dedicada `Foto`**:
   - Agrupa cambiar foto, ajuste manual de contorno, efecto `Letras detrás de la persona`, filtros de color y oscurecido de fondo.
5. **Rail Flotante de Acceso Rápido sobre el Lienzo 9:16**:
   - Botones verticales para `3D`, `Contorno` (cuando 3D está activo), `Latido` y `Centrar` (cuando el texto fue movido).
6. **Barra de Reproducción Interactiva y Cabecera Limpia**:
   - Barra deslizable para saltar a cualquier punto del tramo y menú `Más` en la cabecera para `.lrc` y actualizaciones.

## Criterios de Éxito
- Todos los controles siguen invocando el mismo motor `LyricFrameRenderer` y `VideoExporter` (WYSIWYG intacto).
- `.\gradlew.bat testDebugUnitTest assembleDebug` finaliza con éxito.
