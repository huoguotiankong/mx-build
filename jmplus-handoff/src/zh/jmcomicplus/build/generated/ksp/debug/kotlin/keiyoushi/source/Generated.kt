package keiyoushi.source

import eu.kanade.tachiyomi.extension.zh.jmcomicplus.JmComicPlus
import kotlin.Long
import kotlin.String

internal class Generated : JmComicPlus() {
  protected override val filterFetchHint: String
    get() = "Tap 'Reset' to load filters"

  override val name: String
    get() = "禁漫天堂 Plus"

  override val lang: String
    get() = "zh"

  override val id: Long
    get() = 5_337_195_273_790_213_207L

  override val baseUrl: String
    get() = "https://18comic.vip"
}
