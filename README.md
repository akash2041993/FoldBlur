# FoldBlur

iPhone Duo style blur for Samsung Galaxy Z Fold phones (made for Fold 5).
While you open or close the phone, the screen blurs. As the phone gets close to fully open, the
blur melts away and the screen comes into focus.

## How it works

- Reads the phone's hinge angle sensor.
- **Screen capture mode (version 2, works on Samsung):** keeps a small live copy of the screen.
  When the hinge starts moving, it freezes that picture, blurs it inside the app, shows it on top,
  and fades it away as the phone opens flat.
- **Glass mode:** uses Android's window blur when screen capture is off. Samsung turns this blur
  off on some phones (like the Fold 5), then only a tint shows.
- The blur only shows while the hinge is moving. If you hold the phone half open, it fades away.
- If the phone has no hinge sensor, it plays a short blur when the screen switches instead.

Limits: this is an app, not a system change. It cannot change Samsung's own animation, only cover
it. Android stops screen capture when the phone locks. To keep it running, turn ON
"Turn off screen share protections" in Developer options. Android shows a screen-sharing icon while
capture is on.

## Get the APK for free (GitHub builds it for you)

1. Make a free account at github.com.
2. Create a new repository named `FoldBlur`.
3. Upload all files from this folder, including the `.github` folder.
4. Open the **Actions** tab and wait for "Build APK" to finish (about 3 to 5 minutes).
5. Open **Releases** on the repository page and download `FoldBlur.apk` on your Fold.

## Install on the phone

1. Open the downloaded `FoldBlur.apk`. If asked, allow your browser or Files app to install unknown apps.
2. Open FoldBlur and do the 3 setup steps on the screen.
3. Turn **Effect ON**, then tap **Test effect now**.
4. Close and open your phone.

If the effect stops after some time: Settings > Apps > FoldBlur > Battery > Unrestricted.

## Tuning

- **Blur strength**: how strong the blur is.
- **Frosted glass**: adds a milky glass color.
- **Start angle / End angle**: watch the live hinge angle at the top of the app, then set where the
  blur starts and where the screen becomes fully sharp.

## Build on a computer instead

Open the folder in Android Studio and press Run, or run `gradle assembleDebug` with Gradle 8.9 and JDK 17.
