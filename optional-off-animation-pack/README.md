# Cameramod — Optional "off" animation pack

The animated **off** screen (the looping clip the camera stream shows when it
isn't displaying a live camera) used to be bundled inside the mod jar. It was
~133 MB of PNG frames, which made the jar huge. It now ships as this **optional
resource pack** instead, so the mod jar stays small.

Without this pack the mod falls back to the single static image
`assets/cameramod/textures/gui/off.png` that is still bundled in the jar — the
stream simply shows that still frame instead of the animation. Everything works
either way; the animation is purely cosmetic.

## What's in here

```
pack.mcmeta
assets/cameramod/textures/gui/
    off.count        # number of animation frames (266)
    off.fps          # playback rate (24)
    off_0.png … off_265.png
```

The mod loads these through Minecraft's resource manager, so any enabled
resource pack that provides them is picked up automatically — no extra config.

## How to install

1. Zip the **contents** of this folder (so that `pack.mcmeta` and `assets/` sit
   at the root of the zip, *not* inside an extra `optional-off-animation-pack/`
   directory):

   ```
   cd optional-off-animation-pack
   zip -r ../cameramod-off-animation.zip pack.mcmeta assets
   ```

2. Drop the resulting `cameramod-off-animation.zip` into your
   `.minecraft/resourcepacks/` folder.
3. In Minecraft: **Options → Resource Packs**, move it to the right (enabled),
   and click **Done**.

The pack's `pack.mcmeta` carries both versioning schemes so it stays valid from
1.21.8 forward (as the mod is ported) instead of being flagged incompatible:

- **`pack_format` 64 + `supported_formats` `[64, 9999]`** — read by Minecraft
  1.21.8 and earlier (the original system; still required while the pack
  supports a format below 65).
- **`min_format` 64 + `max_format` 9999** — read by 1.21.9+ (the new system from
  snapshot 25w31a). Each accepts a `[major, minor]` array; a plain integer means
  `[n, 0]`, and a plain integer `max_format` means that major with any minor.

On versions *older* than 1.21.8 Minecraft may still warn; you can enable it
anyway and the off animation will load.

## Replacing the animation

Swap in your own frames named `off_0.png … off_(N-1).png`, set `off.count` to
`N`, and set `off.fps` to the desired playback rate. All frames should share the
camera's aspect ratio; they are sampled to the configured camera resolution.

### Easiest way: the pack maker tool

Open **[`tools/off-pack-maker.html`](../tools/off-pack-maker.html)** (in this
repo) in a browser and drop in a GIF, video, or image. It extracts the frames,
writes `off.count` / `off.fps` / `pack.mcmeta`, and gives you a ready-to-install
`.zip` — no manual frame exporting. A single image becomes a static `off.png`
pack instead. Everything runs locally in the browser; nothing is uploaded.
