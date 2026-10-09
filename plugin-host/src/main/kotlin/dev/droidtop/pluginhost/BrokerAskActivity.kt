package dev.droidtop.pluginhost

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What the broker asks Android for on a plugin's behalf, in droidtop's own process and under droidtop's own identity
 * (docs/plugin-api.md 3 D4, 4.1): Android's document picker for `files.pick`, and Android's runtime permission prompt
 * for an op droidtop needs a permission for. A plugin process cannot show either: a contained one has no window and no
 * permissions at all. The broker calls [pick] or [requestPermission] from its binder thread, only during a call the
 * person started, and waits; this invisible activity shows the system's own screen, hands back the answer and finishes,
 * which returns the person to where they were. Backing out is an answer (no file, not granted).
 */
class BrokerAskActivity : Activity() {
    private var answered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID)
        if (id == null || waiting[id] == null) {
            finish()
            return
        }
        if (savedInstanceState != null) return
        when (intent.getStringExtra(EXTRA_KIND)) {
            KIND_PICK -> {
                val create = intent.getStringExtra(EXTRA_MODE) == "create"
                val request = Intent(if (create) Intent.ACTION_CREATE_DOCUMENT else Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = intent.getStringExtra(EXTRA_MIME) ?: "*/*"
                    intent.getStringExtra(EXTRA_NAME)?.let { putExtra(Intent.EXTRA_TITLE, it) }
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                    if (create) addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
                runCatching { startActivityForResult(request, REQUEST) }.onFailure { answer(id, null) }
            }
            KIND_PERMISSION -> {
                val permission = intent.getStringExtra(EXTRA_PERMISSION)
                if (permission == null) answer(id, false) else requestPermissions(arrayOf(permission), REQUEST)
            }
            else -> answer(id, null)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val id = intent.getStringExtra(EXTRA_ID) ?: return finish()
        val picked = data?.takeIf { resultCode == RESULT_OK }?.let { result -> result.data?.let { uri -> uri to result.flags } }
        answer(id, picked)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val id = intent.getStringExtra(EXTRA_ID) ?: return finish()
        answer(id, grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED })
    }

    override fun onDestroy() {
        // Gone without an answer (the person left, or Android took the activity away): that is "no".
        if (!answered && isFinishing) intent.getStringExtra(EXTRA_ID)?.let { waiting.remove(it)?.complete(null) }
        super.onDestroy()
    }

    private fun answer(id: String, value: Any?) {
        answered = true
        waiting.remove(id)?.complete(value)
        finish()
    }

    companion object {
        private const val EXTRA_ID = "dev.droidtop.pluginhost.ask.id"
        private const val EXTRA_KIND = "dev.droidtop.pluginhost.ask.kind"
        private const val EXTRA_MODE = "dev.droidtop.pluginhost.ask.mode"
        private const val EXTRA_MIME = "dev.droidtop.pluginhost.ask.mime"
        private const val EXTRA_NAME = "dev.droidtop.pluginhost.ask.name"
        private const val EXTRA_PERMISSION = "dev.droidtop.pluginhost.ask.permission"
        private const val KIND_PICK = "pick"
        private const val KIND_PERMISSION = "permission"
        private const val REQUEST = 1

        /** How long the person has to answer; then the answer is no. */
        private const val TIMEOUT_MS = 5L * 60 * 1000

        private val waiting = ConcurrentHashMap<String, CompletableDeferred<Any?>>()

        /** Shows the system's own screen for one request and blocks the calling (binder) thread until it is answered. */
        private fun ask(context: Context, fill: Intent.() -> Unit): Any? {
            val id = UUID.randomUUID().toString()
            val answer = CompletableDeferred<Any?>()
            waiting[id] = answer
            val intent = Intent(context, BrokerAskActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                putExtra(EXTRA_ID, id)
                fill()
            }
            val started = runCatching { context.startActivity(intent) }.isSuccess
            if (!started) {
                waiting.remove(id)
                return null
            }
            return try {
                runBlocking { withTimeoutOrNull(TIMEOUT_MS) { answer.await() } }
            } finally {
                waiting.remove(id)
            }
        }

        /** Android's document picker: the picked document and the grant flags it came with, or null when the person backed out. */
        fun pick(context: Context, mode: String, mime: String, name: String?): Pair<Uri, Int>? {
            @Suppress("UNCHECKED_CAST")
            return ask(context) {
                putExtra(EXTRA_KIND, KIND_PICK)
                putExtra(EXTRA_MODE, mode)
                putExtra(EXTRA_MIME, mime)
                name?.let { putExtra(EXTRA_NAME, it) }
            } as? Pair<Uri, Int>
        }

        /** Android's runtime permission prompt for [permission]; true when droidtop holds it afterwards. */
        fun requestPermission(context: Context, permission: String): Boolean =
            ask(context) {
                putExtra(EXTRA_KIND, KIND_PERMISSION)
                putExtra(EXTRA_PERMISSION, permission)
            } == true || context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }
}
