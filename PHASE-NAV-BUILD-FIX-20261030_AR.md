# إصلاح فشل بناء شريط التنقل — 30 أكتوبر 2026

## نتيجة تحليل سجل GitHub Actions

- المهمة الفاشلة: `:app:compileDebugKotlin`
- الملف: `app/src/main/java/com/example/ui/components/BottomNavBar.kt`
- الموضع: السطر 286، العمود 19 في النسخة التي بُنيت على GitHub Actions.
- رسالة المترجم: `Unresolved reference 'togetherWith'`.

## السبب الجذري

يستخدم انتقال `AnimatedContent` التعبير:

```kotlin
fadeIn(tween(150)) + androidx.compose.animation.scaleIn(
    initialScale = .82f,
    animationSpec = tween(180)
) togetherWith fadeOut(tween(100))
```

لكن الملف لم يكن يستورد امتداد `togetherWith`، ولذلك تعذّر على مترجم Kotlin حل الاسم.

## التعديل

أُضيف الاستيراد التالي إلى `BottomNavBar.kt` دون تغيير منطق الانتقال أو إزالة الأنيميشن:

```kotlin
import androidx.compose.animation.togetherWith
```

تتوافق الواجهة مع حزمة `androidx.compose.animation`، وهي موثقة رسميًا من Android Developers ومتاحة منذ الإصدار 1.5.0. يستعمل المشروع Compose BOM `2024.10.01`.

## ما لا يُعد سببًا لهذا الفشل

- تحذير توافق Android Gradle Plugin `8.7.2` مع `compileSdk = 36` تحذير وليس سبب التوقف المعروض.
- رسالة تعذّر strip لبعض المكتبات الأصلية ليست خطأ البناء النهائي المعروض.
- خطوة `validateWebClientId` نجحت في السجل المقدم.

## حدود التحقق

- جرى التحقق من وجود الاستيراد في المصدر المعدّل، ومن اتساق موضع استخدام `togetherWith` معه، ومن سلامة أرشيف ZIP بعد إنشائه.
- لم يُعلن نجاح بناء APK؛ بيئة العمل الحالية لا تحتوي على تثبيت Gradle أو توزيع Gradle 8.9 محلي جاهز، وبالتالي يلزم تشغيل GitHub Actions على النسخة المصححة للتحقق النهائي. قد يكشف المترجم عن أخطاء إضافية بعد تجاوز هذا الخطأ الأول.

## الملفات

- التعديل الوحيد المخصص لهذا العطل: استيراد `androidx.compose.animation.togetherWith` في `BottomNavBar.kt`.
- لم تُحذف شاشات أو موارد أو إعدادات من المشروع بسبب هذا الإصلاح.
