# برومبت التحقق من إصلاح بناء CINESTREAM-PRO — Phase 26.12

أنت مهندس Android Build/QA. افتح المشروع من المجلد الجذر `CINESTREAM-PRO`، وراجع تقرير `PHASE-26.12-BUILD-FIX-REPORT_AR.md`.

نفّذ الأوامر التالية فعليًا بالترتيب، مع حفظ الخرج الكامل ورمز الخروج لكل أمر:

```bash
./gradlew --no-daemon --stacktrace :app:compileDebugKotlin
./gradlew --no-daemon --stacktrace :app:testDebugUnitTest
./gradlew --no-daemon --stacktrace :app:assembleDebug
```

## قواعد إلزامية
1. لا تعدّل ملفات مصدر أو إعدادات Gradle أو Firebase/Firestore قبل إرجاع تقرير الفشل إن وُجد.
2. لا تعتبر تحذيرات `compileSdk = 36` أو تحذيرات عدم تقليم مكتبات native سبب الفشل ما لم توجد رسالة خطأ مانعة.
3. تحقق تحديدًا أن أخطاء `searchUrl` و`Jsoup` و`TurnstilePositioningEngine` الثلاثة اختفت.
4. أبلغ عن أي خطأ جديد بدقة: الملف، السطر، النص الكامل، والحد الأدنى من الإصلاح المقترح. لا تنفذ إصلاحات إضافية بصمت.
5. إذا نجح البناء، أرفق مسار APK وحجمه وSHA-256. لا تدّع نجاح الاختبار دون سجل فعلي.
6. لا تختبر Cloudflare عن طريق تجاوز آلي؛ أي تحقق حي يجب أن يبقى تفاعليًا ورسميًا.

أعد ملفًا باسم `PHASE_26_12_BUILD_VERIFICATION_REPORT.md` داخل المشروع يتضمن حالة كل أمر وملخص الأخطاء/التحذيرات وسجل الخرج أو مسار السجل.
