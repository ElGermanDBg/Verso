# Plan Técnico: Verso Studio Pro (v1.5.0)

## Arquitectura de Cambios

1. **Modelos (`StoryConfig.kt`)**:
   - `PhotoFilter`: `NONE`, `WARM`, `COOL`, `BW`, `VINTAGE`, `MOODY`.
   - `LyricAlignment`: `LEFT`, `CENTER`, `RIGHT`.
   - `BrushStyle`: añade `ARROW` a `CHALK`, `NEON`, `MARKER`.

2. **Renderizador (`LyricFrameRenderer.kt`)**:
   - **Tiza realista**: `chalkStampBitmap` (`96×96`, `ARGB_8888`) generado una sola vez con ruido determinista 2D (multi-octava + bordes porosos + estrías) y aplicado a lo largo del trazo con `PorterDuffColorFilter(stroke.color, PorterDuff.Mode.SRC_IN)`, rotación tangencial + micro-jitter determinista y opacidad variable.
   - **Flecha**: cálculo del ángulo terminal entre los últimos puntos del trazo y dibujo de alas simétricas (`±28°`).
   - **Filtros de foto**: `ColorMatrixColorFilter` sobre el `Paint` de `photoBitmap`.
   - **Alineación**: mapeo de `LyricAlignment` a `Layout.Alignment` en `buildLayout` y posicionamiento en `drawScrollStyle`, `drawFocusStyle`, `drawCardStyle`, `drawHighlightStyle` y `drawCinemaStyle`.

3. **Estado y UI (`EditorViewModel.kt`, `EditorScreen.kt`)**:
   - Persistencia en `SharedPreferences("verso_prefs")`.
   - Controles de alineación, tamaño, filtros de foto y 4 pinceles en el dock inferior.
