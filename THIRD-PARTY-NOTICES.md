# Third-party notices

## Kyant0/backdrop

Copyright 2025 Kyant. Licensed under the Apache License, Version 2.0.
Full license: `licenses/Kyant-backdrop-LICENSE.txt` (also bundled in the APK).

`liquid-glass.js` adapts the rounded rectangle distance, gradient, depth effect and circular refraction profile from Kyant0/backdrop v2.0.0's `internal/Shaders.kt`. The original AGSL shader is translated into a cached displacement map used by a browser backdrop filter. The foreground is excluded. This adaptation uses the non-dispersive lens; it does not copy Compose's renderer or its multiple chromatic samples.

Upstream: https://github.com/Kyant0/backdrop
Reference inspected: https://github.com/Roronoa-code/BitChord/tree/70394304ee718d160cd25e41fbcbaef39c05b45c/app/src/main/java/com/music/bitchord/ui/components/backdrop

BitChord's `LiquidGlass.kt`, `GlassNavBar.kt` and `FloatingBottomBar.kt` informed the material values and interaction tuning: 8 dp blur, saturation 1.5, 40% dark tint, a shaded indicator, restrained edge and shadow, and a 320 / 0.72 selector spring. Orbit retains its own navigation and controller implementation. No GPL navigation or integration source is copied.

## Samsung Health Data SDK and Android dependencies

This personal build links the unmodified Samsung Health Data SDK 1.1.0, obtained separately from Samsung. Samsung's SDK license governs that SDK; it is not relicensed as Orbit or as Apache-2.0. Its bundled open-source announcement is included as `assets/Samsung-OPEN-SOURCE.txt` in the APK. The SDK archive is not added to this repository.

Runtime dependencies: Kotlin standard library 2.2.21 and Parcelize runtime 1.9.22 (JetBrains), kotlinx.coroutines core/Android 1.10.2 (JetBrains), and Gson 2.13.1 (Google). These dependencies use Apache-2.0; the full Apache license is bundled at `assets/Kyant-backdrop-LICENSE.txt`. Their code is unmodified apart from Android DEX compilation. Coroutine service-provider declarations are retained.
