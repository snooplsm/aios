# AIOS boot artwork

These original raster masters are project assets for the AIOS boot identity.
They intentionally avoid Pixel, Android, GrapheneOS, Gemini, Google, Apple,
OpenAI, and other existing marks.

The built-in image-generation tool produced the three masters on 2026-08-14.
Prompts:

1. **Emblem:** an original, mathematically balanced interwoven conversational-AI
   knot made from softly rounded continuous bands, subtly suggesting speech and
   the letter A, near-white with restrained cyan/violet rim light on OLED black,
   with no text, particles, circuitry, watermark, or existing company logo.
2. **Completion wordmark:** the same original centered AIOS knot above exactly
   `AIOS` in quiet, widely tracked geometric lettering on OLED black, with no
   tagline, extra text, watermark, or existing company logo.
3. **Transparent UI knot:** the emblem isolated on a transparent square canvas,
   crisp and legible at 48dp and 64dp, with no text or background rectangle.

`tools/build_boot_animation.py` converts them into a deterministic 1080x2424
animation. `aios-ui-knot.png` also supplies the Setup Wizard resource overlay.
The masters and derived animation are distributed under the repository license.
