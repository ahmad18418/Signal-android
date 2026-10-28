# سیگنال بورس — اپ اندروید

اپ جدا از نسخه وب/سرور است. سرور فعلی (`signal_alert`) تغییر نمی‌کند.

## امکانات
- نصب به‌صورت APK روی اندروید
- ورود با **اثرانگشت / تشخیص چهره** (BiometricPrompt)
- نمایش همان پنل وب (سیگنال، آمار، پرتفو، خرید…) داخل WebView
- تنظیم آدرس سرور داخل اپ
- امکان خاموش کردن بیومتریک در تنظیمات

## پیش‌نیاز ساخت APK
- Android Studio Hedgehog یا جدیدتر
- JDK 17
- اینترنت برای دانلود Gradle/SDK

## ساخت و نصب
1. پوشه `signal_android` را در Android Studio با **Open** باز کنید
2. صبر کنید Gradle Sync تمام شود
3. یک امولاتور یا گوشی با USB Debugging وصل کنید
4. **Run** بزنید، یا:

```bash
cd signal_android
./gradlew assembleDebug
# خروجی:
# app/build/outputs/apk/debug/app-debug.apk
```

فایل `app-debug.apk` را روی گوشی کپی و نصب کنید (Install unknown apps).

## تنظیم سرور
- پیش‌فرض: `http://130.185.75.146`
- داخل اپ → دکمه تنظیمات (صفحه قفل) → آدرس را عوض کنید
- اگر از nginx روی پورت 80 استفاده می‌کنید همان IP بدون پورت کافی است

## بیومتریک
- هنگام باز شدن اپ، اگر حسگر موجود باشد درخواست اثرانگشت/چهره می‌شود
- در تنظیمات می‌توانید بیومتریک را خاموش کنید (ورود مستقیم به وب)

## نکات
- اپ وب قبلی و سرویس سرور دست نخورده‌اند
- محاسبات و فیلترها همان API سرور است؛ بعداً با آپدیت سرور هر دو (وب و اندروید) به‌روز می‌شوند
- برای HTTP بودن سرور، `usesCleartextTraffic` فعال است

## ساختار
```
signal_android/
  app/src/main/java/com/signalalert/app/
    MainActivity.kt      # قفل بیومتریک + WebView
    SettingsActivity.kt  # آدرس سرور و سوییچ بیومتریک
    Prefs.kt
  app/src/main/res/
  README.md
```
