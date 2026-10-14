# modem

The library handling modem stuff for bunnylock. It does band, cell and PLMN locks, network and 5G modes, and diagnostics on Qualcomm, MediaTek, Pixel and the plain Android radio HAL.

It doesn't care how you get root. Hand it a helper started with `RootClient` (su), or with your own Shizuku setup. bunnylock's `ShizukuClient` is a good starting point.

## Setup

Copy the folder in, add `include(":modem")` to `settings.gradle.kts`, and add `implementation(project(":modem"))` to your app. You'll also need `com.android.library` and `kotlinx-coroutines-android` in your version catalog.

Two things the module can't do for you:

- Keep native libs extracted, or QMI won't load:
  ```kotlin
  android { packaging { jniLibs.useLegacyPackaging = true } }
  ```
- Ask for `READ_PHONE_STATE` and `ACCESS_FINE_LOCATION` at runtime.

## Usage

```kotlin
val modem = ModemController(context, scope, starter = { showStep ->
    showStep("Asking for root access...")
    val client = RootClient(context)
    StartedHelper(client, client.start(), Access.ROOT)
})

modem.connect()
modem.state.collect { /* it.groups, it.lteLock, ... */ }

modem.lockBands(Group.LTE, listOf(3, 20)) // returns a message, or throws ModemException
modem.refreshNow()
```

Actions don't refresh the state on their own, so call `refreshNow()` after them.

The module keeps its state in the `bunnylock` shared prefs, including the radio HAL locks it holds. Don't wipe that file.
