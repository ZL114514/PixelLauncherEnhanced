package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Process
import android.os.UserHandle
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.TextView
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.data.common.Constants.RECENTS_APP_BUBBLE
import com.drdisagree.pixellauncherenhanced.xposed.HookRes.Companion.modRes
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callStaticMethodSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethodSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getStaticFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hasMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethodMatchPattern
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.log
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setStaticFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.XposedHelpers.getAdditionalInstanceField
import de.robv.android.xposed.XposedHelpers.setAdditionalInstanceField
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class RecentsAppBubble(context: Context) : ModPack(context) {

    private var enabled = false
    private var popupProxyClass: Any? = null

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            enabled = getBoolean(RECENTS_APP_BUBBLE, false)
        }
    }

    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        val taskOverlayFactoryClass = findClass("com.android.quickstep.TaskOverlayFactory")
        val appInfoShortcutClass = findClass("com.android.launcher3.popup.SystemShortcut\$AppInfo")
        hookPopup(loadPackageParam)

        appInfoShortcutClass
            .hookMethod("setIconAndLabelFor")
            .runBefore { param ->
                if (!isBubbleShortcut(param.thisObject)) return@runBefore

                val iconView = param.args[0]
                val labelView = param.args.getOrNull(1) as? TextView
                val context = (iconView as? View)?.context ?: return@runBefore
                val bubbleLabel = getBubbleLabel(context)
                val bubbleIcon = getBubbleIcon(context)

                when (iconView) {
                    is ImageView -> iconView.setImageDrawable(bubbleIcon)
                    is View -> if (bubbleIcon != null) iconView.background = bubbleIcon
                }
                labelView?.text = bubbleLabel
                param.thisObject.setFieldSilently("mLabel", bubbleLabel)
                param.result = null
            }

        appInfoShortcutClass
            .hookMethod("setIconAndContentDescriptionFor")
            .runBefore { param ->
                if (!isBubbleShortcut(param.thisObject)) return@runBefore

                val iconView = param.args[0] as ImageView
                val bubbleLabel = getBubbleLabel(iconView.context)
                iconView.setImageDrawable(getBubbleIcon(iconView.context))
                iconView.contentDescription = bubbleLabel
                param.thisObject.setFieldSilently("mLabel", bubbleLabel)
                param.result = null
            }

        val labelMethodName = when {
            appInfoShortcutClass.hasMethod("getLabel") -> "getLabel"
            appInfoShortcutClass.hasMethod("getText") -> "getText"
            else -> null
        }
        if (labelMethodName != null) {
            appInfoShortcutClass
                .hookMethod(labelMethodName)
                .runBefore { param ->
                    if (!isBubbleShortcut(param.thisObject)) return@runBefore

                    val context = (param.thisObject.getFieldSilently("mOriginalView") as? View)?.context
                        ?: mContext
                    param.result = getBubbleLabel(context)
                }
        }

        appInfoShortcutClass
            .hookMethod("createAccessibilityAction")
            .runBefore { param ->
                if (!isBubbleShortcut(param.thisObject)) return@runBefore

                val context = param.args[0] as Context
                val actionId =
                    param.thisObject.getFieldSilently("mAccessibilityActionId") as? Int
                        ?: View.generateViewId()

                param.result = AccessibilityNodeInfo.AccessibilityAction(
                    actionId,
                    getBubbleLabel(context)
                )
            }

        appInfoShortcutClass
            .hookMethod("onClick")
            .runBefore { param ->
                if (!isBubbleShortcut(param.thisObject)) return@runBefore

                param.thisObject.callMethodSilently("dismissTaskMenuView")
                showBubble(param.thisObject)
                param.result = null
            }

        taskOverlayFactoryClass
            .hookMethod("getEnabledShortcuts")
            .runAfter { param ->
                if (!enabled) return@runAfter

                val shortcuts = (param.result as? MutableList<Any>) ?: return@runAfter
                val taskView = param.args.firstOrNull() ?: return@runAfter
                val taskContainer = param.args.lastOrNull() ?: return@runAfter

                if (shortcuts.any(::isBubbleShortcut)) return@runAfter
                if (resolveTaskIntent(taskContainer) == null) return@runAfter
                if (!canShowBubble(taskView)) return@runAfter

                val target = resolveShortcutTarget(taskView) ?: return@runAfter
                val itemInfo = taskContainer.callMethodSilently("getItemInfo")
                    ?: taskContainer.getFieldSilently("itemInfo")
                    ?: return@runAfter

                val shortcut = createBubbleShortcut(
                    appInfoShortcutClass,
                    target,
                    itemInfo,
                    taskView
                ) ?: return@runAfter

                setAdditionalInstanceField(shortcut, KEY_BUBBLE_SHORTCUT, true)
                setAdditionalInstanceField(shortcut, KEY_TASK_CONTAINER, taskContainer)

                val appInfoIndex = shortcuts.indexOfFirst {
                    it.javaClass.name == appInfoShortcutClass?.name && !isBubbleShortcut(it)
                }
                shortcuts.add(
                    if (appInfoIndex >= 0) appInfoIndex + 1 else 0,
                    shortcut
                )
                param.result = shortcuts
            }
    }

    private fun hookPopup(loadPackageParam: LoadPackageParam) {
        val systemShortcutClass = findClass("com.android.launcher3.popup.SystemShortcut") ?: return
        val installClass = findClass("com.android.launcher3.popup.SystemShortcut\$Install") ?: return
        val factoryClass = findClass("com.android.launcher3.popup.SystemShortcut\$Factory") ?: return

        systemShortcutClass
            .hookMethodMatchPattern(".*")
            .runBefore { param ->
                if (!enabled) return@runBefore
                if (param.thisObject !== popupProxyClass) return@runBefore
                handlePopupProxyMethod(param.thisObject, param.method.name, param.args)?.let {
                    param.result = it
                }
            }

        installClass
            .hookMethodMatchPattern(".*")
            .runBefore { param ->
                if (!enabled) return@runBefore
                if (param.thisObject !== popupProxyClass) return@runBefore
                handlePopupProxyMethod(param.thisObject, param.method.name, param.args)?.let {
                    param.result = it
                }
            }

        val installFactory = buildPopupFactory(loadPackageParam, factoryClass, installClass)
        if (installFactory != null) {
            systemShortcutClass.setStaticFieldSilently("INSTALL", installFactory)
        }
    }

    private fun buildPopupFactory(
        loadPackageParam: LoadPackageParam,
        factoryClass: Class<*>,
        installClass: Class<*>
    ): Any? {
        return try {
            Proxy.newProxyInstance(
                loadPackageParam.classLoader,
                arrayOf(factoryClass)
            ) { _, method, args ->
                if (!enabled || method.name != "getShortcut" || args == null || args.size < 3) {
                    return@newProxyInstance null
                }

                val constructor = installClass.declaredConstructors.firstOrNull { it.parameterTypes.size == 3 }
                    ?: return@newProxyInstance null
                constructor.isAccessible = true
                constructor.newInstance(args[0], args[1], args[2]).also {
                    popupProxyClass = it
                    setAdditionalInstanceField(it, KEY_BUBBLE_SHORTCUT, true)
                }
            }
        } catch (throwable: Throwable) {
            log(this, "Unable to build popup shortcut factory: ${throwable.message}")
            null
        }
    }

    private fun handlePopupProxyMethod(
        shortcut: Any,
        methodName: String,
        args: Array<out Any?>
    ): Any? {
        when (methodName) {
            "onClick" -> {
                val itemInfo = shortcut.getFieldSilently("mItemInfo")
                val target = shortcut.getFieldSilently("mTarget")
                showBubbleFromPopup(target, itemInfo)
                shortcut.callMethodSilently("dismissTaskMenuView", target)
                return null
            }

            "setIconAndContentDescriptionFor" -> {
                val view = args.firstOrNull() as? ImageView ?: return null
                view.setImageDrawable(getBubbleIcon(view.context))
                view.contentDescription = getBubbleLabel(view.context)
                return null
            }

            "setIconAndLabelFor" -> {
                val iconView = args.getOrNull(0) as? View ?: return null
                val labelView = args.getOrNull(1) as? TextView ?: return null
                val icon = getBubbleIcon(iconView.context)
                if (iconView is ImageView) {
                    iconView.setImageDrawable(icon)
                } else if (icon != null) {
                    iconView.background = icon
                }
                val bubbleLabel = getBubbleLabel(iconView.context)
                labelView.text = bubbleLabel
                shortcut.setFieldSilently("mLabel", bubbleLabel)
                return null
            }

            "createAccessibilityAction" -> {
                val context = args.firstOrNull() as? Context ?: return null
                val actionId =
                    shortcut.getFieldSilently("mAccessibilityActionId") as? Int
                        ?: View.generateViewId()
                return AccessibilityNodeInfo.AccessibilityAction(
                    actionId,
                    getBubbleLabel(context)
                )
            }
        }
        return null
    }

    private fun showBubble(shortcut: Any) {
        val taskContainer = getAdditionalInstanceField(shortcut, KEY_TASK_CONTAINER) ?: return
        val target =
            shortcut.getFieldSilently("mTarget")
                ?: resolveShortcutTarget((taskContainer.callMethodSilently("getTaskView")
                    ?: return))
                ?: return
        val taskIntent = resolveTaskIntent(taskContainer) ?: return
        val user = resolveTaskUser(taskContainer)

        if (invokeShowAppBubble(target, taskIntent, user)) return

        val taskView = taskContainer.callMethodSilently("getTaskView") ?: return
        val proxy = resolveSystemUiProxy(taskView) ?: return
        invokeShowAppBubble(proxy, taskIntent, user)
    }

    private fun showBubbleFromPopup(target: Any?, itemInfo: Any?) {
        val intent = (itemInfo?.callMethodSilently("getIntent") as? Intent)?.let(::Intent) ?: return
        val user = (itemInfo?.getFieldSilently("user") as? UserHandle) ?: Process.myUserHandle()

        if (target != null && invokeShowAppBubble(target, intent, user)) return

        val proxy = resolveSystemUiProxyFromContext((target ?: itemInfo) ?: return) ?: return
        invokeShowAppBubble(proxy, intent, user)
    }

    private fun canShowBubble(taskView: Any): Boolean {
        val target = resolveShortcutTarget(taskView) ?: return false
        if (findShowAppBubbleMethod(target) != null) return true

        val proxy = resolveSystemUiProxy(taskView) ?: return false
        return findShowAppBubbleMethod(proxy) != null
    }

    private fun resolveShortcutTarget(taskView: Any): Any? {
        val viewContext = taskView.callMethodSilently("getContext") as? Context ?: return null

        return findClass("com.android.quickstep.views.RecentsViewContainer")
            ?.callStaticMethodSilently("containerFromContext", viewContext)
            ?: findClass("com.android.launcher3.views.ActivityContext")
                ?.callStaticMethodSilently("lookupContext", viewContext)
    }

    private fun resolveSystemUiProxy(taskView: Any): Any? {
        val viewContext = taskView.callMethodSilently("getContext") as? Context ?: return null
        return resolveSystemUiProxyFromContext(viewContext)
    }

    private fun resolveSystemUiProxyFromContext(host: Any): Any? {
        val viewContext = when (host) {
            is Context -> host
            else -> host.callMethodSilently("getContext") as? Context
        } ?: return null

        val systemUiProxyClass = findClass("com.android.quickstep.SystemUiProxy") ?: return null
        val instanceHolder = systemUiProxyClass.getStaticFieldSilently("INSTANCE")

        val proxy = when (instanceHolder) {
            null -> null
            else -> instanceHolder.callMethodSilently("get", viewContext)
        } ?: systemUiProxyClass.callStaticMethodSilently("get", viewContext)

        return proxy?.takeIf {
            (it.callMethodSilently("isActive") as? Boolean) != false
        }
    }

    private fun findShowAppBubbleMethod(target: Any): Method? {
        return target::class.java.declaredMethods
            .toList()
            .union(target::class.java.methods.toList())
            .firstOrNull { method ->
                method.name == "showAppBubble" &&
                        buildShowAppBubbleArgs(
                            method.parameterTypes,
                            Intent(),
                            userHandleFor(0)
                        ) != null
            }
    }

    private fun invokeShowAppBubble(target: Any, intent: Intent, user: UserHandle): Boolean {
        val method = findShowAppBubbleMethod(target) ?: return false
        val args = buildShowAppBubbleArgs(method.parameterTypes, intent, user) ?: return false

        return try {
            method.isAccessible = true
            method.invoke(target, *args)
            true
        } catch (throwable: Throwable) {
            log(this, "Unable to show app bubble: ${throwable.message}")
            false
        }
    }

    private fun buildShowAppBubbleArgs(
        parameterTypes: Array<Class<*>>,
        intent: Intent,
        user: UserHandle
    ): Array<Any?>? {
        val args = ArrayList<Any?>(parameterTypes.size)

        parameterTypes.forEach { parameterType ->
            when {
                Intent::class.java.isAssignableFrom(parameterType) -> {
                    args.add(Intent(intent))
                }

                UserHandle::class.java.isAssignableFrom(parameterType) -> {
                    args.add(user)
                }

                parameterType.isEnum && parameterType.simpleName.contains("EntryPoint", true) -> {
                    args.add(resolveEntryPoint(parameterType))
                }

                parameterType.simpleName.contains("BubbleBarLocation", true) -> {
                    args.add(null)
                }

                else -> return null
            }
        }

        return args.toTypedArray()
    }

    private fun resolveEntryPoint(enumClass: Class<*>): Any? {
        val constants = enumClass.enumConstants ?: return null
        val preferredKeys = listOf("RECENTS", "OVERVIEW", "TASK", "MENU", "LAUNCHER")

        return preferredKeys.firstNotNullOfOrNull { key ->
            constants.firstOrNull { constant ->
                constant.toString().contains(key, ignoreCase = true)
            }
        } ?: constants.firstOrNull()
    }

    private fun createBubbleShortcut(
        appInfoShortcutClass: Class<*>?,
        target: Any,
        itemInfo: Any,
        originalView: Any
    ): Any? {
        val constructors = appInfoShortcutClass?.declaredConstructors ?: return null

        constructors.forEach { constructor ->
            createBubbleShortcut(constructor, target, itemInfo, originalView)?.let {
                return it
            }
        }

        log(this, "No supported AppInfo constructor found for recents bubble shortcut.")
        return null
    }

    private fun createBubbleShortcut(
        constructor: Constructor<*>,
        target: Any,
        itemInfo: Any,
        originalView: Any
    ): Any? {
        val parameterTypes = constructor.parameterTypes

        val args = when {
            parameterTypes.size == 3 &&
                    parameterTypes[0].isInstance(target) &&
                    parameterTypes[1].isInstance(itemInfo) &&
                    parameterTypes[2].isInstance(originalView) -> {
                arrayOf(target, itemInfo, originalView)
            }

            parameterTypes.size == 4 &&
                    parameterTypes[0].isInstance(target) &&
                    parameterTypes[1].isInstance(itemInfo) &&
                    parameterTypes[2].isInstance(originalView) &&
                    !parameterTypes[3].isPrimitive -> {
                arrayOf(target, itemInfo, originalView, null)
            }

            else -> return null
        }

        return try {
            constructor.isAccessible = true
            constructor.newInstance(*args)
        } catch (_: Throwable) {
            null
        }
    }

    private fun resolveTaskUser(taskContainer: Any): UserHandle {
        val task = taskContainer.callMethodSilently("getTask")
            ?: taskContainer.callMethodSilently("getTaskView")?.callMethodSilently("getFirstTask")
        val key = task?.getFieldSilently("key")
        val userId = key?.getFieldSilently("userId") as? Int ?: 0
        return userHandleFor(userId)
    }

    private fun userHandleFor(userId: Int): UserHandle {
        val ofMethod = UserHandle::class.java.methods.firstOrNull { method ->
            method.name == "of" &&
                    method.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
        }

        if (ofMethod != null) {
            return ofMethod.invoke(null, userId) as UserHandle
        }

        val constructor = UserHandle::class.java.declaredConstructors.firstOrNull { declared ->
            declared.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
        }

        if (constructor != null) {
            constructor.isAccessible = true
            return constructor.newInstance(userId) as UserHandle
        }

        return Process.myUserHandle()
    }

    private fun resolveTaskIntent(taskContainer: Any): Intent? {
        val task = taskContainer.callMethodSilently("getTask")
            ?: taskContainer.callMethodSilently("getTaskView")?.callMethodSilently("getFirstTask")
            ?: return null

        val key = task.getFieldSilently("key")
        val baseIntent = when (val intent = key?.getFieldSilently("baseIntent")) {
            is Intent -> Intent(intent)
            else -> null
        } ?: return null

        val topComponent = task.callMethodSilently("getTopComponent") as? ComponentName
            ?: key?.getFieldSilently("sourceComponent") as? ComponentName

        if (baseIntent.component == null && topComponent != null) {
            baseIntent.component = topComponent
        }
        if (baseIntent.`package` == null && topComponent != null) {
            baseIntent.setPackage(topComponent.packageName)
        }

        return baseIntent
    }

    @SuppressLint("DiscouragedApi")
    private fun getBubbleIcon(context: Context): Drawable? {
        val bubbleIconRes = context.resources.getIdentifier(
            "ic_bubble_button",
            "drawable",
            context.packageName
        )

        return if (bubbleIconRes != 0) {
            context.getDrawable(bubbleIconRes)
        } else {
            null
        }
    }

    @SuppressLint("DiscouragedApi")
    private fun getBubbleLabel(context: Context): CharSequence {
        val openAsBubbleRes = context.resources.getIdentifier(
            "open_app_as_a_bubble",
            "string",
            context.packageName
        )
        if (openAsBubbleRes != 0) {
            return context.getString(openAsBubbleRes)
        }

        val bubbleRes = context.resources.getIdentifier(
            "bubble",
            "string",
            context.packageName
        )
        if (bubbleRes != 0) {
            return context.getString(bubbleRes)
        }

        return modRes.getString(R.string.open_app_as_a_bubble)
    }

    private fun isBubbleShortcut(shortcut: Any?): Boolean {
        return getAdditionalInstanceField(shortcut, KEY_BUBBLE_SHORTCUT) == true
    }

    companion object {
        private const val KEY_BUBBLE_SHORTCUT = "ple_recents_app_bubble_shortcut"
        private const val KEY_TASK_CONTAINER = "ple_recents_app_bubble_task_container"
    }
}
