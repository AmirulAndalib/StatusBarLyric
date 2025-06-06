/*
 * StatusBarLyric
 * Copyright (C) 2021-2022 fkj@fkj233.cn
 * https://github.com/Block-Network/StatusBarLyric
 *
 * This software is free opensource software: you can redistribute it
 * and/or modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either
 * version 3 of the License, or any later version and our eula as
 * published by Block-Network contributors.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * and eula along with this software.  If not, see
 * <https://www.gnu.org/licenses/>
 * <https://github.com/Block-Network/StatusBarLyric/blob/main/LICENSE>.
 */

package statusbar.lyric.hook.module.xiaomi

import android.graphics.Rect
import android.os.Message
import android.view.MotionEvent
import android.widget.FrameLayout
import com.github.kyuubiran.ezxhelper.ClassUtils
import com.github.kyuubiran.ezxhelper.EzXHelper
import com.github.kyuubiran.ezxhelper.HookFactory.`-Static`.createHook
import com.github.kyuubiran.ezxhelper.finders.ConstructorFinder.`-Static`.constructorFinder
import com.github.kyuubiran.ezxhelper.finders.MethodFinder.`-Static`.methodFinder
import statusbar.lyric.R
import statusbar.lyric.config.XposedOwnSP
import statusbar.lyric.hook.module.SystemUILyric
import statusbar.lyric.tools.LogTools.log
import statusbar.lyric.tools.Tools
import statusbar.lyric.tools.Tools.callMethod
import statusbar.lyric.tools.Tools.getObjectField
import statusbar.lyric.tools.Tools.ifNotNull
import statusbar.lyric.tools.Tools.isNotNull
import statusbar.lyric.tools.XiaomiUtils
import java.lang.reflect.Method
import kotlin.math.max

class FocusNotifyController {
    companion object {
        private var focusedNotify: Any? = null
        private var canHideFocusNotify: Boolean = false
        private var isHideFocusNotify: Boolean = false
        var isInteraction: Boolean = false
        var isOS2FocusNotifyShowing: Boolean = false
        var isOS1FocusNotifyShowing: Boolean = false // OS1 不要支持隐藏焦点通知

        fun init(systemUILyric: SystemUILyric) {
            if (!XiaomiUtils.isXiaomi) return
            if (!XposedOwnSP.config.automateFocusedNotice) return

            EzXHelper.moduleRes.getString(R.string.automate_focused_notice).log()
            ClassUtils.loadClassOrNull("com.android.systemui.statusbar.phone.FocusedNotifPromptController").isNotNull {
                it.constructorFinder().singleOrNull().ifNotNull { constructor ->
                    constructor.createHook {
                        after { hook ->
                            focusedNotify = hook.thisObject
                        }
                    }
                }

                it.declaredMethods.filter { method ->
                    (method.name == "updateVisibility$1" || method.name == "showImmediately" || method.name == "hideImmediately" ||
                        method.name == "cancelFolme" || method.name == "setIsFocusedNotifPromptShowing")
                }.forEach { method ->
                    method.createHook {
                        before { hook ->
                            if (isHideFocusNotify) {
                                hook.result = null
                                "Update focus notify visibility state to hide".log()
                            }
                        }
                    }
                }
            }

            val shouldShowMethod =
                ClassUtils.loadClassOrNull("com.android.systemui.statusbar.phone.FocusedNotifPromptController").ifNotNull {
                    it.declaredMethods.singleOrNull { method -> method.name == "shouldShow" }
                }
            if (shouldShowMethod.isNotNull()) {
                canHideFocusNotify = true
                (shouldShowMethod as Method).createHook {
                    after { hook ->
                        val show = hook.result as Boolean
                        if (show) {
                            if (!isHideFocusNotify) {
                                isInteraction = true
                                isOS2FocusNotifyShowing = true
                                if (systemUILyric.isMusicPlaying)
                                    systemUILyric.updateLyricState(showLyric = false, showFocus = false)
                            }
                        } else {
                            isHideFocusNotify = false
                            isInteraction = false
                            isOS2FocusNotifyShowing = false
                            if (systemUILyric.isMusicPlaying)
                                systemUILyric.updateLyricState()
                        }
                        "New focus notify is ${if (show) "show" else "hide"}".log()
                        "isHideFocusNotify: $isHideFocusNotify, isOS2FocusNotifyShowing: $isOS2FocusNotifyShowing, isInteraction: $isInteraction".log()
                    }
                }
            } else {
                canHideFocusNotify = false
                ClassUtils.loadClassOrNull("com.android.systemui.statusbar.phone.FocusedNotifPromptController$2").isNotNull {
                    it.methodFinder().filterByName("handleMessage").single().createHook {
                        before { hook ->
                            val message = hook.args[0] as Message
                            if (message.what == 1003) {
                                val show = isFocusNotifyShowing()
                                if (systemUILyric.isMusicPlaying) {
                                    if (show) {
                                        isOS1FocusNotifyShowing = true
                                        systemUILyric.updateLyricState(showLyric = false, showFocus = false)
                                    } else {
                                        isOS1FocusNotifyShowing = false
                                        systemUILyric.updateLyricState()
                                    }
                                    "Focus notify is ${if (show) "show" else "hide"}".log()
                                }
                            }
                        }
                    }
                }
            }
        }

        fun hideFocusNotifyIfNeed() {
            if (!canControlFocusNotify()) return
            if (!isFocusNotifyShowing()) return
            if (isHideFocusNotify) return

            val mIcon = focusedNotify!!.getObjectField("mIcon")
            val mContent = focusedNotify!!.getObjectField("mContent")
            if (mIcon == null || mContent == null) return
            Tools.goMainThread {
                focusedNotify!!.callMethod("cancelFolme")
                focusedNotify!!.callMethod("hideImmediately", mIcon)
                focusedNotify!!.callMethod("hideImmediately", mContent)
                focusedNotify!!.callMethod("setIsFocusedNotifPromptShowing", false)
                isHideFocusNotify = true
                "Hiding focus notify".log()
            }
        }

        fun showFocusNotifyIfNeed() {
            if (!canControlFocusNotify()) return
            if (!isFocusNotifyShowing()) return
            if (!isHideFocusNotify) return

            val mIcon = focusedNotify!!.getObjectField("mIcon")
            val mContent = focusedNotify!!.getObjectField("mContent")
            if (mIcon == null || mContent == null) return
            isHideFocusNotify = false
            Tools.goMainThread {
                focusedNotify!!.callMethod("cancelFolme")
                focusedNotify!!.callMethod("showImmediately", mIcon)
                focusedNotify!!.callMethod("showImmediately", mContent)
                focusedNotify!!.callMethod("setIsFocusedNotifPromptShowing", true)
                "Showing focus notify".log()
            }
        }

        fun canControlFocusNotify(): Boolean {
            return XiaomiUtils.isXiaomi && focusedNotify.isNotNull() && canHideFocusNotify
        }

        private fun isFocusNotifyShowing(): Boolean {
            return isOS1FocusNotifyShowing || isOS2FocusNotifyShowing
        }

        fun shouldOpenFocusNotify(motionEvent: MotionEvent): Boolean {
            if (!canControlFocusNotify()) return false
            if (!isFocusNotifyShowing()) return false
            if (isHideFocusNotify) return false

            val focusedNotifyPromptView = focusedNotify!!.getObjectField("mView") ?: return false

            val x = motionEvent.rawX
            val rect = focusedNotifyPromptView.getObjectField("mRect") as Rect
            val mContent = focusedNotifyPromptView.getObjectField("mContent") as FrameLayout
            val right = rect.right
            if (right <= 0 || rect.left == right) {
                mContent.getGlobalVisibleRect(rect)
                rect.right = max(rect.right, mContent.measuredWidth + rect.left)
            }
            return x >= rect.left
        }
    }
}