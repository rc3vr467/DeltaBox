# گزارش فنی — DeltaBox Hybrid UI (HTML در WebView + Kotlin)

## ۱) وضعیت صادقانه
| مورد | وضعیت |
|---|---|
| Build / کامپایل Kotlin | **انجام نشد** (محیط من Android SDK و اینترنت ندارد). کد Kotlin فقط با بازبینی دستی در برابر APIهای واقعی سورس نوشته شده؛ ممکن است خطای کامپایل کوچک داشته باشد. |
| APK | تحویل داده نمی‌شود. |
| تست UI (JS) | انجام شد: Chromium headless + Bridge ساختگی، ۳۶ مورد، همه پاس، بدون خطای کنسول و بدون درخواست شبکه (`docs/hybrid-ui-tools/smoke.py`). |
| تست روی دستگاه/WebView واقعی | انجام نشد. |
| نسخه سورس | ZIP با نام 1.0.4 است ولی `nb4a.properties` نسخه 1.4.2 را نشان می‌دهد و یادداشت‌هایی از 1.0.8/1.0.15 دارد. |

## ۲) معماری
- `WebMainActivity` (Launcher جدید) → WebView قفل‌شده → `assets/ui/index.html`.
- HTML/CSS/JS فقط نمایش و تعامل ظاهری؛ همه‌ی داده و عمل از Kotlin (`UiBridge`، نام در JS: `NativeBridge`).
- `localStorage`/`sessionStorage` غیرفعال است (`domStorageEnabled=false`) و در کد هم هیچ ارجاعی نمانده.
- `MainActivity` قدیمی حفظ شد: میزبان Fragmentهای Native (Group/Tools/Logs/About/Settings چون به `MainActivity` cast می‌شوند)، Tile و لینک‌های Import (`sn://`, `clash://`, ...).
- اگر `index.html` لود نشود، برنامه خودکار به UI قدیمی برمی‌گردد (`onReceivedError`).

## ۳) فایل‌ها
**جدید:** `ui/web/WebMainActivity.kt`، `ui/web/UiBridge.kt`، `ui/web/UiSettingsSchema.kt`، `assets/ui/index.html`، `docs/*` (نقشه، گزارش، مرجع HTML، ابزار ساخت/تست).
**ویرایش‌شده:** `AndroidManifest.xml` (Launcher به WebMainActivity؛ parent اسپلیت‌تانل)، `MainActivity.kt` (پشتیبانی `EXTRA_NAV` در onCreate/onNewIntent)، `SagerNet.kt` (PendingIntent نوتیفیکیشن)، `ktx/Utils.kt` (rebirth)، ۶ فایل رنگ + `styles_delta.xml` (پالت Lime برای صفحات Native).
**حذف‌شده:** هیچ.

## ۴) API پل (فقط این متدها در دسترس JS هستند)
| متد | عمل واقعی |
|---|---|
| `getState()` | وضعیت سرویس، پروفایل/گروه انتخاب‌شده، night |
| `toggleConnection()` | `SagerNet.stopService()` یا درخواست مجوز VPN + `startService` |
| `getGroups()`, `selectGroup(id)` | `groupDao`، `DataStore.selectedGroup` |
| `getProfiles(groupId)` | `proxyDao` با ترتیب‌دهی مثل `ConfigurationFragment` (name/delay)؛ فیلدها: name,type,addr,status,ping,error,tx,rx,reality |
| `selectProfile(id)` | `DataStore.selectedProxy` + `reloadService()` اگر در حال اجرا |
| `deleteProfiles(json)`, `renameProfile(id,name)` | `ProfileManager` |
| `importText(text)`, `importFromClipboard()` | `parseProxies` + `createProfile` |
| `copyText`, `shareText` | Clipboard/Intent در Kotlin |
| `testGroup(groupId,"url"/"tcp")` | همان منطق `runConnectionTest` (UrlTest / TCP)، با `connectionTestConcurrent` |
| `testConnection()` | `service.urlTest()` (نوار وضعیت) |
| `clearTestResults`, `clearTraffic`, `getInvalidIds`, `sortGroupByDelay` | معادل منوی Native |
| `updateSubscription(groupId)` | `GroupUpdater.startUpdate` |
| `getSettings()`, `setSetting(k,v)` | ۳۷ کلید whitelist روی `DataStore.configurationStore`؛ `nightTheme` و `appLanguage` اثر فوری دارند |
| `getBlackWall()`, `setBlackWall(on,level)` | `BlackWallEngine` |
| `openNative(target)` | assets, split, route, blackwall, group, tools, logs, about, settings, classic |

رویدادهای Kotlin→JS: `state`, `speed`, `changed`, `connectionTest`, `testDone`, `toast`, `theme`, `ready`. Back: `DeltaNative.onBack()`.
امنیت: فقط `file:///android_asset/ui/`، شبکه بلاک (`blockNetworkLoads` + `shouldInterceptRequest`)، `allowFileAccess=false`، ناوبری خارج از asset بسته، اعتبارسنجی کلید/نوع/طول/id.

## ۵) تطابق طراحی
**منتقل‌شده (CSS/markup مرجع دست‌نخورده):** Header، کارت‌های پروفایل، تب گروه، Power Button/گربه، نوار وضعیت و سرعت، Drawer، Black Wall Sheet، Dialog/Modal/Toast، منوها، تنظیمات اصلی، About، تم Dark/Light.
**تفاوت‌ها (آگاهانه):**
1. فونت Inter همراه برنامه نیست (فایلش در دسترس نبود) → fallback سیستم. JetBrains Mono داخل CSS تعبیه شد.
2. `nightTheme`: در HTML مقادیر 0/1 بود ولی در Native 1=تیره و 2=روشن است → اصلاح شد.
3. زبان HTML فقط English و فارسی (طبق تصمیم شما)؛ Native زبان‌های بیشتری دارد و دست‌نخورده است.
4. ping/ترافیک/وضعیت دمو و «کشور خروج» حذف شد. پرچم از ایموجی نام پروفایل گرفته می‌شود، وگرنه 🌐. متای Black Wall فقط سطح را نشان می‌دهد (SNI/fingerprint از Kotlin در دسترس نیست).
5. «Reset data» مثل Native فقط روی گروه جاری است (در HTML همه بود).
6. تنظیمات اضافه‌شده در HTML: AdBlock (`ad_block_enabled`) و همزمانی تست (`connectionTestConcurrent`).
7. چیدمان RTL صفحه اصلی در مرجع وجود نداشت؛ فقط متن‌ها فارسی می‌شوند (تست نشده روی دستگاه).
**فقط Native (طبق شرط ۲):** ویرایش عمیق پروفایل/پروتکل‌ها، Route، Assets و Rule Provider، Split Tunnel، Logs، Backup (Tools)، مدیریت گروه. «Manual Settings…» به UI کلاسیک (`classic`) می‌رود، نه مستقیم به ویرایشگر.
**هنوز استخراج‌نشده:** Import فایل/QR/Subscription-link از Intent هنوز به `MainActivity` قدیمی می‌رود.

## ۶) دستورات Build و تست (روی سیستم شما)
```bash
# پیش‌نیاز: Android SDK/NDK، JDK مطابق Gradle پروژه، و libcore (Go) طبق README پروژه
./gradlew :app:assembleOssDebug          # یا assemblePlayDebug / assemblePreviewDebug
./gradlew :app:lintOssDebug
adb install -r app/build/outputs/apk/oss/debug/*.apk
adb logcat -s chromium WebView:V *:E     # خطاهای JS/WebView
```
Debug WebView: `chrome://inspect` (برای دیدن کنسول JS باید موقتاً `WebView.setWebContentsDebuggingEnabled(true)` در debug اضافه شود؛ الان فعال نیست).
**تست دستی روی دستگاه:** لود صفحه اصلی؛ انتخاب پروفایل؛ Power (مجوز VPN، اتصال، قطع)؛ سرعت زنده؛ تپ روی نوار وضعیت؛ Real Delay/TCP؛ Import از Clipboard؛ حذف/تغییر نام؛ Black Wall و AdBlock؛ تغییر Theme/Language؛ هر ۳۷ تنظیم؛ Drawer → صفحات Native و بازگشت؛ Back (sheet → drawer → صفحه → خروج)؛ چرخش؛ نوتیفیکیشن؛ Tile.

## ۷) ریسک‌های شناخته‌شده
- خطاهای احتمالی کامپایل در `UiBridge.kt`/`WebMainActivity.kt` (تایپ‌ها و importها دستی بررسی شده‌اند).
- `setApplicationLocales` ممکن است Activity را بازسازی کند (صفحه دوباره لود می‌شود).
- هر رویداد `changed` با debounce 150ms داده را از Room دوباره می‌خواند؛ با گروه بسیار بزرگ باید روی دستگاه سنجیده شود.
- اتصال Intentهای ورودی (VIEW) هنوز به UI کلاسیک است.

## ۸) نتیجه‌ی تست روی دستگاه (گزارش کاربر) و اصلاحات دور دوم
| # | گزارش | علت / اقدام | وضعیت |
|---|---|---|---|
| ۱ | AdBlock در Black Wall نیست | فقط در تنظیمات بود. کلید AdBlock به شیت Black Wall هم اضافه شد (`ad_block_enabled`) | اصلاح شد، تست‌نشده روی دستگاه |
| ۲ | کلید و سطح‌های Black Wall و منوی ⋮ واکنش ندارند | علت قطعی نامعلوم است. کد HTML و Bridge در تست Chromium درست کار می‌کرد. اصلاح‌ها: وضعیت Black Wall بعد از هر تغییر دوباره از Native خوانده می‌شود، و هر خطای Bridge حالا به‌صورت Toast قرمز «Bridge error (نام متد)» دیده می‌شود | نیاز به تست مجدد |
| ۳ | Manual Settings به تم قدیمی می‌رود | طراحی فعلی است (ویرایشگرهای پروفایل هنوز Native‌اند) | تغییری نکرد |
| ۴ | Export/Share/Import در Tools | این صفحه همان `BackupFragment` اصلی است؛ تغییری در آن نداده‌ام. علت نامعلوم | نیاز به جزئیات |
| ۵ | نوار وضعیت پایین در Group/Tools | رفتار اصلی برنامه: تنظیم «نوار پایین» (`showBottomBar`) آن را در همه صفحات نگه می‌دارد. حالا برای صفحاتی که از HTML باز می‌شوند همیشه مخفی است | اصلاح شد |
| ۶ | لینک‌های Telegram/GitHub | باگ من بود: ناوبری WebView عمداً بلاک است. حالا از Bridge (`openUrl`) و فقط برای این دو لینک باز می‌شود | اصلاح شد |
| ۷ | Tools باز می‌ماند | Back از صفحات Native به UI قدیمی برمی‌گشت؛ حالا برای صفحات باز‌شده از HTML Back صفحه را می‌بندد و به خانه‌ی HTML برمی‌گردد | اصلاح شد |
| — | «Import from file» (گزارش نشده) | `<input type=file>` در WebView بدون File Chooser کار نمی‌کند → با انتخابگر فایل Native جایگزین شد (`importFile`) | اصلاح شد |

برای عیب‌یابی: در Debug build، WebView قابل بازرسی است (`chrome://inspect`). خطاهای Bridge در Toast می‌آیند.
