# PHASE 33.1 — إصلاح موارد النصوص غير الصالحة

## سبب الفشل حسب سجل GitHub Actions

توقف البناء في المهمة `:app:mergeDebugResources` قبل تجميع Kotlin. أشار AAPT إلى المورد `string/everyone_watching` في `app/src/main/res/values/strings.xml` مع الرسالة `Invalid unicode escape sequence in string`.

تحذير توافق Android Gradle Plugin 8.7.2 مع `compileSdk = 36` ليس سبب هذا الفشل.

## ما وجدته في نسخة PHASE 33 التي تمت مراجعتها

1. قيمة `everyone_watching` كانت تحتوي فاصلة عليا غير مهربة: `Everyone's Watching`.
2. وُجد تسلسل هروب غير مدعوم في مورد نصي آخر: `receiver\x27s`. صيغة `\x27` ليست من صيغ الهروب المقبولة في Android string resources.

## الإصلاح

- أصبحت القيمة: `Everyone\'s Watching`.
- أصبح النص الآخر: `receiver\'s code`.
- لم تُغيّر أسماء الموارد أو معرّفاتها أو النص العربي.
- لم تتغير مكتبات Gradle أو إعدادات SDK أو منطق التطبيق.

## التحقق

- تم تحليل ملفي `values/strings.xml` و`values-ar/strings.xml` باستخدام محلل XML.
- تم فحص تسلسلات الهروب في الملفين، ولم تبقَ تسلسلات غير معروفة أو Unicode ناقصة.
- تم فحص أرشيف ZIP الناتج باستخدام `unzip -t`.

## ملاحظة حول رقم السطر

يشير سجل CI إلى السطر 383، بينما يظهر مورد `everyone_watching` في ملف PHASE 33 المرفق هنا عند السطر 1232. هذا يعني أن نسخة الملف التي بُنيت في GitHub قد تختلف في ترتيب/محتوى الأسطر عن النسخة المضغوطة التي تمت مراجعتها. لذلك يجب رفع هذه النسخة المصححة كاملة أو تطبيق التصحيحين على نفس commit الذي فشل، ثم إعادة البناء.

## حدود التحقق

لم يتم تأكيد نجاح `assembleDebug` في هذه البيئة. فحوص XML وتسلسلات الهروب وسلامة ZIP ناجحة، لكنها لا تستبدل اختبار AAPT/Gradle داخل GitHub Actions.
