# Third-party notices

## Kyant0/backdrop

Copyright 2025 Kyant. Licensed under the Apache License, Version 2.0.
Full license: `licenses/Kyant-backdrop-LICENSE.txt` (also bundled in the APK).

`liquid-glass.js` adapts the rounded rectangle distance, gradient, depth effect and circular refraction profile from Kyant0/backdrop v2.0.0's `internal/Shaders.kt`. The original AGSL shader is translated into a cached displacement map used by a browser backdrop filter. The foreground is excluded. This adaptation uses the non-dispersive lens; it does not copy Compose's renderer or its multiple chromatic samples.

Upstream: https://github.com/Kyant0/backdrop
Reference inspected: https://github.com/Roronoa-code/BitChord/tree/70394304ee718d160cd25e41fbcbaef39c05b45c/app/src/main/java/com/music/bitchord/ui/components/backdrop

The native app goes further: `native/phone/src/main/java/com/mani/orbit/backdrop` is Kyant0/backdrop v2.0.0 vendored as source, re-vendored from BitChord's copy (which had merged the KMP expect/actual declarations into one Android source set and added a backdrop resolution scale). Orbit renames the package and drops the IDE-only `@Language("AGSL")` annotations; the shaders, effects, highlight and shadow are otherwise unmodified. The Apache-2.0 licence text ships with that app at `assets/licenses/Kyant-backdrop-LICENSE.txt`. `native/phone/src/main/java/com/mani/orbit/OrbitGlass.kt` is Orbit's own code, but it is not independent work: it arranges the vendored library in the same order as BitChord's `LiquidGlass.kt` and carries the same tuning — vibrancy, blur radius, refraction height and amount, surface opacity, resolution scale, the lift multipliers and the dispersion threshold. BitChord's file is in turn adapted from EchoMusicApp/Echo-Music's `GlassEffectConfig` / `Modifier.liquidGlass`, which is **GPL-3.0**. Only the amounts and the arrangement travelled here; no Echo or BitChord source was copied. The owner has confirmed Orbit is a private sideloaded build for their own devices, not distributed, so no further licence step is outstanding; this note records the provenance rather than a caution.

BitChord's `LiquidGlass.kt`, `GlassNavBar.kt` and `FloatingBottomBar.kt` informed the material values and interaction tuning: 8 dp blur, saturation 1.5, 40% dark tint, a shaded indicator, restrained edge and shadow, and a 320 / 0.72 selector spring. Orbit retains its own navigation and controller implementation. No GPL navigation or integration source is copied.

## Morphing menu

`morphing-menu.zip`, supplied by the owner, is a standalone adaptation of Danny Williams's site menu.
Orbit copies none of its code: it is React and TypeScript, and Orbit's Explore island is Compose. What
travelled is the motion model — the two-phase compress-then-spring shell morph and its constants, the
bar's blur-and-scale departure, the row cascade with its offsets and blur, the press scale, and the
rule that a cancelled transition never resumes its second phase. `native/phone/src/main/java/com/mani/orbit/ExploreMotion.kt`
and `ExploreIsland.kt` carry that adaptation.

## Samsung Health Data SDK and Android dependencies

This personal build links the unmodified Samsung Health Data SDK 1.1.0, obtained separately from Samsung. Samsung's SDK license governs that SDK; it is not relicensed as Orbit or as Apache-2.0. Its bundled open-source announcement is included as `assets/Samsung-OPEN-SOURCE.txt` in the APK. The SDK archive is not added to this repository.

Runtime dependencies: Kotlin standard library 2.2.21 and Parcelize runtime 1.9.22 (JetBrains), kotlinx.coroutines core/Android 1.10.2 (JetBrains), and Gson 2.13.1 (Google). These dependencies use Apache-2.0; the full Apache license is bundled at `assets/Kyant-backdrop-LICENSE.txt`. Their code is unmodified apart from Android DEX compilation. Coroutine service-provider declarations are retained.
