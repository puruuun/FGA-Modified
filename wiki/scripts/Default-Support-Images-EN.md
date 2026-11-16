# Extract Default Support Images

Tapping **More options → Storage → Extract Default Support Images** extracts the app's built-in default Servant and Craft Essence support images into your selected FGA folder. First set the storage folder using **Change folder used for Support images, drops images, etc.** on the same tab. The extraction result appears below the option.

Icons used by logs and the Account dashboard are downloaded **separately from extraction** by tapping **Download log icon data** directly below. Extracting alone does not download icons; that item shows only progress or the final result with no summary description line (blank while idle). Because the initial download is **about 15 MB**, Wi-Fi is recommended.

- After fetching the public KR list from Atlas Academy, it downloads standard Servant and Mash portraits, skill and NP enhancement materials, currencies and fruits used by logs and the dashboard, and Mystic Code icons. Event items are not prefetched.
- Existing icons are not downloaded again; missing files are fetched up to 4 at a time. Icons are saved internally inside the app under `capture/icons/`, with portraits in `servants/` and Mystic Codes in `mystics/`. Nothing is added to the APK or your chosen FGA folder.
- Downloading continues even if you leave the screen. Tapping it again while running does nothing and keeps showing the current progress of the same job. If the app process stops, downloading stops; tapping again later reuses saved files and downloads only the missing ones.
- Image extraction triggered from the Support selection screen (**Preferred** supports) does not download icons.
- Even after **Delete log collection data**, the public lists and icons remain. Only public lists and images are downloaded; no account data is sent.

## Progress display and failures

Below **Download log icon data**, FGA displays **Fetching icon lists…** and **Downloading icons n/m**. When finished, it updates to **m icons ready** or **m icons ready, k failed**. The ready count includes previously saved files, and the progress count includes failed attempts. Entries that have no image on Atlas (HTTP 404, such as unused Null/Void Skill Gems, Magic Gems, and Secret Gems, Star Fragments, or Extra Pieces and Monuments) are counted in neither ready nor failed, and are subtracted from the progress total. If nothing failed, the failure count is omitted (for example, 570 ready + 6 HTTP 404 shows **570 icons ready**). Network errors and other status codes still count as failures. HTTP 404 items are not retried for one hour. This option is shown only on Android 10 and later when **Enable log collection** is turned on.

If **Could not fetch icon lists** appears, check your connection and tap again later. Because it reuses the existing list download and retry cooldown, tapping repeatedly will not bypass the cooldown. Individual icon failures do not stop the rest of the download, and keep the existing per-icon retry interval (at least one hour). Any missing icon needed when rendering on screen is also fetched using the existing mechanism.

Support image extraction and icon downloading are independent, and each shows its own result under its own item. Where an image is missing, logs and the dashboard display the item's name instead.

For dashboard layout and how public data is stored, see [Log collection](Gains-Tracker-EN.md).
