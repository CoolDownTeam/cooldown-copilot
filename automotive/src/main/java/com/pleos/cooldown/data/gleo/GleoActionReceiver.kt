package com.pleos.cooldown.data.gleo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.pleos.cooldown.CooldownApplication
import com.pleos.cooldown.domain.cooldown.Feedback
import org.json.JSONObject

/**
 * Gleo Interface 수신기 (명세 4.8, F2-15~17). Gleo가 `sendBroadcast`로 보내는 Action을 받아
 * 7초 안에 `APP_ACTION_RESPONSE`로 응답한다.
 *
 * 누구나 같은 broadcast를 보낼 수 있으므로 정의된 값만 받고, 실제 동작은 개입 중일 때만 한다.
 */
class GleoActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ADJUST_CABIN_COMFORT) return
        val feeling = parseFeeling(intent.getStringExtra(EXTRA_ACTION_PARAM))
        val feedback = when (feeling) {
            "cold" -> Feedback.COLD
            "stop" -> Feedback.STOP
            else -> null
        }
        if (feedback == null) {
            respond(context, STATUS_ASKING, "Tell me whether the cabin feels too cold or you want me to stop.", feeling)
            return
        }

        val pending = goAsync()
        val runner = (context.applicationContext as CooldownApplication).container.copilotRunner
        runner.handleGleoFeedback(feedback) { handled ->
            val message = when {
                !handled -> "The app is not adjusting the cabin climate right now."
                feedback == Feedback.COLD -> "Restored the cabin temperature the driver had set."
                else -> "Stopped adjusting the cabin climate for this trip."
            }
            respond(context, STATUS_SUCCESS, message, feeling)
            pending.finish()
        }
    }

    private fun respond(context: Context, status: String, message: String, feeling: String) {
        val response = JSONObject()
            .put(KEY_FUNCTION_NAME, ACTION_ADJUST_CABIN_COMFORT)
            .put(KEY_STATUS, status)
            .put(KEY_MESSAGE, message)
            .put(KEY_DATA, "[{\"feeling\":\"$feeling\"}]")
        context.sendBroadcast(
            Intent(ACTION_APP_ACTION_RESPONSE)
                .putExtra(EXTRA_PACKAGE_NAME, context.packageName)
                .putExtra(EXTRA_RESPONSE, response.toString())
        )
        Log.i(TAG, "responded $response")
    }

    /** 문서 예시는 ACTION_PARAM 값을 따옴표로 한 번 더 감싸므로 벗겨서 읽는다 */
    private fun parseFeeling(raw: String?): String {
        val json = raw?.trim()?.removeSurrounding("\"")?.replace("\\\"", "\"") ?: return ""
        return runCatching { JSONObject(json).optString("feeling") }.getOrDefault("")
    }

    companion object {
        const val ACTION_ADJUST_CABIN_COMFORT = "ADJUST_CABIN_COMFORT"
        const val ACTION_APP_ACTION_RESPONSE = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"
        private const val EXTRA_ACTION_PARAM = "ACTION_PARAM"
        private const val EXTRA_PACKAGE_NAME = "packageName"
        private const val EXTRA_RESPONSE = "response"
        private const val KEY_FUNCTION_NAME = "functionName"
        private const val KEY_STATUS = "status"
        private const val KEY_MESSAGE = "message"
        private const val KEY_DATA = "data"
        private const val STATUS_SUCCESS = "success"
        private const val STATUS_ASKING = "asking"
        private const val TAG = "GleoAction"
    }
}
