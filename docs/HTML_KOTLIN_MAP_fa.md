# نقشه‌ی ارتباط HTML ↔ Kotlin (Hybrid) — DeltaBox

وضعیت: همه‌ی APIهای ستون Kotlin از سورس واقعی خوانده شده‌اند، اما هنوز کامپایل نشده‌اند.

## قانون داده
منبع حقیقت: Room (`SagerDatabase`) و `DataStore.configurationStore`. HTML فقط نمایش می‌دهد؛ `localStorage` برای پروفایل/تنظیمات/وضعیت استفاده نمی‌شود.

## نگاشت اجزا
| بخش HTML | منبع داده / عمل Kotlin | Bridge | وضعیت |
|---|---|---|---|
| Power Button | `DataStore.serviceState.canStop` → `SagerNet.stopService()`، وگرنه `VpnRequestActivity.StartService` (مجوز VPN) | `toggleConnection()` | مرحله ۱ |
| وضعیت اتصال (گربه، متن) | `SagerConnection.Callback.stateChanged` / `onServiceConnected` (`BaseService.State`) | رویداد `state` | مرحله ۱ |
| سرعت زنده | `cbSpeedUpdate(SpeedDisplayData)`: tx/rxRateProxy، Direct، Total | رویداد `speed` | مرحله ۱ |
| ترافیک هر پروفایل | `ProxyEntity.tx/rx` (به‌روزرسانی با `ProfileManager.postUpdate(TrafficData)`) | داخل `getProfiles` | مرحله ۱ |
| تب‌های گروه | `SagerDatabase.groupDao.allGroups()`، `ProxyGroup.displayName()`، `DataStore.selectedGroup` | `getGroups()`, `selectGroup(id)` | مرحله ۱ |
| لیست پروفایل | `proxyDao.getByGroup(id)`؛ `displayName()`, `displayType()`, `displayAddress()`, `status`, `ping`, `error` | `getProfiles(groupId)` | مرحله ۱ |
| انتخاب پروفایل | `DataStore.selectedProxy` (+ `SagerNet.reloadService()` اگر در حال اجرا) | `selectProfile(id)` | مرحله ۱ |
| حذف پروفایل | `ProfileManager.deleteProfile(groupId, id)` | `deleteProfile(id)` | مرحله ۱ |
| Import از متن/Clipboard | `parseProxies(text)` + `ProfileManager.createProfile(DataStore.selectedGroupForImport(), bean)` | `importText(text)` | مرحله ۱ |
| Import فایل / QR / Subscription | `MainActivity.importProfile/importSubscription`، `ScannerActivity` | `openNative(...)` | Native |
| Real Delay / Ping گروه | منطق `ConfigurationFragment.runConnectionTest` (`UrlTest().doTest`) — در Fragment خصوصی است | نیاز به استخراج به کلاس مشترک | مرحله ۳ |
| Black Wall | `BlackWallEngine.enabled`, `BlackWallEngine.level` (GHOST/PHANTOM/WRAITH) | `getBlackWall()`, `setBlackWall()` | مرحله ۱ |
| تنظیمات اصلی (۳۵ کلید) | `DataStore.configurationStore` با whitelist | `getSettings()`, `setSetting(k,v)` | مرحله ۱ |
| تم روشن/تاریک | `nightTheme` (+ `ThemedActivity`)؛ HTML با `prefers`/پارامتر از Kotlin | داخل `getState` | مرحله ۱ |
| زبان | `appLanguage`؛ HTML فقط EN/FA | داخل `getSettings` | مرحله ۱ |
| Drawer: Group/Assets/Route/Tools/Logs/About | Activity/Fragment موجود | `openNative(target)` | Native |
| ویرایش عمیق پروفایل | `ProfileSettingsActivity`ها | `openNative("edit:ID")` | Native |

## یافته‌ی کلیدی
همه‌ی ۳۵ کلید تنظیمات در HTML با کلیدهای واقعی `res/xml` و `DataStore` یکسان‌اند (تأییدشده با اسکریپت). پس یک `getSetting/setSetting` عمومی با whitelist کافی است.

## معنای status پروفایل (از ConfigurationFragment)
0 = تست‌نشده، 1 = در دسترس (`ping` بر حسب ms)، 2 = پلاگین یافت نشد (`error`)، 3 = خطا (`error`).

## نکات امنیتی Bridge
- فقط `file:///android_asset/ui/` بارگذاری می‌شود؛ `allowFileAccess=false`, `allowContentAccess=false`، بدون شبکه‌ی WebView (`blockNetworkLoads`).
- فقط متدهای `@JavascriptInterface` فهرست‌شده؛ whitelist کلید تنظیمات؛ اعتبارسنجی نوع و طول ورودی.
- کارهای حساس (VPN، DB، clipboard، Intent) فقط در Kotlin.

## تفاوت‌های HTML با Native که باید گزارش شوند
- HTML پروفایل‌های دمو و ping شبیه‌سازی‌شده دارد → حذف و جایگزین با داده‌ی Native.
- `navigator.clipboard/share` → Bridge.
- فونت‌های Google Fonts → باید محلی شوند (فایل‌های Inter و JetBrains Mono لازم است؛ اینجا دانلود ممکن نیست).
