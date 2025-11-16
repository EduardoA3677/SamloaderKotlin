package tk.zwander.common.util

import dev.whyoleg.cryptography.DelicateCryptographyApi
import io.ktor.utils.io.core.*
import kotlinx.io.bytestring.toHexString
import tk.zwander.common.tools.CryptUtils

/**
 * Calculate MD5 hash of a string
 */
@OptIn(DelicateCryptographyApi::class, ExperimentalStdlibApi::class)
fun String.md5(): String {
    val md5 = CryptUtils.md5Provider.hasher().createHashFunction()
    val bytes = this.toByteArray(Charsets.UTF_8)
    val hash = md5.hash(bytes)
    return hash.toHexString()
}
