# Plan Técnico: Verso Studio 2.0 (`v2.0.0`)

## Arquitectura de UI
1. **Tema Visual (`ui/theme/Theme.kt`)**:
   - Paleta oscura refinada (`VersoBg = 0xFF07070B`, `VersoSurface = 0xFF12121B`, `VersoSurfaceElevated = 0xFF1B1B28`) con acentos `VersoAccent`, `VersoAccentSecondary`, `VersoGold`, `VersoCyan`, `VersoGreen` y bordes sutiles `VersoBorder`.
2. **Componentes Reutilizables y Presets (`ui/StudioUiComponents.kt`)**:
   - `StoryPreset` y `VERSO_PRESETS` (6 presets curados).
   - `StudioSegmentedBar`, `StudioSectionLabel`, `StudioToggleCard`, `FloatingCanvasPillButton` y `PresetCard`.
3. **ViewModel (`ui/EditorViewModel.kt`)**:
   - `applyPreset(preset: StoryPreset)` con persistencia en `SharedPreferences` y activación automática de `AudioBeatAnalyzer` o `SubjectSegmenter` según corresponda.
   - `seekWithinClip(fraction: Float)` para scrubbing en tiempo real dentro del tramo activo.
4. **Pantalla Principal (`ui/EditorScreen.kt`)**:
   - Cabecera minimalista con selector de canción integrado, acceso rápido a foto y menú contextual (`MoreVert`).
   - Lienzo 9:16 con rail flotante derecho y barra inferior interactiva.
   - Dock fijo de 5 pestañas (`MUSICA`, `ESTILOS`, `TEXTO`, `FOTO`, `DIBUJAR`).
