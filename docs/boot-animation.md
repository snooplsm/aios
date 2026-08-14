# AIOS boot animation

`assets/bootanimation/bootanimation.zip` is an original 1080x2424, 30 fps Android
boot animation for the Pixel 9a display. Part 0 fades the emblem in over twelve
frames. Part 1 holds the AIOS wordmark until Android exits boot animation. Every
ZIP member is stored without compression for Android BootAnimation compatibility.

The product flag defaults on:

```text
AIOS_ENABLE_BOOT_ANIMATION=true
```

Set it to `false` before building to retain the upstream device boot visuals.
The optional `aios_bootanimation` prebuilt installs at
`/product/media/bootanimation.zip`; it does not patch framework or device code.

Regenerate after changing either master:

```text
python3 tools/build_boot_animation.py \
  --emblem assets/branding/aios-boot-emblem-master.png \
  --wordmark assets/branding/aios-boot-wordmark-master.png \
  --output /new/path/bootanimation.zip
```

The builder refuses to overwrite an output. Review the result, then replace the
checked-in ZIP deliberately. `tests/test_boot_animation.py` verifies the canvas,
frame topology, loop contract, PNG headers, and uncompressed archive entries.
