package com.aiia.app.util

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.provider.Settings
import kotlin.math.roundToInt

object SystemCommands {

    @Volatile private var torchOn = false

    fun handle(context: Context, text: String): String? {
        val t = text.trim().lowercase()
        if (t.isBlank()) return null

        if (containsAny(t, "фонарик", "фонарь", "фонарик ")) return toggleTorch(context)
        if (containsAny(t, "яркость", "подсветк")) return setBrightness(context, text)
        if (containsAny(t, "громкость", "звук на ", "тихий режим", "беззвучн", "вибраци")) {
            return setSound(context, text)
        }
        if (t.startsWith("открой ") || t.startsWith("запусти ")) return openApp(context, text)
        return null
    }

    private fun containsAny(t: String, vararg terms: String): Boolean =
        terms.any { t.contains(it) }

    private fun toggleTorch(context: Context): String {
        val cam = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return "Нет камеры."
        val id = try { cam.cameraIdList.firstOrNull() } catch (_: Exception) { null }
            ?: return "Не найден модуль камеры."
        return try {
            val hasFlash = cam.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            if (!hasFlash) return "Фонарик не поддерживается на этом устройстве."
            val newOn = !torchOn
            cam.setTorchMode(id, newOn)
            torchOn = newOn
            ThoughtLog.add(ThoughtLog.Tag.TOOL, if (newOn) "Включён фонарик" else "Выключен фонарик")
            if (newOn) "Фонарик включён." else "Фонарик выключен."
        } catch (e: Exception) {
            "Не получилось переключить фонарик: ${e.message}"
        }
    }

    private fun setBrightness(context: Context, text: String): String {
        if (!Settings.System.canWrite(context)) {
            return "Для смены яркости нужно разрешение «Изменить системные настройки» — выдать в настройках телефона."
        }
        val pct = extractPercent(text)
        if (pct == null) {
            val bright = Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS, 128
            )
            val cur = (bright * 100 / 255).coerceIn(0, 100)
            return "Сейчас яркость $cur%."
        }
        val value = (pct * 255 / 100f).roundToInt().coerceIn(5, 255)
        runCatching {
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
        }
        ThoughtLog.add(ThoughtLog.Tag.TOOL, "Яркость установлена: $pct%")
        return "Поставил яркость на $pct%."
    }

    private fun setSound(context: Context, text: String): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return "Нет аудио-менеджера."
        val t = text.trim().lowercase()

        if (t.contains("тихий") || t.contains("беззвучн")) {
            am.ringerMode = AudioManager.RINGER_MODE_SILENT
            ThoughtLog.add(ThoughtLog.Tag.TOOL, "Тихий режим включён")
            return "Переключил в тихий режим."
        }
        if (t.contains("вибраци")) {
            am.ringerMode = AudioManager.RINGER_MODE_VIBRATE
            ThoughtLog.add(ThoughtLog.Tag.TOOL, "Режим вибрации")
            return "Переключил на вибрацию."
        }
        if (t.contains("обычн") || t.contains("звук на") && !t.contains("звук напомин")) {
            am.ringerMode = AudioManager.RINGER_MODE_NORMAL
            ThoughtLog.add(ThoughtLog.Tag.TOOL, "Обычный режим звука")
            return "Включил обычный режим звука."
        }

        val pct = extractPercent(text) ?: return "Укажите громкость в процентах."
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val level = (max * pct / 100).coerceIn(0, max)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
        ThoughtLog.add(ThoughtLog.Tag.TOOL, "Громкость установлена: $pct%")
        return "Поставил громкость на $pct%."
    }

    private fun openApp(context: Context, text: String): String {
        val query = text.trim().trimStart().lowercase()
            .removePrefix("открой ").removePrefix("запусти ")
            .trim().trimEnd('.', '!', '?')
        if (query.isBlank()) return "Какое приложение открыть?"
        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, 0)
        val lower = query.lowercase()
        val match = apps
            .filter { it.activityInfo.packageName.lowercase().contains(lower) ||
                it.loadLabel(pm).toString().lowercase().contains(lower) }
            .minByOrNull { it.loadLabel(pm).toString().lowercase().indexOf(lower).let { i -> if (i < 0) Int.MAX_VALUE else i } }
        if (match == null) {
            return "Приложение «$query» не найдено."
        }
        val label = match.loadLabel(pm).toString().ifBlank { query }
        pm.getLaunchIntentForPackage(match.activityInfo.packageName)?.let { launcher ->
            runCatching {
                launcher.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launcher)
            }.onFailure {
                return "Не удалось открыть «$label»."
            }
            ThoughtLog.add(ThoughtLog.Tag.TOOL, "Открыто приложение «$label»")
            return "Открываю «$label»."
        }
        return "Не удалось открыть «$label»."
    }

    fun extractPercent(text: String): Int? {
        return Regex("""(\d{1,3})\s*(?:%|процент|процентов|процента)""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 100)
    }
}
