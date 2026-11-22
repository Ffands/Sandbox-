package com.sandboxforge.app.clone

import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.value.ValueType
import com.sandboxforge.app.logging.AppLogger
import java.io.ByteArrayInputStream

data class BaseManifestPatchResult(
    val patchedBytes: ByteArray,
    val originalPackage: String,
    val clonePackage: String,
    val originalAppComponentFactory: String?,
    val applicationClassName: String?,
    val launcherActivity: String?,
    val rewrittenProvidersCount: Int,
    val rewrittenPermissionsCount: Int,
    val strippedSharedUserId: String?,
    val strippedLaunchPermissions: List<String>,
    val disabledPairIpComponents: List<String>
)

/**
 * Patches binary AndroidManifest.xml chunks for both base.apk and split_*.apk using ARSCLib.
 * Critical invariants preserved:
 * 1. Resolves relative class names (.MainActivity, .App) BEFORE renaming package
 * 2. Strips sharedUserId & OEM launch/app android:permission locks (fixes com.oplus.permission.safe.SETTINGS)
 * 3. Rewrites provider authorities & custom permissions to prevent INSTALL_FAILED_CONFLICTING_PROVIDER
 * 4. Disables com.pairip.licensecheck.* manifest components to prevent immediate Play Store redirects
 * 5. Adds <queries><package android:name="com.sandboxforge.app"/></queries> for Android 11–17 bridge auth
 * 6. Injects SandboxAppComponentFactory + SandboxShimInitProvider + SandboxFileBridgeProvider
 * 7. Optionally removes CATEGORY_LAUNCHER while keeping exported=true on the launch Activity
 */
object ApkManifestPatcher {
    private const val ATTR_ID_NAME = 0x01010003
    private const val ATTR_ID_PERMISSION = 0x01010006
    private const val ATTR_ID_ENABLED = 0x0101000e
    private const val ATTR_ID_EXPORTED = 0x01010010
    private const val ATTR_ID_AUTHORITIES = 0x01010018
    private const val ATTR_ID_INIT_ORDER = 0x0101001a
    private const val ATTR_ID_TARGET_ACTIVITY = 0x01010402
    private const val ATTR_ID_APP_COMPONENT_FACTORY = 0x0101057a
    private const val ATTR_ID_SHARED_USER_ID = 0x0101000b
    private const val ATTR_ID_SHARED_USER_LABEL = 0x01010261

    private const val SHIM_FACTORY_CLASS = "com.sandboxforge.shim.SandboxAppComponentFactory"
    private const val SHIM_INIT_PROVIDER_CLASS = "com.sandboxforge.shim.SandboxShimInitProvider"
    private const val SHIM_FILE_BRIDGE_CLASS = "com.sandboxforge.shim.SandboxFileBridgeProvider"

    const val FILE_BRIDGE_AUTHORITY_SUFFIX = ".sandboxforge.files"

    fun buildClonePackageName(originalPackage: String, containerPrefix: String = "sf.c1"): String {
        val cleaned = originalPackage.trim().trim('.')
        return "$containerPrefix.$cleaned"
    }

    fun fileBridgeAuthority(clonePackage: String): String = "$clonePackage$FILE_BRIDGE_AUTHORITY_SUFFIX"

    fun patchBaseManifest(
        manifestBytes: ByteArray,
        clonePackage: String,
        hideFromLauncher: Boolean,
        hostPackage: String = "com.sandboxforge.app",
        neutralizePairIp: Boolean = true
    ): BaseManifestPatchResult {
        val manifest = AndroidManifestBlock()
        manifest.readBytes(ByteArrayInputStream(manifestBytes))

        val originalPackage = manifest.packageName
            ?: error("В AndroidManifest.xml не найден атрибут package")

        val manifestEl = manifest.manifestElement
            ?: error("Некорректный AndroidManifest.xml (нет корневого тега manifest)")
        val appEl = manifest.applicationElement
            ?: error("Некорректный AndroidManifest.xml (нет тега application)")

        // 1. Strip android:sharedUserId if present (e.g., OEM apps)
        var strippedSharedUserId: String? = null
        manifestEl.searchAttributeByResourceId(ATTR_ID_SHARED_USER_ID)?.let { attr ->
            strippedSharedUserId = attr.valueAsString
            manifestEl.removeAttribute(attr)
        }
        manifestEl.searchAttributeByResourceId(ATTR_ID_SHARED_USER_LABEL)?.let { attr ->
            manifestEl.removeAttribute(attr)
        }

        // 2. Resolve relative android:name on <application> BEFORE changing package
        resolveRelativeClassAttribute(appEl, ATTR_ID_NAME, originalPackage)
        val applicationClassName = appEl.searchAttributeByResourceId(ATTR_ID_NAME)?.valueAsString

        // Strip app-level android:permission if present (otherwise Android applies it to our bridge & launcher)
        val strippedLaunchPermissions = mutableListOf<String>()
        appEl.searchAttributeByResourceId(ATTR_ID_PERMISSION)?.let { permAttr ->
            permAttr.valueAsString?.let { strippedLaunchPermissions.add("application:$it") }
            appEl.removeAttribute(permAttr)
        }

        // 3. Walk all components under <application>
        var launcherActivity: String? = null
        var rewrittenProviders = 0
        val disabledPairIp = mutableListOf<String>()
        val pairIpDiagnostics = mutableListOf<String>()
        val componentTags = setOf("activity", "activity-alias", "service", "receiver", "provider")

        appEl.childElementsList().forEach { child ->
            val tag = child.name
            if (tag in componentTags) {
                resolveRelativeClassAttribute(child, ATTR_ID_NAME, originalPackage)
                if (tag == "activity-alias") {
                    resolveRelativeClassAttribute(child, ATTR_ID_TARGET_ACTIVITY, originalPackage)
                }
            }

            val compName = child.searchAttributeByResourceId(ATTR_ID_NAME)?.valueAsString.orEmpty()
            if (compName.startsWith("com.pairip.")) {
                pairIpDiagnostics.add("$tag:$compName")
                if (neutralizePairIp && compName.startsWith("com.pairip.licensecheck.")) {
                    child.getOrCreateAndroidAttribute("enabled", ATTR_ID_ENABLED)
                        .setValueAsBoolean(false)
                    disabledPairIp.add(compName)
                }
            }

            if (tag == "provider") {
                val authAttr = child.searchAttributeByResourceId(ATTR_ID_AUTHORITIES)
                val currentAuth = authAttr?.valueAsString
                if (!currentAuth.isNullOrBlank()) {
                    val rewritten = currentAuth.split(';').joinToString(";") { part ->
                        val trimmed = part.trim()
                        if (trimmed.isEmpty()) trimmed
                        else if (trimmed.startsWith(originalPackage)) {
                            clonePackage + trimmed.removePrefix(originalPackage)
                        } else {
                            "$clonePackage.$trimmed"
                        }
                    }
                    authAttr.setValueAsString(rewritten)
                    rewrittenProviders++
                }
            }

            if (tag == "activity" || tag == "activity-alias") {
                val isLauncher = isLauncherComponent(child)
                if (isLauncher) {
                    if (launcherActivity == null && compName.isNotBlank()) {
                        launcherActivity = compName
                    }
                    // Strip OEM/custom android:permission from launcher Activity so SandboxForge can launch it
                    child.searchAttributeByResourceId(ATTR_ID_PERMISSION)?.let { permAttr ->
                        permAttr.valueAsString?.let { strippedLaunchPermissions.add("$compName:$it") }
                        child.removeAttribute(permAttr)
                    }
                    // Ensure SandboxForge can launch this activity explicitly even when hidden from launcher
                    child.getOrCreateAndroidAttribute("exported", ATTR_ID_EXPORTED)
                        .setValueAsBoolean(true)

                    if (hideFromLauncher) {
                        removeLauncherCategory(child)
                    }
                }
            }
        }

        // 4. Rewrite custom <permission> declarations & matching <uses-permission>
        val customPermissions = linkedMapOf<String, String>()
        manifestEl.childElementsList().forEach { child ->
            if (child.name == "permission") {
                val nameAttr = child.searchAttributeByResourceId(ATTR_ID_NAME)
                val permName = nameAttr?.valueAsString
                if (!permName.isNullOrBlank()) {
                    val newPerm = if (permName.startsWith(originalPackage)) {
                        clonePackage + permName.removePrefix(originalPackage)
                    } else {
                        "$clonePackage.perm.$permName"
                    }
                    customPermissions[permName] = newPerm
                    nameAttr.setValueAsString(newPerm)
                }
            }
        }
        if (customPermissions.isNotEmpty()) {
            manifestEl.childElementsList().forEach { child ->
                if (child.name == "uses-permission") {
                    val nameAttr = child.searchAttributeByResourceId(ATTR_ID_NAME)
                    val oldPerm = nameAttr?.valueAsString
                    val mapped = oldPerm?.let { customPermissions[it] }
                    if (mapped != null) {
                        nameAttr.setValueAsString(mapped)
                    }
                }
            }
        }

        // 5. Ensure <queries><package android:name="com.sandboxforge.app"/></queries>
        //    Required on Android 11–17 so SandboxFileBridgeProvider can verify host UID & cert SHA-256
        ensurePackageQuery(manifestEl, hostPackage)

        // 6. Capture original appComponentFactory (if any) and inject SandboxAppComponentFactory
        val factoryAttr = appEl.searchAttributeByResourceId(ATTR_ID_APP_COMPONENT_FACTORY)
        val originalFactory = factoryAttr?.valueAsString?.let { normalizeClassName(it, originalPackage) }
        appEl.getOrCreateAndroidAttribute("appComponentFactory", ATTR_ID_APP_COMPONENT_FACTORY)
            .setValueAsString(SHIM_FACTORY_CLASS)

        // 7. Inject SandboxShimInitProvider (runs first with full Context on Android 8–17)
        val initProvider = appEl.newElement("provider")
        initProvider.getOrCreateAndroidAttribute("name", ATTR_ID_NAME)
            .setValueAsString(SHIM_INIT_PROVIDER_CLASS)
        initProvider.getOrCreateAndroidAttribute("authorities", ATTR_ID_AUTHORITIES)
            .setValueAsString("$clonePackage.sandboxforge.shim")
        initProvider.getOrCreateAndroidAttribute("exported", ATTR_ID_EXPORTED)
            .setValueAsBoolean(false)
        initProvider.getOrCreateAndroidAttribute("initOrder", ATTR_ID_INIT_ORDER)
            .setTypeAndData(ValueType.DEC, Int.MAX_VALUE)

        // 8. Inject SandboxFileBridgeProvider (exported=true, protected by triple-lock auth inside provider)
        val bridgeProvider = appEl.newElement("provider")
        bridgeProvider.getOrCreateAndroidAttribute("name", ATTR_ID_NAME)
            .setValueAsString(SHIM_FILE_BRIDGE_CLASS)
        bridgeProvider.getOrCreateAndroidAttribute("authorities", ATTR_ID_AUTHORITIES)
            .setValueAsString(fileBridgeAuthority(clonePackage))
        bridgeProvider.getOrCreateAndroidAttribute("exported", ATTR_ID_EXPORTED)
            .setValueAsBoolean(true)

        // 9. Finally update root package name
        manifest.packageName = clonePackage
        manifest.refresh()

        AppLogger.d(
            "ManifestPatcher",
            "Патч base manifest: $originalPackage -> $clonePackage | " +
                "app=${applicationClassName ?: "default"} | launcher=$launcherActivity | " +
                "providers=$rewrittenProviders | customPerms=${customPermissions.size} | " +
                "origFactory=${originalFactory ?: "none"}"
        )
        if (strippedLaunchPermissions.isNotEmpty()) {
            AppLogger.i(
                "ManifestPatcher",
                "Сняты ограничивающие android:permission: ${strippedLaunchPermissions.joinToString(", ")}"
            )
        }
        if (pairIpDiagnostics.isNotEmpty() || applicationClassName?.startsWith("com.pairip.") == true) {
            AppLogger.i(
                "ManifestPatcher",
                "Обнаружены PairIP компоненты: app=${applicationClassName ?: "-"} | " +
                    "items=${pairIpDiagnostics.joinToString(", ").ifEmpty { "none" }} | " +
                    "отключено licensecheck=${disabledPairIp.size}"
            )
        }

        return BaseManifestPatchResult(
            patchedBytes = manifest.bytes,
            originalPackage = originalPackage,
            clonePackage = clonePackage,
            originalAppComponentFactory = originalFactory,
            applicationClassName = applicationClassName,
            launcherActivity = launcherActivity,
            rewrittenProvidersCount = rewrittenProviders,
            rewrittenPermissionsCount = customPermissions.size,
            strippedSharedUserId = strippedSharedUserId,
            strippedLaunchPermissions = strippedLaunchPermissions,
            disabledPairIpComponents = disabledPairIp
        )
    }

    /**
     * Split APKs MUST share the exact same `package` attribute as base.apk
     * when installed together in one PackageInstaller.Session.
     */
    fun patchSplitManifest(manifestBytes: ByteArray, clonePackage: String): ByteArray {
        val manifest = AndroidManifestBlock()
        manifest.readBytes(ByteArrayInputStream(manifestBytes))
        manifest.packageName = clonePackage
        manifest.manifestElement?.let { el ->
            el.searchAttributeByResourceId(ATTR_ID_SHARED_USER_ID)?.let { attr ->
                el.removeAttribute(attr)
            }
        }
        manifest.refresh()
        return manifest.bytes
    }

    private fun ensurePackageQuery(manifestEl: ResXmlElement, targetPackage: String) {
        val queriesEl = manifestEl.childElementsList().firstOrNull { it.name == "queries" }
            ?: manifestEl.newElement("queries")
        val alreadyExists = queriesEl.childElementsList().any { child ->
            child.name == "package" &&
                child.searchAttributeByResourceId(ATTR_ID_NAME)?.valueAsString == targetPackage
        }
        if (!alreadyExists) {
            val pkgEl = queriesEl.newElement("package")
            pkgEl.getOrCreateAndroidAttribute("name", ATTR_ID_NAME)
                .setValueAsString(targetPackage)
        }
    }

    private fun resolveRelativeClassAttribute(element: ResXmlElement, attrId: Int, originalPackage: String) {
        val attr = element.searchAttributeByResourceId(attrId) ?: return
        val raw = attr.valueAsString ?: return
        val normalized = normalizeClassName(raw, originalPackage)
        if (normalized != raw) {
            attr.setValueAsString(normalized)
        }
    }

    private fun normalizeClassName(raw: String, originalPackage: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return trimmed
        return when {
            trimmed.startsWith(".") -> originalPackage + trimmed
            !trimmed.contains('.') -> "$originalPackage.$trimmed"
            else -> trimmed
        }
    }

    private fun isLauncherComponent(component: ResXmlElement): Boolean {
        for (child in component.childElementsList()) {
            if (child.name != "intent-filter") continue
            var hasMain = false
            var hasLauncher = false
            for (filterChild in child.childElementsList()) {
                val n = filterChild.searchAttributeByResourceId(ATTR_ID_NAME)?.valueAsString
                if (filterChild.name == "action" && n == "android.intent.action.MAIN") {
                    hasMain = true
                }
                if (filterChild.name == "category" &&
                    (n == "android.intent.category.LAUNCHER" || n == "android.intent.category.LEANBACK_LAUNCHER")
                ) {
                    hasLauncher = true
                }
            }
            if (hasMain && hasLauncher) return true
        }
        return false
    }

    private fun removeLauncherCategory(component: ResXmlElement) {
        for (child in component.childElementsList()) {
            if (child.name != "intent-filter") continue
            val toRemove = child.childElementsList().filter { filterChild ->
                filterChild.name == "category" &&
                    filterChild.searchAttributeByResourceId(ATTR_ID_NAME)?.valueAsString ==
                    "android.intent.category.LAUNCHER"
            }
            toRemove.forEach { it.removeSelf() }
        }
    }

    private fun ResXmlElement.childElementsList(): List<ResXmlElement> {
        val result = mutableListOf<ResXmlElement>()
        val iter = this.getElements()
        while (iter.hasNext()) {
            result.add(iter.next())
        }
        return result
    }
}
