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
- No other emulators outside of these have been configured to work

---

## Setting JoeyOS as your home screen

After installing the APK, press the **Home** button. Android will ask which launcher to use — choose **JoeyOS** and tap **Always**.

---

## Using the app

- **Long-press the wallpaper**, tap the **Settings cog** (top-left), or press **Start** → opens Settings
- **Tap the grid icon** (top-left) or press **B** → opens the App Drawer
- **Long-press an app** or press **Y** → opens the Recently Played menu
- Press **X** → launches your most recently played game directly

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
- Log in with your RetroAchievements and/or InfiniteBacklog account
- View your beaten games at a glance, broken down by year and by game
