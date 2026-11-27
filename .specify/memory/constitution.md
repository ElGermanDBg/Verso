# Constitución de Verso

## Principios Fundamentales

1. **WYSIWYG Absoluto (Preview = MP4)**:
   Todo elemento visual (foto, filtros, trazos de dibujo, tipografía, alineación, animaciones y efectos) debe pasar por el mismo motor `LyricFrameRenderer` tanto en la vista previa de Compose como en la exportación H.264/AAC con `MediaCodec`. No se permiten efectos que solo funcionen en UI o que parpadeen por aleatoriedad sin semilla entre frames.

2. **Offline-First para Música y Letras Locales**:
   La app debe funcionar sin conexión para escanear canciones en el dispositivo (`MediaStore`), leer archivos `.lrc` locales (adyacentes o vía SAF) y exportar videos MP4. LRCLIB es un complemento opcional en red.

3. **Edición Intuitiva con Una Mano**:
   Controles agrupados en un dock inferior colapsable, gestos directos sobre el lienzo 9:16 y selector visual de tramo/versos sin menús profundos.

4. **Exportación Nativa Ligera**:
   Codificación de video y audio 100% nativa en Android (`MediaCodec` + `MediaExtractor` + `MediaMuxer`) sin binarios externos pesados.
