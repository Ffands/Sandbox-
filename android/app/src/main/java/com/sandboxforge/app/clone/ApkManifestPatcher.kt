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
    val launcherActivity: String?,
    val rewrittenProvidersCount: Int,
    val rewrittenPermissionsCount: Int,
    val strippedSharedUserId: String?
)

/**
 * Patches binary AndroidManifest.xml chunks for both base.apk and split_*.apk using ARSCLib.
 * Critical invariants preserved:
 * 1. Resolves relative class names (.MainActivity, .App) BEFORE renaming package
 * 2. Strips sharedUserId (so cloned OEM/system apps install cleanly without UID mismatch)
 * 3. Rewrites provider authorities & custom permissions to prevent INSTALL_FAILED_CONFLICTING_PROVIDER
 * 4. Injects SandboxAppComponentFactory (saving original factory for clean delegation)
 * 5. Injects SandboxShimInitProvider (initOrder=MAX_VALUE) for Android 8–17 startup telemetry
 * 6. Optionally removes CATEGORY_LAUNCHER while keeping exported=true on the launch Activity
 */
object ApkManifestPatcher {
    private const val ATTR_ID_NAME = 0x01010003
    private const val ATTR_ID_EXPORTED = 0x01010010
    private const val ATTR_ID_AUTHORITIES = 0x01010018
    private const val ATTR_ID_INIT_ORDER = 0x0101001a
    private const val ATTR_ID_TARGET_ACTIVITY = 0x01010402
    private const val ATTR_ID_APP_COMPONENT_FACTORY = 0x0101057a
    private const val ATTR_ID_SHARED_USER_ID = 0x0101000b
    private const val ATTR_ID_SHARED_USER_LABEL = 0x01010261

    private const val SHIM_FACTORY_CLASS = "com.sandboxforge.shim.SandboxAppComponentFactory"
    private const val SHIM_PROVIDER_CLASS = "com.sandboxforge.shim.SandboxShimInitProvider"

    fun buildClonePackageName(originalPackage: String, containerPrefix: String = "sf.c1"): String {
        val cleaned = originalPackage.trim().trim('.')
        return "$containerPrefix.$cleaned"
    }

    fun patchBaseManifest(
        manifestBytes: ByteArray,
        clonePackage: String,
        hideFromLauncher: Boolean
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

        // 3. Walk all components under <application> to resolve relative names, rewrite authorities,
        //    and locate the MAIN/LAUNCHER entry activity
        var launcherActivity: String? = null
        var rewrittenProviders = 0
        val componentTags = setOf("activity", "activity-alias", "service", "receiver", "provider")

        appEl.childElementsList().forEach { child ->
            val tag = child.name
            if (tag in componentTags) {
                resolveRelativeClassAttribute(child, ATTR_ID_NAME, originalPackage)
                if (tag == "activity-alias") {
                    resolveRelativeClassAttribute(child, ATTR_ID_TARGET_ACTIVITY, originalPackage)
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
                    val compName = child.searchAttributeByResourceId(ATTR_ID_NAME)?.valueAsString
                    if (launcherActivity == null && !compName.isNullOrBlank()) {
                        launcherActivity = compName
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

        // 5. Capture original appComponentFactory (if any) and inject SandboxAppComponentFactory
        val factoryAttr = appEl.searchAttributeByResourceId(ATTR_ID_APP_COMPONENT_FACTORY)
        val originalFactory = factoryAttr?.valueAsString?.let { normalizeClassName(it, originalPackage) }
        appEl.getOrCreateAndroidAttribute("appComponentFactory", ATTR_ID_APP_COMPONENT_FACTORY)
            .setValueAsString(SHIM_FACTORY_CLASS)

        // 6. Inject SandboxShimInitProvider (runs first with full Context on Android 8–17)
        val shimProvider = appEl.newElement("provider")
        shimProvider.getOrCreateAndroidAttribute("name", ATTR_ID_NAME)
            .setValueAsString(SHIM_PROVIDER_CLASS)
        shimProvider.getOrCreateAndroidAttribute("authorities", ATTR_ID_AUTHORITIES)
            .setValueAsString("$clonePackage.sandboxforge.shim")
        shimProvider.getOrCreateAndroidAttribute("exported", ATTR_ID_EXPORTED)
            .setValueAsBoolean(false)
        shimProvider.getOrCreateAndroidAttribute("initOrder", ATTR_ID_INIT_ORDER)
            .setTypeAndData(ValueType.DEC, Int.MAX_VALUE)

        // 7. Finally update root package name
        manifest.packageName = clonePackage
        manifest.refresh()

        AppLogger.d(
            "ManifestPatcher",
            "Патч base manifest: $originalPackage -> $clonePackage | " +
                "launcher=$launcherActivity | providers=$rewrittenProviders | " +
                "customPerms=${customPermissions.size} | origFactory=${originalFactory ?: "none"}"
        )

        return BaseManifestPatchResult(
            patchedBytes = manifest.bytes,
            originalPackage = originalPackage,
            clonePackage = clonePackage,
            originalAppComponentFactory = originalFactory,
            launcherActivity = launcherActivity,
            rewrittenProvidersCount = rewrittenProviders,
            rewrittenPermissionsCount = customPermissions.size,
            strippedSharedUserId = strippedSharedUserId
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
