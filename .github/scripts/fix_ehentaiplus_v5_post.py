from pathlib import Path

p = Path('source/src/zh/ehentaiplus/src/eu/kanade/tachiyomi/extension/zh/ehentaiplus/EHentaiPlus.kt')
s = p.read_text('utf-8')

old = '    private fun installManualCookieListener(screen: PreferenceScreen, accountStatusPreference: Preference) {'
new = '    private fun EditTextPreference.installManualCookieListener(screen: PreferenceScreen, accountStatusPreference: Preference) {'
if old not in s:
    raise SystemExit('manual-cookie listener receiver fix did not find expected declaration')
s = s.replace(old, new, 1)

old = '            val text = result.fold({ "$prefix：$it" }, { "$prefix失败：${it.message}" })'
new = '            val text = result.fold({ "$prefix：$it" }, { "${prefix}失败：${it.message}" })'
if old not in s:
    raise SystemExit('account refresh interpolation fix did not find expected line')
s = s.replace(old, new, 1)

p.write_text(s, 'utf-8')
print('E-Hentai Plus v5 post-patch applied')
