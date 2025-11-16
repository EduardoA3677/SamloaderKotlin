package tk.zwander.common.tools

import com.fleeksoft.ksoup.Ksoup
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tk.zwander.common.util.globalHttpClient
import tk.zwander.common.util.md5

/**
 * Handle fetching and decrypting Samsung test firmware versions.
 * Based on https://github.com/EduardoA3677/SamsungTestFirmwareVersionDecrypt
 */
object TestFirmwareDecrypt {
    
    /**
     * Firmware version data class
     */
    data class TestFirmwareVersion(
        val versionCode: String,  // e.g., "S9280ZCU4BXKV/S9280CHC4BXKV/S9280ZCU4BXKV"
        val md5Hash: String,
        val year: Int,           // e.g., 2024
        val month: Int,          // e.g., 11 (November)
        val serialNumber: Int    // Internal test build number
    )
    
    /**
     * Result of test firmware fetch operation
     */
    data class TestFirmwareResult(
        val versions: List<TestFirmwareVersion> = emptyList(),
        val latestRegularUpdate: TestFirmwareVersion? = null,
        val latestMajorUpdate: TestFirmwareVersion? = null,
        val decryptionPercentage: Double = 0.0,
        val error: Exception? = null,
        val rawOutput: String = ""
    )
    
    /**
     * Fetch MD5 hashes from version.test.xml
     */
    private suspend fun fetchTestFirmwareMD5Hashes(model: String, region: String): List<String> {
        try {
            val response = globalHttpClient.get(
                urlString = "https://fota-cloud-dn.ospserver.net/firmware/${region}/${model}/version.test.xml",
            ) {
                userAgent("Kies2.0_FUS")
            }
            
            val responseXml = Ksoup.parse(response.bodyAsText())
            
            if (responseXml.tagName() == "Error") {
                return emptyList()
            }
            
            // Extract all <value> tags which contain MD5 hashes
            val md5List = mutableListOf<String>()
            responseXml.select("value").forEach { element ->
                val md5 = element.text()
                if (md5.isNotBlank()) {
                    md5List.add(md5)
                }
            }
            
            return md5List
        } catch (e: Exception) {
            return emptyList()
        }
    }
    
    /**
     * Get test firmware versions for a device model and region
     */
    suspend fun getTestFirmwareVersions(
        model: String, 
        region: String,
        maxVersionsToDecrypt: Int = 50,  // Limit to avoid excessive computation
        referenceVersion: String = ""     // Optional reference version to avoid circular dependency
    ): TestFirmwareResult = withContext(Dispatchers.Default) {
        try {
            // Fetch MD5 hashes from version.test.xml
            val md5Hashes = fetchTestFirmwareMD5Hashes(model, region)
            
            if (md5Hashes.isEmpty()) {
                return@withContext TestFirmwareResult(
                    error = Exception("No test firmware found for $model in region $region")
                )
            }
            
            // Use provided reference version or fetch from official version.xml
            val refVersion = if (referenceVersion.isNotBlank()) {
                referenceVersion
            } else {
                try {
                    VersionFetch.getLatestVersion(model, region, useTestFirmware = false).versionCode
                } catch (e: Exception) {
                    "" // If we can't get reference version, continue without it
                }
            }
            
            // Decrypt MD5 hashes to get actual firmware versions
            val decryptedVersions = decryptFirmwareVersions(
                model = model,
                region = region,
                md5Hashes = md5Hashes,
                referenceVersion = refVersion,
                maxVersions = maxVersionsToDecrypt
            )
            
            val decryptionPercentage = if (md5Hashes.isNotEmpty()) {
                (decryptedVersions.size.toDouble() / md5Hashes.size.toDouble()) * 100.0
            } else {
                0.0
            }
            
            // Separate regular updates from major version updates
            val (regularUpdates, majorUpdates) = categorizeVersions(decryptedVersions, refVersion)
            
            TestFirmwareResult(
                versions = decryptedVersions,
                latestRegularUpdate = regularUpdates.maxByOrNull { it.versionCode },
                latestMajorUpdate = majorUpdates.maxByOrNull { it.versionCode },
                decryptionPercentage = decryptionPercentage
            )
        } catch (e: Exception) {
            TestFirmwareResult(error = e)
        }
    }
    
    /**
     * Decrypt firmware version MD5 hashes using brute force
     * Based on https://github.com/EduardoA3677/SamsungTestFirmwareVersionDecrypt
     */
    private suspend fun decryptFirmwareVersions(
        model: String,
        region: String,
        md5Hashes: List<String>,
        referenceVersion: String,
        maxVersions: Int
    ): List<TestFirmwareVersion> {
        val decryptedVersions = mutableListOf<TestFirmwareVersion>()
        val md5Set = md5Hashes.toSet()
        
        // Parse model information
        val modelCode = model.replace("SM-", "")
        
        // Determine region-specific codes based on the region
        val regionCodes = getRegionCodes(region)
        val (apPrefix, cscPrefix, cpPrefix) = regionCodes
        
        // Get reference version info if available
        val referenceYear = if (referenceVersion.isNotEmpty()) {
            val parts = referenceVersion.split("/")
            if (parts.isNotEmpty() && parts[0].length >= 3) {
                // Convert character to year (A=2021, B=2022, etc.)
                val yearChar = parts[0].getOrNull(parts[0].length - 3)
                if (yearChar != null && yearChar.isLetter()) {
                    2021 + (yearChar - 'A')
                } else {
                    2024  // Default to current year
                }
            } else {
                2024
            }
        } else {
            2024
        }
        
        // Brute force parameters - start from 4 years before current year like Python implementation
        val startYear = maxOf(referenceYear - 4, 2021)
        val endYear = referenceYear + 1  // Go 1 year ahead
        
        // Get bootloader range from reference version
        val startBootloader = if (referenceVersion.isNotEmpty()) {
            val parts = referenceVersion.split("/")
            if (parts.isNotEmpty() && parts[0].length >= 5) {
                parts[0][parts[0].length - 5]
            } else {
                '0'
            }
        } else {
            '0'
        }
        
        // All possible bootloader characters (0-9, A-Z)
        val bootloaderChars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val bootloaderStart = bootloaderChars.indexOf(startBootloader)
        val bootloaderRange = if (bootloaderStart >= 0) {
            bootloaderChars.substring(bootloaderStart)
        } else {
            bootloaderChars
        }
        
        // All possible characters for serial number (0-9, A-Z)
        val serialChars = "123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        
        // Track baseband versions for reuse (like Python's CpVersions)
        val basebandVersions = mutableListOf<String>()
        
        // Iterate through possible version combinations
        for (updateType in listOf("U", "S")) {  // U=major update, S=security update
            for (bootloader in bootloaderRange) {
                
                for (versionLetter in 'A'..'Z') {
                    for (year in startYear..endYear) {
                        val yearChar = ('A' + (year - 2021))
                        
                        for (month in 1..12) {
                            val monthChar = ('A' + (month - 1))
                            
                            // Add candidate baseband versions for this month
                            val tempBasebands = basebandVersions.takeLast(12).toMutableList()
                            if (cpPrefix.isNotEmpty()) {
                                for (i in 1..2) {
                                    val initCP = "${modelCode}${cpPrefix}${updateType}${bootloader}${versionLetter}${yearChar}${monthChar}${i}"
                                    if (!tempBasebands.contains(initCP)) {
                                        tempBasebands.add(initCP)
                                    }
                                }
                            }
                            
                            for (serialChar in serialChars) {
                                if (decryptedVersions.size >= maxVersions) {
                                    return decryptedVersions
                                }
                                
                                // Build version strings
                                val apVersion = "${modelCode}${apPrefix}${updateType}${bootloader}${versionLetter}${yearChar}${monthChar}${serialChar}"
                                val cscVersion = "${modelCode}${cscPrefix}${bootloader}${versionLetter}${yearChar}${monthChar}${serialChar}"
                                val cpVersion = if (cpPrefix.isNotEmpty()) {
                                    "${modelCode}${cpPrefix}${updateType}${bootloader}${versionLetter}${yearChar}${monthChar}${serialChar}"
                                } else {
                                    ""
                                }
                                
                                val fullVersion = if (cpVersion.isNotEmpty()) {
                                    "$apVersion/$cscVersion/$cpVersion"
                                } else {
                                    "$apVersion/$cscVersion/$apVersion"
                                }
                                
                                // Calculate MD5 hash and check
                                val hash = fullVersion.md5()
                                if (hash in md5Set) {
                                    decryptedVersions.add(
                                        TestFirmwareVersion(
                                            versionCode = fullVersion,
                                            md5Hash = hash,
                                            year = year,
                                            month = month,
                                            serialNumber = serialChar.digitToIntOrNull() ?: (serialChar - 'A' + 10)
                                        )
                                    )
                                    // Track baseband version
                                    if (cpVersion.isNotEmpty() && !basebandVersions.contains(cpVersion)) {
                                        basebandVersions.add(cpVersion)
                                    }
                                }
                                
                                // Try with different baseband versions (firmware updated but baseband not)
                                for (tempBaseband in tempBasebands) {
                                    if (tempBaseband.isEmpty()) continue
                                    
                                    val fullVersion2 = "$apVersion/$cscVersion/$tempBaseband"
                                    if (fullVersion2 == fullVersion) continue
                                    
                                    val hash2 = fullVersion2.md5()
                                    if (hash2 in md5Set) {
                                        decryptedVersions.add(
                                            TestFirmwareVersion(
                                                versionCode = fullVersion2,
                                                md5Hash = hash2,
                                                year = year,
                                                month = month,
                                                serialNumber = serialChar.digitToIntOrNull() ?: (serialChar - 'A' + 10)
                                            )
                                        )
                                        if (!basebandVersions.contains(tempBaseband)) {
                                            basebandVersions.add(tempBaseband)
                                        }
                                    }
                                }
                                
                                // Handle beta versions with 'Z' as 4th character from end (like Python)
                                val betaVersionStr = "${bootloader}Z${yearChar}${monthChar}${serialChar}"
                                val apVersionZ = "${modelCode}${apPrefix}${updateType}${betaVersionStr}"
                                val cscVersionZ = "${modelCode}${cscPrefix}${betaVersionStr}"
                                val cpVersionZ = if (cpPrefix.isNotEmpty()) {
                                    "${modelCode}${cpPrefix}${updateType}${bootloader}${versionLetter}${yearChar}${monthChar}${serialChar}"
                                } else {
                                    ""
                                }
                                
                                val fullVersionZ = if (cpVersionZ.isNotEmpty()) {
                                    "$apVersionZ/$cscVersionZ/$cpVersionZ"
                                } else {
                                    "$apVersionZ/$cscVersionZ/$apVersionZ"
                                }
                                
                                val hashZ = fullVersionZ.md5()
                                if (hashZ in md5Set) {
                                    decryptedVersions.add(
                                        TestFirmwareVersion(
                                            versionCode = fullVersionZ,
                                            md5Hash = hashZ,
                                            year = year,
                                            month = month,
                                            serialNumber = serialChar.digitToIntOrNull() ?: (serialChar - 'A' + 10)
                                        )
                                    )
                                    if (cpVersionZ.isNotEmpty() && !basebandVersions.contains(cpVersionZ)) {
                                        basebandVersions.add(cpVersionZ)
                                    }
                                }
                                
                                // Try beta version with different basebands
                                for (tempBaseband in tempBasebands) {
                                    if (tempBaseband.isEmpty()) continue
                                    
                                    val fullVersionZ2 = "$apVersionZ/$cscVersionZ/$tempBaseband"
                                    if (fullVersionZ2 == fullVersionZ) continue
                                    
                                    val hashZ2 = fullVersionZ2.md5()
                                    if (hashZ2 in md5Set) {
                                        decryptedVersions.add(
                                            TestFirmwareVersion(
                                                versionCode = fullVersionZ2,
                                                md5Hash = hashZ2,
                                                year = year,
                                                month = month,
                                                serialNumber = serialChar.digitToIntOrNull() ?: (serialChar - 'A' + 10)
                                            )
                                        )
                                        if (!basebandVersions.contains(tempBaseband)) {
                                            basebandVersions.add(tempBaseband)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        return decryptedVersions
    }
    
    /**
     * Get region-specific codes for building firmware versions
     */
    private fun getRegionCodes(region: String): Triple<String, String, String> {
        return when (region) {
            "CHC", "CHN" -> Triple("ZC", "CHC", "ZC")      // China
            "TGY" -> Triple("ZH", "OZS", "ZC")             // Hong Kong
            "XAA" -> Triple("UE", "OYM", "UE")             // USA Unlocked
            "ATT" -> Triple("SQ", "OYN", "SQ")             // USA AT&T
            "KOO" -> Triple("KS", "OKR", "KS")             // Korea
            "TPA" -> Triple("PA", "TPA", "PA")             // Panama
            else -> Triple("XX", region, "XX")              // Generic fallback
        }
    }
    
    /**
     * Categorize versions into regular updates and major version updates
     */
    private fun categorizeVersions(
        versions: List<TestFirmwareVersion>,
        referenceVersion: String
    ): Pair<List<TestFirmwareVersion>, List<TestFirmwareVersion>> {
        if (referenceVersion.isEmpty()) {
            return Pair(versions, emptyList())
        }
        
        // Extract the update type character from reference version
        val refParts = referenceVersion.split("/")
        if (refParts.isEmpty() || refParts[0].length < 4) {
            return Pair(versions, emptyList())
        }
        
        val refUpdateChar = refParts[0].getOrNull(refParts[0].length - 4)
        
        val regular = mutableListOf<TestFirmwareVersion>()
        val major = mutableListOf<TestFirmwareVersion>()
        
        versions.forEach { version ->
            val parts = version.versionCode.split("/")
            if (parts.isNotEmpty() && parts[0].length >= 4) {
                val updateChar = parts[0].getOrNull(parts[0].length - 4)
                
                // If update char is one letter ahead of reference, it's a major update
                if (refUpdateChar != null && updateChar != null) {
                    if (updateChar > refUpdateChar) {
                        major.add(version)
                    } else {
                        regular.add(version)
                    }
                } else {
                    regular.add(version)
                }
            }
        }
        
        return Pair(regular, major)
    }
}
