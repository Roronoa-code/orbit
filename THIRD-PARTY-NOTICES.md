# Third-party notices

## Kyant0/backdrop

Copyright 2025 Kyant. Licensed under the Apache License, Version 2.0.
Full license: `licenses/Kyant-backdrop-LICENSE.txt` (also bundled in the APK).

`liquid-glass.js` adapts the rounded rectangle distance, gradient, depth effect and circular refraction profile from Kyant0/backdrop v2.0.0's `internal/Shaders.kt`. The original AGSL shader is translated into a cached displacement map used by a browser backdrop filter. The foreground is excluded. This adaptation uses the non-dispersive lens; it does not copy Compose's renderer or its multiple chromatic samples.

Upstream: https://github.com/Kyant0/backdrop
Reference inspected: https://github.com/Roronoa-code/BitChord/tree/70394304ee718d160cd25e41fbcbaef39c05b45c/app/src/main/java/com/music/bitchord/ui/components/backdrop

BitChord's `LiquidGlass.kt`, `GlassNavBar.kt` and `FloatingBottomBar.kt` informed the material values and interaction tuning: 8 dp blur, saturation 1.5, 40% dark tint, a shaded indicator, restrained edge and shadow, and a 320 / 0.72 selector spring. Orbit retains its own navigation and controller implementation. No GPL navigation or integration source is copied.
