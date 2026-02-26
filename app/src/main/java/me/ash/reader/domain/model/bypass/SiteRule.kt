package me.ash.reader.domain.model.bypass

import androidx.annotation.Keep
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonElement

@Keep
@Serializable
data class SiteRule(
    @SerialName("domain") val domain: String? = null,
    @SerialName("group") val group: List<String>? = null,
    @SerialName("allow_cookies") val allowCookies: Int? = null,
    @SerialName("remove_cookies") val removeCookies: Int? = null,
    @SerialName("remove_cookies_select_hold") val removeCookiesSelectHold: List<String>? = null,
    @SerialName("remove_cookies_select_drop") val removeCookiesSelectDrop: List<String>? = null,
    @SerialName("useragent") val useragent: String? = null,
    @SerialName("useragent_custom") val useragentCustom: String? = null,
    @SerialName("referer") val referer: String? = null,
    @SerialName("referer_custom") val refererCustom: String? = null,
    @SerialName("random_ip") val randomIp: String? = null,
    @SerialName("block_regex") val blockRegex: String? = null,
    @SerialName("block_js") val blockJs: Int? = null,
    @SerialName("block_js_ext") val blockJsExt: Int? = null,
    @SerialName("block_js_inline") val blockJsInline: String? = null,
    @SerialName("ld_json") val ldJson: String? = null,
    @SerialName("ld_json_next") val ldJsonNext: String? = null,
    @SerialName("ld_json_source") val ldJsonSource: String? = null,
    @SerialName("ld_json_url") val ldJsonUrl: String? = null,
    @SerialName("ld_archive_is") val ldArchiveIs: String? = null,
    @SerialName("ld_och_to_unlock") val ldOchToUnlock: String? = null,
    @SerialName("cs_clear_lclstrg") val csClearLclStrg: Int? = null,
    @SerialName("cs_code") val csCode: JsonElement? = null,
    @SerialName("cs_dompurify") val csDompurify: Int? = null,
    @SerialName("cs_block") val csBlock: Int? = null,
    @SerialName("amp_unhide") val ampUnhide: Int? = null,
    @SerialName("amp_redirect") val ampRedirect: String? = null,
    @SerialName("add_ext_link") val addExtLink: String? = null,
    @SerialName("add_ext_link_type") val addExtLinkType: String? = null,
    @SerialName("exception") val exception: List<SiteRule>? = null,
    @SerialName("nofix") val nofix: Int? = null,
    @SerialName("headers_custom") val headersCustom: Map<String, String>? = null,
)
