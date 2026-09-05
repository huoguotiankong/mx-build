from pathlib import Path

p = Path('source/src/zh/ehentaiplus/src/eu/kanade/tachiyomi/extension/zh/ehentaiplus/EHentaiPlus.kt')
s = p.read_text('utf-8')
old = '    private fun installManualCookieListener(screen: PreferenceScreen, accountStatusPreference: Preference) {'
new = '    private fun EditTextPreference.installManualCookieListener(screen: PreferenceScreen, accountStatusPreference: Preference) {'
if old not in s:
    raise SystemExit('manual-cookie listener receiver fix did not find expected declaration')
p.write_text(s.replace(old, new, 1), 'utf-8')
print('E-Hentai Plus v5 post-patch applied')
