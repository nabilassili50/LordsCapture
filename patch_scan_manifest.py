"""يسجّل خدمة المسح الآلي في AndroidManifest.xml (آمن لإعادة التشغيل). شغّله من جذر مستودع LordsCapture: python3 patch_scan_manifest.py"""
import os, sys
M = "app/src/main/AndroidManifest.xml"
if not os.path.exists(M): sys.exit("لم أجد " + M + " — شغّل الأمر من جذر المستودع (~/lc_repo)")
t = open(M, encoding="utf-8").read()
if "ScanAccessibilityService" in t:
    print("الخدمة مسجّلة من قبل")
else:
    if t.count("</application>") != 1: sys.exit("شكل الـmanifest غير متوقع؛ لم يُعدَّل شيء")
    svc = '''        <service
            android:name="com.botvi.lords.ScanAccessibilityService"
            android:exported="false"
            android:label="Lords Capture — المسح الآلي"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService" />
            </intent-filter>
            <meta-data
                android:name="android.accessibilityservice"
                android:resource="@xml/scan_accessibility" />
        </service>
    '''
    open(M, "w", encoding="utf-8").write(t.replace("</application>", svc + "</application>", 1))
    print("سُجّلت الخدمة في AndroidManifest.xml")
