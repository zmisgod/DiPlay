package com.shilapi.xcertplay.ultra

import com.shilapi.xcertplay.iap2.message.Iap2ClusterAsset

/** Matching metadata from Apple CarDDPAssets, parsed using LIVI's theme_catalog contract.
 * No certificates, artwork or CoreAnimation bundles are embedded here.
 */
data class UltraThemeProfile(
    val assetId: String, val version: Long, val sdkVersion: String,
    val mainPanel: String, val clusterPanel: String,
    val layoutId: String, val paletteId: String, val activeComponents: Map<String, String>,
) {
    init {
        require(version in 1..0xffffffffL)
        require(listOf(assetId, sdkVersion, mainPanel, clusterPanel, layoutId, paletteId).all { it.isNotBlank() && '\u0000' !in it })
        require(mainPanel != clusterPanel)
    }
    fun asset() = Iap2ClusterAsset(assetId, version)
    fun layout(light: Boolean) = mapOf<String, Any?>(
        "layoutID" to layoutId, "paletteID" to paletteId, "appearanceMode" to if (light) 0L else 1L,
        "fadeInNewLayout" to true, "fadeOutOldLayout" to true, "activeComponents" to activeComponents)
    companion object {
        // Verified 2026-10-31: catalog identifier/version and package minimumSDKVersion.
        val FX11_TEST = UltraThemeProfile(
            "44C9FA31ECCN", 985L, "19.0", "Center_Display", "Cluster_Display",
            "8BC9ABB1-CB20-595E-92BF-D8F099BF3515", "green", mapOf("BC653D68-76F1-5FC2-B2D5-2F1B78E72C90" to "5B5182A0-F0F6-5A8E-B108-D625D0FDC01B"))
    }
}
