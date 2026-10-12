# bunnylock

bunnylock is an Android app for band, RAT, and cell locking. It talks to the modem through root or Shizuku and picks the best backend available on the phone.

Modems are weird, and vendors rarely implement the same controls in the same way. Something working on one phone does not guarantee that it will work on every phone with the same chipset or Android version.

## Features

Depending on the phone and backend, bunnylock can:

- lock 2G, 3G, 4G, and 5G bands
- force a RAT, such as 4G only or 5G only
- lock LTE and NR frequencies using EARFCN or ARFCN
- lock one or more PCIs on supported modems
- select 5G SA, NSA, or both
- lock to a mobile network (PLMN)
- re-attach, restart the radio, or reboot the modem
- clear active locks with **Unlock all**

See [Tested devices](TESTED_DEVICES.md) for combinations that have been tried on real phones.

## Access

bunnylock needs either root or [Shizuku](https://shizuku.rikka.app/). It tries root first and falls back to Shizuku when root is unavailable or denied.

- **Root or Shizuku running as root** gives bunnylock full access to the available modem backend.
- **Shizuku started through ADB** can use Android's telephony APIs without root.
- Some **MediaTek** phones expose an AT-command passthrough through Shizuku, allowing real band and cell locking without root.

## Backends

bunnylock detects the phone automatically. You can override the backend in **Tools** if the automatic choice is wrong.

- **Qualcomm** - talks to the modem over QMI.
- **MediaTek** - uses the same AT commands as MediaTek EngineerMode.
- **Pixel Tensor** - uses Shannon modem settings exposed by Pixel firmware.
- **Samsung Exynos and other devices** - uses Android's radio HAL. On supported Samsung phones, bunnylock can also open the built-in Samsung band menu.

Available features still depend on what the modem and firmware accept. In particular, Android's radio HAL controls are vendor-dependent.

## Using it

1. Grant root access, or start Shizuku and allow bunnylock to use it.
2. Pick the SIM you want to control.
3. Use **Bands** to select bands, or **Cell lock** to enter an EARFCN, ARFCN, or PCI.
4. Use **Unlock all** on the Home screen to return to the default configuration.

Locking a band or cell that your carrier does not use can leave the phone without service. **Unlock all** works offline, so keep it in mind while experimenting.

If something fails, copy the diagnostics from **Tools**. They include the detected chipset, backend, available interfaces, and recent operation log.

## Building

You need:

- JDK 17 or newer
- Android SDK
- Android NDK `29.0.14206865`
- CMake `3.22.1`

On Windows:

```powershell
.\gradlew.bat assembleRelease
```

On Linux or macOS:

```sh
./gradlew assembleRelease
```

The APK is written to `app/build/outputs/apk/release/app-release.apk`
