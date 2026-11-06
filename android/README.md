# Drive mode for Android

The native app: listens in the background (a foreground service), plays
Spotify through Spotify's App Remote without bringing Spotify to the front,
opens Google Maps directions, and shows only an orb while driving.

Build (on the Mac whose debug key is registered with Spotify):

    mkdir -p app/libs && curl -L -o app/libs/spotify-app-remote-release-0.8.0.aar \
      https://github.com/spotify/android-sdk/releases/download/v0.8.0-appremote_v2.1.0-auth/spotify-app-remote-release-0.8.0.aar
    JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug

The APK is app/build/outputs/apk/debug/app-debug.apk.
