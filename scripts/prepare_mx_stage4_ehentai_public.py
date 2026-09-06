#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1]).resolve()
eh_path = root / "app/src/main/java/eu/kanade/tachiyomi/source/online/all/EHentai.kt"
build_path = root / "app/build.gradle.kts"
doc_path = root / "docs/EHENTAI.md"

eh = eh_path.read_text(encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, got {count}")
    return text.replace(old, new, 1)


eh = replace_once(
    eh,
    '''    private val domain: String
        get() = if (exh) {
            "exhentai.org"
        } else {
            "e-hentai.org"
        }
''',
    '''    private val hasExhentaiAccessCookie: Boolean
        get() = exhPreferences.igneousVal().get().let { igneous ->
            igneous.isNotBlank() && !igneous.equals("mystery", ignoreCase = true)
        }

    // Accounts without ExHentai access should remain usable through the public E-Hentai site.
    private val useExhentaiSite: Boolean
        get() = exh && hasExhentaiAccessCookie

    private val domain: String
        get() = if (useExhentaiSite) {
            "exhentai.org"
        } else {
            "e-hentai.org"
        }
''',
    "site selection",
)

eh = replace_once(
    eh,
    '''        if (exh && mangas.isEmpty() && exhPreferences.igneousVal().get().equals("mystery", true)) {
''',
    '''        if (useExhentaiSite && mangas.isEmpty() && exhPreferences.igneousVal().get().equals("mystery", true)) {
''',
    "invalid-cookie check",
)

eh = replace_once(
    eh,
    '''    fun spPref() = if (exh) {
        exhPreferences.exhSettingsProfile()
    } else {
        exhPreferences.ehSettingsProfile()
    }
''',
    '''    fun spPref() = if (useExhentaiSite) {
        exhPreferences.exhSettingsProfile()
    } else {
        exhPreferences.ehSettingsProfile()
    }
''',
    "settings profile",
)

eh = replace_once(
    eh,
    '''            cookies[EhLoginActivity.IGNEOUS_COOKIE] = exhPreferences.igneousVal().get()
''',
    '''            exhPreferences.igneousVal().get()
                .takeIf { it.isNotBlank() && !it.equals("mystery", ignoreCase = true) }
                ?.let { cookies[EhLoginActivity.IGNEOUS_COOKIE] = it }
''',
    "igneous cookie",
)

eh = replace_once(
    eh,
    '''    override val name = if (exh) {
        "ExHentai"
    } else {
        "E-Hentai"
    }
''',
    '''    override val name: String
        get() = when {
            !exh -> "E-Hentai（表站）"
            useExhentaiSite -> "ExHentai（里站）"
            else -> "ExHentai（表站回退）"
        }
''',
    "source name",
)

eh = replace_once(
    eh,
    '''    override val matchingHosts: List<String> = if (exh) {
        listOf(
            "exhentai.org",
        )
    } else {
        listOf(
            "g.e-hentai.org",
            "e-hentai.org",
        )
    }
''',
    '''    override val matchingHosts: List<String> = if (exh) {
        listOf(
            "exhentai.org",
            "g.e-hentai.org",
            "e-hentai.org",
        )
    } else {
        listOf(
            "g.e-hentai.org",
            "e-hentai.org",
        )
    }
''',
    "matching hosts",
)

eh_path.write_text(eh, encoding="utf-8")

build = build_path.read_text(encoding="utf-8")
build = replace_once(build, '        versionCode = 88\n        versionName = "1.14.8"\n', '        versionCode = 89\n        versionName = "1.14.9"\n', "version bump")
build_path.write_text(build, encoding="utf-8")

if doc_path.exists():
    doc = doc_path.read_text(encoding="utf-8")
    marker = "## MX Stage 4 public-site fallback"
    if marker not in doc:
        doc += '''\n\n## MX Stage 4 public-site fallback\n\n- The built-in public source is labelled `E-Hentai（表站）`.\n- The private source is labelled `ExHentai（里站）` when a usable `igneous` cookie exists.\n- If the account has no ExHentai privilege (`igneous` is blank or `mystery`), the ExHentai entry automatically uses `e-hentai.org` and is labelled `ExHentai（表站回退）`.\n- Existing Stage 1/2/3 detail, comment, rating and tag enhancements are preserved.\n'''
        doc_path.write_text(doc, encoding="utf-8")

print("Prepared MX Stage 4 E-Hentai public/front-site fallback (1.14.9 / 89)")
