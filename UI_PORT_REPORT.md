# گزارش انتقال UI از 1.0.3 به 1.0.4

مرجع طراحی: `DeltaBox_1.0.3_FULL_UI_MERGED.zip`
پروژه اصلی و نهایی (منطق و قابلیت‌ها): `DeltaBox_1.0.4.zip`
خروجی: `DeltaBox_1.0.4_FULL_UI_PORTED.zip`

## ۱. فایل‌های طراحی منتقل‌شده

**رنگ و تم (Light و Dark)**
- `values/colors.xml`، `values-night/colors.xml` (پالت سبز مرجع جایگزین ایندیگوی 1.0.4 شد)
- `values/colors_delta_glass.xml`، `values-night/colors_delta_glass.xml`
- `values/colors_deltabox_tokens.xml`، `values-night/colors_deltabox_tokens.xml`
- `values/themes.xml` (statusBarColor)
- `values/styles_delta.xml`، `values-night/styles_delta.xml`

**Drawable و آیکون**
- `drawable/bg_black_wall_level_card.xml`، `bg_delta_popup.xml`، `bg_tools_icon_container_danger.xml`
- `mipmap-*/ic_launcher.png` و `ic_launcher_foreground.png` در ۵ تراکم

**Layout و منو**
- `layout_main.xml` (صفحه اصلی، نوار وضعیت، دکمه اتصال، Drawer)
- `layout_appbar.xml`، `layout_app_list.xml`، `layout_apps.xml`، `layout_group_list.xml`، `layout_tools.xml`
- `layout_black_wall.xml`، `layout_logcat.xml`، `preference_category_material.xml`
- `layout_tools_popup.xml` (در هیچ نسخه‌ای inflate نمی‌شود؛ فقط طراحی)
- `layout_about.xml` و `layout_nav_header.xml` (ادغام دستی؛ بخش ۶)
- `menu/main_drawer_menu_primary.xml`، `menu/main_drawer_menu_tools.xml` (بدون chevron)

**Dimens و String**
- `values/dimens.xml` (از 1.0.3 + `zxl_flashlight_margin_top` که فقط در 1.0.4 است)
- `values/strings.xml` (فقط `developer_name` تغییر کرد)

## ۲. فایل‌های تغییرکرده

جمعاً ۳۸ فایل در `app/src/main/res` تغییر کرده و هیچ فایل جدیدی اضافه یا حذف نشده است. فهرست کامل در `RESOURCE_CHANGES.diff` (بازتولید شده برای این انتقال) آمده است.

هیچ فایل Kotlin، Java، Gradle، AndroidManifest، sing-box، libcore، یا فایل داده/پیکربندی دیگری تغییر نکرده است. این موضوع با `diff -rq` بین دو درخت تأیید شد.

## ۳. تغییر اختصاصی Developer

- فایل: `app/src/main/res/values/strings.xml`
- کلید: `developer_name` (`translatable="false"`)
- مقدار قبلی: `rc3vr467`
- مقدار جدید: `Pedram &amp; Danial` (در XML به‌صورت `&amp;` نوشته شده تا در اپ دقیقاً `Pedram & Danial` نمایش داده شود)
- آیکون Developer (`ic_baseline_person_24`) بدون تغییر است.
- Telegram همچنان `@v2ray_DA` و GitHub همچنان `rc3vr467/DeltaBox` است.
- عنوان `Delta for Android` و توضیحات آن بدون تغییر است.

## ۴. تأیید حفظ قابلیت‌های 1.0.4

- تمام IDهای استفاده‌شده در کد 1.0.4 در layoutها و منوهای خروجی موجودند. بررسی: `R.id.*` کد در برابر XML.
- منابع مخصوص 1.0.4 حفظ شدند:
  - `zxl_flashlight_margin_top` (برای `layout_scanner.xml` و قابلیت چراغ‌قوه در ScannerActivity)
  - رشته‌ها و ترجیحات WireGuard/AmneziaWG (`wireguard_preferences.xml` بدون تغییر)
  - `colors_delta_stats.xml`، `strings_delta_stats.xml`، `values-fa/strings_delta_stats.xml`، `bg_stats_signal_bar.xml`
  - `ic_baseline_flash_on/off_24.xml`، `zxl_flashlight_selector.xml`
- `fabRing` در هر دو نسخه وجود دارد و `MainActivity` آن را نمایش/مخفی می‌کند.
- اتصال/قطع، سرورها، Import/Export، Ping، DNS/Proxy، سرویس‌های پس‌زمینه، Notification، Permissionها، دیتابیس و Preference دست‌نخورده‌اند.
- رشته‌های متنی 1.0.4 حفظ شدند؛ فقط `developer_name` عوض شد (بخش ۳).

## ۵. نتیجه تست و Build

- **Build (`assembleDebug`) و Lint اجرا نشدند.** دلیل: Android SDK و پلاگین Android Gradle در این محیط در دسترس نیستند؛ دانلود `dl.google.com` توسط پروکسی با 403 رد شد. این محدودیت دور زده نشد.
- **بررسی ایستا انجام شد:**
  - تمام XMLهای `app/src/main/res` بدون خطای parse هستند.
  - هیچ مرجع `@type/name` یا `R.type.name` جدیدی بدون تعریف ایجاد نشده است. دو مرجع استایل (`ThemeOverlay.AppCompat.DayNight` و `Widget.MaterialComponents.CardView`) از کتابخانه‌های AppCompat و Material می‌آیند و همان‌ها در 1.0.3 هم استفاده می‌شدند.
  - هیچ فایل کدی تغییر نکرده است.
- **اجرای روی Emulator و تست بصری (Light/Dark، Home، Server List، Connection Panel، Settings، Drawer، Dialogs، About): انجام نشد.** لازم است این موارد روی دستگاه بررسی شوند.

## ۶. تغییر ضروری خارج از منابع UI

کد تغییر نکرده است. تصمیم‌های ادغام دستی که باید بازبینی شوند:

1. **`layout_nav_header.xml`:** ظاهر 1.0.3 (لوگوی ۴۰dp، letter-spacing، توکن‌های رنگی) اعمال شد، اما متن برند 1.0.4 (`Delta` + `Box`) نگه داشته شد. دلیل: نام اپ در 1.0.4 `Delta` است و شما گفته بودید تنها تغییر متنی مجاز، Developer است.
2. **`layout_about.xml`:** ظاهر 1.0.3 با ساختار ردیف‌های 1.0.4 (Developer → Telegram → GitHub) ادغام شد. ردیف Publisher از 1.0.3 حذف شد چون 1.0.4 آن را ندارد. هدر Hero به نسخه متنی 1.0.3 تغییر کرد؛ آیکون cat که در 1.0.4 بود حذف شد. اگر آن آیکون باید بماند، باید به `layout_about.xml` اضافه شود.
3. **`layout_main.xml`:** کارت داشبورد (`bg_dashboard_panel`) که فقط یک wrapper بدون ID بود حذف شد، چون 1.0.3 آن را ندارد. هیچ View یا منطق کدی به آن وابسته نبود. ابعاد FAB و Cradle از 1.0.3 گرفته شد (۶۰dp در مقابل ۶۸dp).
4. **`values-night/styles_delta.xml` و `values/styles_delta.xml`:** آیتم `colorControlNormal` از استایل ActionBar حذف شد، چون 1.0.3 آن را ندارد. تأثیر: رنگ آیکون‌های نوار بالا در حالت پیش‌فرض.
5. **`values/strings.xml`:** رشته‌های 1.0.3 (مثل `app_name = DeltaBox`، برچسب‌های منو و متن Black Wall) وارد نشدند، چون متن‌اند نه طراحی و برخی با قابلیت‌های 1.0.4 ناسازگارند.
6. **`wireguard_preferences.xml`:** نسخه 1.0.4 نگه داشته شد، چون تفاوت آن عملکردی است (تنظیمات AmneziaWG).
7. **`layout_tools_popup.xml`:** از 1.0.3 گرفته شد. این layout در هیچ‌کدام از نسخه‌ها inflate نمی‌شود، پس اثر زمان اجرا ندارد.
8. **فایل‌های 1.0.3 که منتقل نشدند:** `release.keystore` (کلید امضا؛ عمداً حذف شد، چون جزو 1.0.4 نیست) و `UI_MERGE_REPORT.md`.

## پیشنهاد اقدام بعدی

1. `./gradlew :app:assembleDebug` و `:app:lint` را روی یک ماشین با Android SDK اجرا کنید.
2. بررسی بصری روی Light و Dark: Home، Server List، Connection Panel، Settings، Drawer، Dialogs و About.
3. تصمیم درباره موارد ۱ تا ۴ بخش ۶.
