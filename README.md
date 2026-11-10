# Mess Machine

The Mess Machine sequencer from Neat Shit and Dye, rebuilt as an Android app.

64 pads in four banks, eight loops (A to H), a song lane, a piano roll, keys mode with scales and chords, note repeat and an arp, live recording, and the doctor's table for trimming and shaping every sound. It ships with the site's jars: Thumps, Bodily, Machinery, Tones, Goo, Brotherwife Bits, Qbldy Guts and The Heap (433 sounds).

A mess saved here is the same file the site saves, so you can move one between your phone and the site.

## Put it on your phone

1. On your phone, open **Releases** on this repo (or go to `github.com/TideClock/Mess-Machine/releases/tag/latest`).
2. Tap **MessMachine.apk**.
3. Open the download. If Android asks, allow installs from your browser.

Every push to `main` builds a new APK and replaces that release. New builds install over the old one and keep your saved messes.

## Build it yourself

Open the folder in Android Studio and press Run, or:

```
./gradlew assembleRelease
```

The APK lands in `app/build/outputs/apk/release/`.

The sound engine is plain Java with no Android parts, so it has its own quick test you can run without a phone:

```
javac -d build/t $(find app/src/main/java -name "*.java") tools/EngineTest.java
java -cp build/t EngineTest app/src/main/assets/sounds/ build/t
```

It checks loading, the site's file format, scales and chords, and that every step and swing lands on the exact sample.

See [DESIGN.md](DESIGN.md) for how it is put together, what is next, and the wishlist.

## A note on the signing key

`app/messmachine-sideload.jks` is checked in on purpose, so every build is signed the same way and installs over the last one. That is fine for sideloading to your own phone. Before putting the app in the Play Store, make a private key and keep it out of the repo.
