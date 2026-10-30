# PdfBox-Android: optional JPEG 2000 codec that the app does not include.
-dontwarn com.gemalto.jp2.**
# PdfBox-Android loads parts of itself (fonts, filters, encodings) by name.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
