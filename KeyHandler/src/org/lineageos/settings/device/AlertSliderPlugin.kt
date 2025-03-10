/*
 * Copyright (C) 2019 CypherOS
 * Copyright (C) 2014-2020 Paranoid Android
 * Copyright (C) 2023 The LineageOS Project
 * Copyright (C) 2023 Yet Another AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.AmbientDisplayConfiguration
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.UserHandle
import android.view.View
import android.util.Log
import com.android.systemui.plugins.OverlayPlugin
import com.android.systemui.plugins.annotations.Requires

@Requires(target = OverlayPlugin::class, version = OverlayPlugin.VERSION)
class AlertSliderPlugin : OverlayPlugin {
    private lateinit var pluginContext: Context
    private lateinit var handler: NotificationHandler
    private lateinit var ambientConfig: AmbientDisplayConfiguration
    private val dialogLock = Any()

    private data class NotificationInfo(
        val position: Int,
        val mode: Int,
    )

    private val updateReceiver: BroadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    KeyHandler.SLIDER_UPDATE_ACTION -> {
                        synchronized(dialogLock) {
                            val ringer =
                                intent.getIntExtra("mode", NONE).takeIf { it != NONE } ?: return

                            handler
                                .obtainMessage(
                                    MSG_DIALOG_UPDATE,
                                    NotificationInfo(
                                        intent.getIntExtra("position", KeyHandler.POSITION_BOTTOM),
                                        ringer
                                    )
                                )
                                .sendToTarget()
                            handler.sendEmptyMessage(MSG_DIALOG_SHOW)
                        }
                    }
                    Intent.ACTION_CONFIGURATION_CHANGED -> {
                        synchronized(dialogLock) {
                            pluginContext = context
                            handler.sendEmptyMessage(MSG_DIALOG_RECREATE)
                        }
                    }
                }
            }
        }

    override fun onCreate(context: Context, plugin: Context) {
        pluginContext = plugin
        val packageContext = context.createPackageContext(
            AlertSliderPlugin::class.java.getPackage()!!.name, 0)
        handler = NotificationHandler(packageContext)
        ambientConfig = AmbientDisplayConfiguration(context)

        plugin.registerReceiver(
            updateReceiver,
            IntentFilter(KeyHandler.SLIDER_UPDATE_ACTION),
            Context.RECEIVER_EXPORTED
        )
        plugin.registerReceiver(
            updateReceiver,
            IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED),
            Context.RECEIVER_EXPORTED
        )
    }

    override fun onDestroy() {
        pluginContext.unregisterReceiver(updateReceiver)
    }

    override fun setup(statusBar: View, navBar: View) {}

    private inner class NotificationHandler(private val context: Context) :
        Handler(Looper.getMainLooper()) {
        private var dialog = AlertSliderDialog(context)
        private var currColor = context.resources.getColor(R.color.alert_check_color, context.theme)
        private var currRotation = context.getDisplay().getRotation()
        private var showing = false
            set(value) {
                synchronized(dialogLock) {
                    if (field != value) {
                        // Remove pending messages
                        removeMessages(MSG_DIALOG_SHOW)
                        removeMessages(MSG_DIALOG_DISMISS)
                        removeMessages(MSG_DIALOG_RESET)

                        // Show/hide dialog
                        if (value) {
                            handleResetTimeout()
                            handleDoze()
                            dialog.show()
                        } else {
                            dialog.dismiss()
                        }
                    }
                    field = value
                }
            }

        override fun handleMessage(msg: Message) =
            when (msg.what) {
                MSG_DIALOG_SHOW -> handleShow()
                MSG_DIALOG_DISMISS -> handleDismiss()
                MSG_DIALOG_RESET -> handleResetTimeout()
                MSG_DIALOG_UPDATE -> handleUpdate(msg.obj as NotificationInfo)
                MSG_DIALOG_RECREATE -> handleRecreate()
                else -> {}
            }

        private fun handleShow() {
            showing = true
        }

        private fun handleDismiss() {
            showing = false
        }

        private fun handleResetTimeout() {
            synchronized(dialogLock) {
                removeMessages(MSG_DIALOG_DISMISS)
                sendMessageDelayed(
                    handler.obtainMessage(MSG_DIALOG_DISMISS, MSG_DIALOG_RESET, 0),
                    DIALOG_TIMEOUT
                )
            }
        }

        private fun handleUpdate(info: NotificationInfo) {
            synchronized(dialogLock) {
                handleResetTimeout()
                handleDoze()
                dialog.setState(info.position, info.mode)
            }
        }

        private fun handleDoze() {
            if (!ambientConfig.pulseOnNotificationEnabled(UserHandle.USER_CURRENT)) return
            val intent = Intent("com.android.systemui.doze.pulse")
            context.sendBroadcastAsUser(intent, UserHandle.CURRENT)
        }

        private fun handleRecreate() {
            // Remake if theme changed or rotation
            val packageContext = context.createPackageContext(
                AlertSliderPlugin::class.java.getPackage()!!.name, 0)

            val color = packageContext.resources.getColor(R.color.alert_check_color, context.theme)
            val rotation = context.getDisplay().getRotation()
            if (color != currColor || rotation != currRotation) {
                Log.d(TAG, "Recreate called")
                Log.d(TAG, color.toString())
                showing = false
                dialog = AlertSliderDialog(packageContext)
                currRotation = rotation
                currColor = color
            }
        }
    }

    companion object {
        private const val TAG = "AlertSliderPlugin"

        // Handler
        private const val MSG_DIALOG_SHOW = 1
        private const val MSG_DIALOG_DISMISS = 2
        private const val MSG_DIALOG_RESET = 3
        private const val MSG_DIALOG_UPDATE = 4
        private const val MSG_DIALOG_RECREATE = 5
        private const val DIALOG_TIMEOUT = 3000L

        // Ringer mode
        private const val NONE = -1
    }
}
