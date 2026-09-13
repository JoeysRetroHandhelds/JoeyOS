# JoeyOS

[![Join our Discord](https://img.shields.io/badge/Discord-Join%20our%20Community-5865F2?style=for-the-badge&logo=discord&logoColor=white)](https://joeysrh.link/discord)

An Android home-screen launcher for retro gaming. Shows a customisable wallpaper with a dock of system icons that each launch an assigned emulator app.

It's a very simple launcher - no artwork scraping, no games lists, none of that. You have your emulators, they'll show your most recently played up to 20, you launch and play. Quick, easy, simple. 

This was created for my personal use, and while it may work for a lot of people's setups, it likely will break or be broken. I don't really have plans to make this a whole thing, but if you want to use it as I update it, feel free!

## Things you really should know
- This assumes you are using ES-DE (EmulationStations) ROM folders naming - folder named ROMs on your internal storage or SD card, with the ES-DE system folders inside (see here: https://github.com/JoeysRetroHandhelds/joeys-rom-folders)
- For RetroArch to work, you must have a RetroArch folder on your internal storage, and saves/states inside
- For PPSSPP to work, you must have a folder called PPSSPP exactly on your internal storage or SD card
- For MelonDualDS to work, you must have a folder called MelonDS or MelonDualDS exactly on your internal storage or SD card
- For Dolphin to work, your device must be able to access Android/data storage
- For NetherSX2 to work, you must be using NetherSX2 with the root storage patch and you must have a folder called NetherSX2 exactly on your internal storage
- For Azahar to work, you must have a folder called Azahar exactly on your internal storage or SD card
- For CEMU to work, you must have a folder called CEMU exactly on your internal storage or SD card
- For Eden to work, your device must be able to access Android/data storage
- For Vita3K to work, you must have a folder called Vita3K exactly on your internal storage or SD card
- For Duckstation to work, you must be using Duckstation-Patch (https://github.com/JoeysRetroHandhelds/duckstation-patch) and you must have a folder called DuckStation exactly on your internal storage
- For APS3E to work, your device must be able to access Android/data storage
- For M64Plus FZ to work, you must have a folder called M64Plus exactly on your internal storage or SD card 
- For ARMSX3 to work, you must have a folder called ARMSX3 exactly on your internal storage or SD card (with its recent_games.json inside), or your device must be able to access Android/data storage
- For Flycast to work, you must have a folder called Flycast on your internal storage or SD card (or whichever home folder you picked in Flycast), or your device must be able to access Android/data storage
- Bachata S4 (PS4) and XenDroid (Xbox 360) show on the dock and open the emulator, but don't have Recently Played yet
- No other emulators outside of these have been configured to work

---

## Setting JoeyOS as your home screen

After installing the APK, press the **Home** button. Android will ask which launcher to use. Choose **JoeyOS** and tap **Always**.

---

## Using the app

JoeyOS runs full screen. Swipe in from an edge to see the status and navigation bars.

- **D-pad** or **L1 / R1** → move along the dock; **L2 / R2** → jump five at a time
- Press **A** → opens the emulator
- **Long-press the wallpaper**, tap the **Settings cog** (top-left), or press **Start** → opens Settings
- **Tap the grid icon** (top-left) or press **B** → opens the App Drawer
- **Long-press an app** or press **Y** → opens the Recently Played menu
- Press **X** → launches your most recently played game directly
- In the App Drawer, press **Start** on an app (or long-press it) → App Info, or add/remove it from the dock
- In any popup, **A** selects and **B** goes back

### Updates
JoeyOS checks GitHub for a new version when it starts and every hour after that, and offers to install it. You can also check any time from **Settings → Tools → Check for updates**.

### Settings → Appearance
- Adjust dock icon size
- Reorder the dock
- Show/hide the recent title
- Show/hide the clock
- Set how many recently played games to show
- Set a dock background
- Set the dock title size
- Choose a default wallpaper or upload your own

### Settings → Emulators
- For RetroArch, choose which cores you have installed
- Default cores are pre-selected based on recommendation, but games won't load unless the chosen core is actually installed

### Settings → Achievements
- Log in with your RetroAchievements account (Account): enter your username and API key, then press **Connect**
- Overview: games beaten this year and all-time, what you're currently playing, and beaten/mastered per year
- Games: your beaten and mastered games by year and month, plus "Almost there" (the games you're closest to beating), with filters
- Stats: totals, points, hours played and charts
- Press A on any game for its details

### Settings → Tools
The tools never change your original games, and only remove them if you choose to when compressing.
- **Check BIOS files**: shows which consoles have the BIOS they need and which are missing one
- **Patch a ROM**: applies a romhack or translation patch (IPS, UPS, BPS, PPF, APS, xdelta). You name the new file and choose to save it next to the original or in Downloads. Tick **Save compressed** to get a zip (or CHD/RVZ/ZCCI) instead of the raw file
- **Generate .m3u playlists**: turns multi-disc games into one playlist each, so each game shows once. Can be undone
- **Compress ROMs**: zips cartridge games and converts discs to CHD, GameCube/Wii to RVZ and 3DS to ZCCI. Can be undone
- **RetroAchievements romhacks**: finds hacks and translations with achievements for the games you own, and creates the patched game for you
- **Check for updates** and **Share log** (send this to me if something goes wrong). The log is also saved in **Internal storage › JoeyOS**

---

## Second screen (dual-screen handhelds like the AYN Thor)

On a device with two screens, JoeyOS puts its own app on the other screen. Turn it on or off in **Settings → Appearance → Second screen**, where you can also choose which screen games open on.

It's touch first, and it takes the controller only when your device sends it there (on the Thor, use its setting to lock controls to the top screen, the bottom screen, or whichever one you tap). The bottom screen's back arrow steps back inside it.

Tabs:
- **Apps**: every app on your device, to open on that screen
- **Achievements** (when logged in to RetroAchievements): your points, what you're playing, recent awards, "Almost there", awards by year, and a search of every game on RetroAchievements. Tap any game for its achievements
- **Now playing** (while a game runs): the achievements of the game RetroAchievements sees you playing, with its live status, session and play time, filters and sorts, an "Unlocked!" banner, and tips from each achievement's comments
- **Guide** (while a game runs): see below
- **Settings**: which tab shows while you're browsing JoeyOS (Apps or Achievements), whether a game opens on Now playing or the Guide, and hiding achievement spoilers

The Achievements and Now playing tabs only appear when you're logged in to RetroAchievements. "Completed" is for games finished in casual (softcore) mode, "Mastered" for hardcore.

### Guides
Nothing downloads until you choose it. Guides are saved in **Internal storage › JoeyOS › guides**, and JoeyOS remembers which guide you picked for each game and where you were in it.
- **GameFAQs archive**: download the text walkthrough for the game and read it offline, with text size, wrap, reflow, light/dark/sepia, Find and Contents
- **Search the web**: a built-in browser with ad blocking (DuckDuckGo). Save any page to read offline later
- **YouTube**: opens a search in your YouTube app (including ReVanced)
- The tabs always stay at the top. In Guide, the arrow at the end of the tab row hides or shows the guide's own bar (Options, Find, Contents, Change guide) for more room
