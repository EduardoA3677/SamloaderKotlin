package tk.zwander.common.tools

import com.fleeksoft.ksoup.Ksoup
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import tk.zwander.common.data.FetchResult
import tk.zwander.common.util.globalHttpClient
import tk.zwander.common.util.firstElementByTagName
import tk.zwander.common.util.invoke
import tk.zwander.samloaderkotlin.resources.MR

/**
 * Handle fetching the latest version for a given model and region.
 */
object VersionFetch {
    /**
     * Get the latest firmware version for a given model and region.
     * @param model the device model.
     * @param region the device region.
     * @param useTestFirmware if true, fetches from version.test.xml instead of version.xml
     * @return a Pair(FirmwareString, AndroidVersion).
     */
    suspend fun getLatestVersion(model: String, region: String, useTestFirmware: Boolean = false): FetchResult.VersionFetchResult {
        try {
            val xmlFile = if (useTestFirmware) "version.test.xml" else "version.xml"
            val response = globalHttpClient.get(
                urlString = "https://fota-cloud-dn.ospserver.net:443/firmware/${region}/${model}/${xmlFile}",
            ) {
                userAgent("Kies2.0_FUS")
            }

            val responseXml = Ksoup.parse(response.bodyAsText())

            if (responseXml.tagName() == "Error") {
                val code = responseXml.firstElementByTagName("Code")!!.text()
                val message = responseXml.firstElementByTagName("Message")!!.text()

                return FetchResult.VersionFetchResult(
                    error = IllegalStateException("Code: ${code}, Message: $message"),
                    rawOutput = responseXml.toString()
                )
            }

            // If using test firmware, we need to decrypt MD5 hashes from <value> tags
            if (useTestFirmware) {
                return try {
                    // Try to get official version for reference (without recursion)
                    val refVersion = try {
                        // Fetch from version.xml for reference
                        val refResponse = globalHttpClient.get(
                            urlString = "https://fota-cloud-dn.ospserver.net:443/firmware/${region}/${model}/version.xml",
                        ) {
                            userAgent("Kies2.0_FUS")
                        }
                        val refXml = Ksoup.parse(refResponse.bodyAsText())
                        refXml.firstElementByTagName("firmware")
                            ?.firstElementByTagName("version")
                            ?.firstElementByTagName("latest")
                            ?.text() ?: ""
                    } catch (e: Exception) {
                        "" // Continue without reference version
                    }
                    
                    // Get test firmware and decrypt it
                    val testResult = TestFirmwareDecrypt.getTestFirmwareVersions(
                        model, 
                        region, 
                        maxVersionsToDecrypt = 100,
                        referenceVersion = refVersion
                    )
                    
                    if (testResult.error != null) {
                        FetchResult.VersionFetchResult(
                            error = testResult.error,
                            rawOutput = ""  // Don't show encrypted XML to user
                        )
                    } else if (testResult.latestRegularUpdate != null) {
                        // Return the latest regular update as the "latest version"
                        FetchResult.VersionFetchResult(
                            versionCode = testResult.latestRegularUpdate.versionCode,
                            androidVersion = "", // Test firmware doesn't have Android version in the same way
                            rawOutput = ""  // Don't show encrypted XML to user
                        )
                    } else if (testResult.versions.isNotEmpty()) {
                        // Fallback to any decrypted version
                        FetchResult.VersionFetchResult(
                            versionCode = testResult.versions.first().versionCode,
                            androidVersion = "",
                            rawOutput = ""  // Don't show encrypted XML to user
                        )
                    } else {
                        // No firmware versions could be decrypted
                        val md5Count = responseXml.select("value").size
                        val debugInfo = "Found $md5Count encrypted firmware entries but could not decrypt any.\n" +
                                       "Model: $model, Region: $region\n" +
                                       "Reference version: ${if (refVersion.isNotBlank()) refVersion else "not available"}"
                        
                        FetchResult.VersionFetchResult(
                            error = Exception(MR.strings.testFirmwareDecryptionError()),
                            rawOutput = debugInfo  // Show helpful debug info instead of encrypted XML
                        )
                    }
                } catch (e: Exception) {
                    FetchResult.VersionFetchResult(
                        error = e,
                        rawOutput = ""  // Don't show encrypted XML to user
                    )
                }
            }

            // Standard version.xml parsing for stable firmware
            try {
                val latest = responseXml.firstElementByTagName("firmware")
                    ?.firstElementByTagName("version")
                    ?.firstElementByTagName("latest")

                val latestText = latest?.text()

                if (latestText.isNullOrBlank()) {
                    val hasAccessDenied = responseXml.firstElementByTagName("code")
                        ?.text() == "AccessDenied"

                    return FetchResult.VersionFetchResult(
                        error = Exception(
                            if (hasAccessDenied) {
                                MR.strings.invalidCscError()
                            } else {
                                MR.strings.noFirmwareFoundError()
                            },
                        ),
                    )
                }

                val vc = latestText.split("/").toMutableList()

                if (vc.size == 3) {
                    vc.add(vc[0])
                }
                if (vc[2] == "") {
                    vc[2] = vc[0]
                }

                return FetchResult.VersionFetchResult(
                    versionCode = vc.joinToString("/"),
                    androidVersion = latest.attribute("o")?.value ?: "",
                    rawOutput = responseXml.toString()
                )
            } catch (e: Exception) {
                return FetchResult.VersionFetchResult(
                    error = e,
                    rawOutput = responseXml.toString()
                )
            }
        } catch (e: Exception) {
            return FetchResult.VersionFetchResult(
                error = e,
            )
        }
    }
}
