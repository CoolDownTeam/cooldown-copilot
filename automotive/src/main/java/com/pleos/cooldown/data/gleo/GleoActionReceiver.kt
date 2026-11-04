package com.pleos.cooldown.data.gleo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.pleos.cooldown.CooldownApplication
import com.pleos.cooldown.domain.cooldown.Feedback
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gleo Interface 수신기 (명세 4.8, F2-15~17, F3-03). Gleo가 `sendBroadcast`로 보내는 Action을 받아
 * 7초 안에 `APP_ACTION_RESPONSE`로 응답한다.
 *
 * 누구나 같은 broadcast를 보낼 수 있으므로 정의된 값만 받고, 실제 동작은 개입 중일 때만 한다.
 */
class GleoActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_ADJUST_CABIN_COMFORT -> onAdjustCabinComfort(context, intent)
            ACTION_ANSWER_REST_SUGGESTION -> onAnswerRestSuggestion(context, intent)
        }
    }

    private fun onAdjustCabinComfort(context: Context, intent: Intent) {
        val feeling = param(intent, "feeling")
        val feedback = when (feeling) {
            "cold" -> Feedback.COLD
            "stop" -> Feedback.STOP
            else -> null
        }
        if (feedback == null) {
            respond(context, ACTION_ADJUST_CABIN_COMFORT, STATUS_ASKING,
                "Tell me whether the cabin feels too cold or you want me to stop.", "feeling", feeling)
            return
        }

        val pending = goAsync()
        runner(context).handleGleoFeedback(feedback) { handled ->
            val message = when {
                !handled -> "The app is not adjusting the cabin climate right now."
                feedback == Feedback.COLD -> "Restored the cabin temperature the driver had set."
                else -> "Stopped adjusting the cabin climate for this trip."
            }
            respond(context, ACTION_ADJUST_CABIN_COMFORT, STATUS_SUCCESS, message, "feeling", feeling)
            pending.finish()
        }
    }

    /** F3-03: 휴게소 제안에 대한 답 */
    private fun onAnswerRestSuggestion(context: Context, intent: Intent) {
        val raw = param(intent, "accept")
        val accepted = raw.toBooleanStrictOrNull()
        if (accepted == null) {
            respond(context, ACTION_ANSWER_REST_SUGGESTION, STATUS_ASKING,
                "Tell me whether the driver wants to stop at the rest area.", "accept", raw)
            return
        }
        val pending = goAsync()
        runner(context).handleGleoRestReply(accepted) { handled ->
            val message = when {
                !handled -> "There is no rest area suggestion waiting for an answer."
                accepted -> "The driver will stop at the rest area. A reminder will come before the entrance."
                else -> "The driver will keep driving. No more suggestions for a while."
            }
            respond(context, ACTION_ANSWER_REST_SUGGESTION, STATUS_SUCCESS, message, "accept", raw)
            pending.finish()
        }
    }

    private fun runner(context: Context) = (context.applicationContext as CooldownApplication).container.copilotRunner

    private fun respond(context: Context, function: String, status: String, message: String, key: String, value: String) {
        val data = JSONArray().put(JSONObject().put(key, value)).toString()
        val response = JSONObject()
            .put(KEY_FUNCTION_NAME, function)
            .put(KEY_STATUS, status)
            .put(KEY_MESSAGE, message)
            .put(KEY_DATA, data)
        context.sendBroadcast(
            Intent(ACTION_APP_ACTION_RESPONSE)
                .putExtra(EXTRA_PACKAGE_NAME, context.packageName)
                .putExtra(EXTRA_RESPONSE, response.toString())
        )
        Log.i(TAG, "responded $response")
    }

    /** 문서 예시는 ACTION_PARAM 값을 따옴표로 한 번 더 감싸므로 벗겨서 읽는다. 값이 없으면 빈 문자열 */
    private fun param(intent: Intent, key: String): String {
        val raw = intent.getStringExtra(EXTRA_ACTION_PARAM)
        val json = raw?.trim()?.removeSurrounding("\"")?.replace("\\\"", "\"") ?: return ""
        return runCatching { JSONObject(json).opt(key)?.toString().orEmpty() }.getOrDefault("")
    }

    companion object {
        const val ACTION_ADJUST_CABIN_COMFORT = "ADJUST_CABIN_COMFORT"
        const val ACTION_ANSWER_REST_SUGGESTION = "ANSWER_REST_SUGGESTION"
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
