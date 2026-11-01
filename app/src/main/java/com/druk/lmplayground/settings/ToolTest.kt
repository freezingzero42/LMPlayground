@file:OptIn(ExperimentalMaterial3Api::class)

package com.druk.lmplayground.settings

// Tool test (Settings -> Tools -> top-right button). Self-contained in this
// file so it can be removed in one go: the only other changes are the button
// in ToolsScreen and the dialog in ToolsFragment. Text is English only.

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.druk.llamacpp.LlamaGenerationCallback
import com.druk.llamacpp.LlamaGenerationSession
import com.druk.llamacpp.LlamaModel
import com.druk.llamacpp.LlamaProgressCallback
import com.druk.llamacpp.chat.ToolCallLoop
import com.druk.llamacpp.chat.ToolCallRecord
import com.druk.llamacpp.tools.ToolRegistry
import com.druk.lmplayground.App
import com.druk.lmplayground.conversation.PreambleCacheManager
import com.druk.lmplayground.conversation.stripThinkTags
import com.druk.lmplayground.models.ChatTemplateOverrides
import com.druk.lmplayground.models.ModelInfo
import com.druk.lmplayground.models.ModelInfoProvider
import com.druk.lmplayground.storage.StoragePreferences
import com.druk.lmplayground.storage.StorageRepository
import com.druk.lmplayground.theme.PopupMenuShape
import com.druk.lmplayground.theme.menuBorder
import com.druk.lmplayground.tools.createDefault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * One check: a prompt that should make the model call [tool] with arguments
 * [argsOk] accepts, and then use what the tool returned in its answer.
 *
 * Tools really run, so answers are checked against real results, except the
 * ones with side effects: with a [simulated] result, the tool is not called
 * (no alarm is set, no calendar opens, no note is saved).
 */
data class ToolTestCase(
    val tool: String,
    val prompt: String,
    val simulated: String? = null,
    val argsOk: (JSONObject) -> Boolean,
    /**
     * From what the tool returned, the text the answer must contain (commas
     * and spaces ignored), or null when there is nothing to check, e.g. the
     * tool reported an error.
     */
    val expect: (JSONObject) -> String?,
)

private fun JSONObject.text(key: String) = optString(key).lowercase()

/** [key] from a successful result, or null after an error or when it's missing. */
private fun JSONObject.field(key: String): String? =
    if (has("error")) null else optString(key).trim().takeIf { it.isNotEmpty() }

val TOOL_TEST_CASES = listOf(
    ToolTestCase(
        "get_date_time", "What day of the week is it today?",
        argsOk = { true },
        expect = { r -> r.field("date")?.substringBefore(',') }, // "Sunday"
    ),
    ToolTestCase(
        "set_alarm", "Set an alarm for 6:30 PM.",
        simulated = """{"result":"Alarm set for 6:30 PM today."}""",
        argsOk = { it.text("time").contains("6:30") },
        expect = { "6:30" },
    ),
    ToolTestCase(
        "add_calendar_event", "Add a dentist appointment to my calendar this Friday at 3 PM.",
        simulated = """{"result":"Opened the Calendar app with \"Dentist\" this Friday at 3:00 PM. The user taps Save to add it."}""",
        argsOk = { it.text("title").contains("dentist") },
        expect = { null },
    ),
    ToolTestCase(
        "calculator", "Use the calculator: what is 1234 times 5678?",
        argsOk = { it.text("expression").let { e -> "1234" in e && "5678" in e } },
        expect = { r -> r.field("result") }, // 7006652
    ),
    ToolTestCase(
        "convert_units", "How many kilometers is 26.2 miles?",
        argsOk = { it.text("from").startsWith("mi") && it.text("to").startsWith("k") },
        // The whole number is enough: "42.16 km" and "about 42.2 km" both answer it.
        expect = { r -> r.field("result")?.let { v -> v.substringBefore('.') } },
    ),
    ToolTestCase(
        "device_info", "How much RAM does my phone have in total?",
        argsOk = { true },
        expect = { r -> r.field("ram_total")?.substringBefore(' ') }, // "7.6" from "7.6 GB"
    ),
    ToolTestCase(
        "wikipedia", "Look up the Eiffel Tower on Wikipedia and tell me the year it was completed.",
        argsOk = { it.text("query").contains("eiffel") },
        expect = { r -> if (r.field("summary")?.contains("1889") == true) "1889" else null },
    ),
    ToolTestCase(
        "location", "What city am I in right now?",
        argsOk = { true },
        expect = { r -> r.field("place")?.substringBefore(',') }, // needs the Location permission
    ),
    ToolTestCase(
        "memory", "Remember that my favorite color is teal.",
        simulated = """{"saved":true,"id":1}""",
        argsOk = { it.text("action") in setOf("save", "add", "remember", "store") && it.text("content").contains("teal") },
        expect = { null },
    ),
    ToolTestCase(
        "web_search", "Search the web for this week's weather forecast in Oslo.",
        argsOk = { it.text("query").contains("oslo") },
        expect = { null }, // live results differ every time
    ),
    ToolTestCase(
        "web_fetch", "Fetch https://example.com and tell me the page title.",
        argsOk = { it.text("url").contains("example.com") },
        expect = { r -> r.field("title") }, // "Example Domain"
    ),
    ToolTestCase(
        "run_javascript", "Use JavaScript to reverse the string \"stressed\".",
        argsOk = { it.text("code").contains("stressed") },
        expect = { r -> r.field("result") }, // "desserts"
    ),
)

enum class ToolTestState { Waiting, Running, Passed, Failed }

data class ToolTestResult(
    val case: ToolTestCase,
    val state: ToolTestState = ToolTestState.Waiting,
    val detail: String = "",
)

/** Loads the chosen model on its own, runs every case on a fresh session, then unloads it. */
class ToolTestViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = StoragePreferences(app)
    private val storage = StorageRepository(app, prefs)

    /** The chat's preamble KV cache, so the tool list is processed once and then loaded from disk. */
    private val preambleCache = PreambleCacheManager(app.filesDir)

    private val _models = MutableStateFlow<List<ModelInfo>>(emptyList())
    val models: StateFlow<List<ModelInfo>> = _models
    private val _results = MutableStateFlow(TOOL_TEST_CASES.map { ToolTestResult(it) })
    val results: StateFlow<List<ToolTestResult>> = _results
    private val _status = MutableStateFlow("Pick a model, then tap Run all, or Run next to one tool.")
    val status: StateFlow<String> = _status
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running
    private var job: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val downloaded = storage.getModelFiles().map { it.name }.toSet()
            _models.value = ModelInfoProvider.getModelsWithStatus(downloaded)
                .filter { it.isDownloaded }
                .map { it.model }
        }
    }

    fun stop() { job?.cancel() }

    /**
     * What stays loaded between runs while the test screen is open, so
     * testing one tool and then another doesn't reload the model.
     */
    private class Loaded(
        val filename: String,
        val modelSize: Long,
        val handle: StorageRepository.ModelFileHandle,
        val llama: LlamaModel,
        val tools: TestTools,
        val toolsJson: String,
        val thinking: Boolean,
    ) {
        var session: LlamaGenerationSession? = null
        /** True once the session holds a question, so it must be rewound first. */
        var used = false

        fun release() {
            try { session?.destroy() } catch (_: Throwable) {}
            try { llama.unloadModel() } catch (_: Throwable) {}
            handle.close()
        }
    }

    private var loaded: Loaded? = null

    /** Unloads the model; the screen calls this when it closes. */
    fun release() {
        viewModelScope.launch(Dispatchers.IO) {
            job?.cancel()
            job?.join()
            loaded?.release()
            loaded = null
        }
    }

    override fun onCleared() {
        loaded?.release()
        loaded = null
    }

    /** Tests every tool, or only the one at [only]. */
    fun run(model: ModelInfo, only: Int? = null) {
        if (_running.value) return
        val indices = only?.let { listOf(it) } ?: TOOL_TEST_CASES.indices.toList()
        _results.value = _results.value.mapIndexed { i, r -> if (i in indices) ToolTestResult(r.case) else r }
        job = viewModelScope.launch(Dispatchers.IO) {
            _running.value = true
            try {
                val l = load(model) ?: return@launch
                indices.forEachIndexed { n, i ->
                    val case = TOOL_TEST_CASES[i]
                    _status.value = if (only != null) "Testing ${case.tool}…"
                    else "Testing ${case.tool} (${n + 1} of ${indices.size})…"
                    update(i, ToolTestResult(case, ToolTestState.Running))
                    val started = System.currentTimeMillis()
                    val r = runOn(l, case)
                    val seconds = "%.1f".format((System.currentTimeMillis() - started) / 1000.0)
                    update(i, r.copy(detail = "${seconds}s. ${r.detail}"))
                }
                _status.value = if (only != null) {
                    val r = _results.value[only]
                    "${model.name}: ${r.case.tool} ${if (r.state == ToolTestState.Passed) "passed" else "failed"}."
                } else {
                    "${model.name}: ${_results.value.count { it.state == ToolTestState.Passed }} of ${TOOL_TEST_CASES.size} passed."
                }
            } catch (e: CancellationException) {
                _status.value = "Stopped."
                throw e
            } catch (t: Throwable) {
                _status.value = "The test couldn't run: ${t.message}"
            } finally {
                withContext(NonCancellable) {
                    _results.value = _results.value.map {
                        if (it.state == ToolTestState.Running) it.copy(state = ToolTestState.Waiting) else it
                    }
                    _running.value = false
                }
            }
        }
    }

    /** The loaded [model], loading it (and unloading any other) if needed; null if it can't be tested. */
    private fun load(model: ModelInfo): Loaded? {
        loaded?.takeIf { it.filename == model.filename }?.let { return it }
        loaded?.release()
        loaded = null
        val app = getApplication<Application>()
        val llamaCpp = (app as? App)?.llamaCpp ?: error("The inference engine isn't available.")
        val handle = storage.openModelFile(model.filename) ?: error("Can't open ${model.filename}.")
        _status.value = "Loading ${model.name}…"
        val llama = try {
            llamaCpp.loadModel(
                handle.pfd,
                object : LlamaProgressCallback {
                    override fun onProgress(progress: Float) {
                        _status.value = "Loading ${model.name}… ${(progress * 100).toInt()}%"
                    }
                },
                disableRepack = !prefs.repackEnabled,
                chatTemplateOverride = ChatTemplateOverrides.forModel(app, model.filename),
            )
        } catch (t: Throwable) {
            handle.close()
            throw t
        }
        if (!llama.supportsToolCalling()) {
            try { llama.unloadModel() } catch (_: Throwable) {}
            handle.close()
            _status.value = "${model.name} doesn't support tool calling, so there is nothing to test."
            return null
        }
        // The real tools and descriptions, all switched on, as a chat would send them.
        val registry = ToolRegistry.createDefault(app)
            .apply { getAllTools().forEach { setToolEnabled(it.name, true) } }
        return Loaded(
            model.filename, llama.getModelSize(), handle, llama, TestTools(registry),
            registry.toOpenAIToolsJson(), llama.supportsThinking(),
        )
            .also { loaded = it }
    }

    /**
     * One case on the loaded model's session, kept between cases and rewound
     * to before its first question. The rewind can't always keep the tool
     * list in memory; the preamble cache then reloads it from disk instead of
     * processing its 2,500 tokens again, as it does for a chat.
     */
    private suspend fun runOn(l: Loaded, case: ToolTestCase): ToolTestResult {
        val reusable = l.session?.takeIf { !l.used || it.rewindToTurn(0, "") >= 0 }
        val session = reusable ?: run {
            l.session?.destroy()
            l.session = newSession(l)
            l.session
        } ?: return ToolTestResult(case, ToolTestState.Failed, "Couldn't start a session.")
        l.used = true
        return runCase(session, case, l.tools, l.thinking)
    }

    private fun update(i: Int, r: ToolTestResult) {
        _results.value = _results.value.toMutableList().also { it[i] = r }
    }

    private fun newSession(l: Loaded): LlamaGenerationSession? =
        // The chat's default context (the full tool list alone is about 2,500
        // tokens, so a smaller one can't even hold the prompt), a short thinking
        // budget so thinking models still fit both rounds in it, greedy decoding
        // and a fixed seed, so a run is repeatable.
        l.llama.createSession(CONTEXT_SIZE, 0f, 1f, 1f, 1, 0f, 42, THINKING_BUDGET, "")?.also {
            it.setTools(l.toolsJson)
            // After setTools (the cache key covers the tool list), before the first question.
            preambleCache.apply(it, l.filename, l.modelSize, "", l.toolsJson)
        }

    private suspend fun runCase(
        session: LlamaGenerationSession,
        case: ToolTestCase,
        tools: TestTools,
        thinking: Boolean,
    ): ToolTestResult {
        session.addMessage(case.prompt, false)
        var answer = ""
        val ctx = currentCoroutineContext()
        val r = ToolCallLoop(session, tools, maxToolRounds = 3).run(
            object : LlamaGenerationCallback {
                override fun onFullResponse(response: String) { answer = response }
            },
            supportsThinking = thinking,
            enableThinking = false,
            isActive = { ctx.isActive },
        )
        return judge(case, r.rc, r.records, stripThinkTags(answer).trim())
    }

    private fun judge(case: ToolTestCase, rc: Int, calls: List<ToolCallRecord>, answer: String): ToolTestResult {
        val said = if (answer.isEmpty()) "(empty answer)" else answer.take(300)
        // 0 is a normal stop; anything else means the engine gave up (for
        // example the prompt didn't fit), which is not the model's fault.
        if (rc != 0 && calls.isEmpty() && answer.isEmpty()) {
            return ToolTestResult(case, ToolTestState.Failed, "The engine stopped with error $rc before the model answered.")
        }
        if (calls.isEmpty()) return ToolTestResult(case, ToolTestState.Failed, "Called no tool. Answer: $said")
        val call = calls.firstOrNull { it.name == case.tool }
            ?: return ToolTestResult(case, ToolTestState.Failed, "Called ${calls.joinToString { it.name }} instead.")
        val args = try { JSONObject(call.arguments) } catch (_: Exception) { JSONObject() }
        if (!case.argsOk(args)) return ToolTestResult(case, ToolTestState.Failed, "Wrong arguments: ${call.arguments}")
        val result = try { JSONObject(call.result) } catch (_: Exception) { JSONObject() }
        val tool = (if (case.simulated != null) "Simulated result: " else "Tool returned: ") + call.result.take(200)
        val expected = case.expect(result)
        fun norm(s: String) = s.lowercase().replace(Regex("[,\\s\\u00A0\\u202F]"), "")
        if (expected != null && norm(expected) !in norm(answer)) {
            return ToolTestResult(case, ToolTestState.Failed, "Answer doesn't match the tool (expected \"$expected\"). $tool\nAnswer: $said")
        }
        return ToolTestResult(case, ToolTestState.Passed, "Called with ${call.arguments}. $tool\nAnswer: $said")
    }

    /**
     * Runs the real tools, except the ones with side effects, which get their
     * case's simulated result instead.
     */
    private class TestTools(private val real: ToolRegistry) : ToolCallLoop.ToolExecutor {
        private val simulated = TOOL_TEST_CASES.mapNotNull { c -> c.simulated?.let { c.tool to it } }.toMap()
        override fun executeToolCalls(toolCallsJson: String): String {
            val calls = JSONArray(toolCallsJson)
            val out = JSONArray()
            for (i in 0 until calls.length()) {
                val call = calls.getJSONObject(i)
                val name = call.getString("name")
                val content = simulated[name] ?: try {
                    JSONArray(real.executeToolCalls(JSONArray().put(call).toString())).getJSONObject(0).getString("content")
                } catch (e: Exception) {
                    JSONObject().put("error", e.message ?: "The $name tool failed.").toString()
                }
                out.put(JSONObject().put("id", call.getString("id")).put("name", name).put("content", content))
            }
            return out.toString()
        }
        override fun cancelInFlight() = real.cancelInFlight()
    }

    private companion object {
        /** Same as a chat's default (GenerationParams.contextSize). */
        const val CONTEXT_SIZE = 4096

        /** Per round; a thinking model thinks before the call and again after it. */
        const val THINKING_BUDGET = 256
    }

}

@Composable
fun ToolTestDialog(viewModel: ToolTestViewModel, onDismiss: () -> Unit) {
    val models by viewModel.models.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val running by viewModel.running.collectAsStateWithLifecycle()
    var chosen by remember { mutableStateOf<ModelInfo?>(null) }
    var pickerOpen by remember { mutableStateOf(false) }
    // Location is tested with the real tool, so ask for its permission first;
    // the run starts after the answer either way.
    var afterAsking by remember { mutableStateOf<(() -> Unit)?>(null) }
    val (locationGranted, askLocation) = rememberLocationPermission {
        afterAsking?.invoke()
        afterAsking = null
    }
    fun start(only: Int? = null) {
        val model = chosen ?: return
        val run = { viewModel.run(model, only) }
        val needsLocation = only == null || TOOL_TEST_CASES[only].tool == "location"
        if (needsLocation && !locationGranted && askLocation != null) {
            afterAsking = run
            askLocation()
        } else {
            run()
        }
    }

    Dialog(
        onDismissRequest = { viewModel.release(); onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Test tools") },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.release(); onDismiss() }) {
                            Icon(Icons.Filled.Close, contentDescription = "Close")
                        }
                    }
                )
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                Text(
                    "Sends one prompt per tool to the model and checks that it calls the right tool " +
                        "with sensible arguments, then uses what the tool returned in its answer. " +
                        "Tools really run (web tools need internet, Location needs its permission), " +
                        "except Set alarm, Add calendar event and Memory, which are simulated so " +
                        "nothing is set or saved. The model loads on its own, alongside any model " +
                        "loaded in chat, so on a phone with little memory unload the chat model first. " +
                        "It stays loaded until you close this screen, so testing one tool after " +
                        "another is quick.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        OutlinedButton(
                            onClick = { pickerOpen = true },
                            enabled = !running,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                chosen?.name ?: if (models.isEmpty()) "No downloaded models" else "Choose a model",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
                        }
                        DropdownMenu(
                            expanded = pickerOpen,
                            onDismissRequest = { pickerOpen = false },
                            shape = PopupMenuShape,
                            border = menuBorder,
                        ) {
                            models.forEach { m ->
                                DropdownMenuItem(text = { Text(m.name) }, onClick = {
                                    // Free the previous model now rather than at the next run.
                                    if (chosen != null && chosen != m) viewModel.release()
                                    chosen = m
                                    pickerOpen = false
                                })
                            }
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    if (running) {
                        Button(onClick = viewModel::stop) { Text("Stop") }
                    } else {
                        Button(onClick = { start() }, enabled = chosen != null) { Text("Run all") }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(status, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                LazyColumn(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    itemsIndexed(results, key = { _, r -> r.case.tool }) { i, r ->
                        ToolTestRow(r, canRun = chosen != null && !running) { start(only = i) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolTestRow(r: ToolTestResult, canRun: Boolean, onRun: () -> Unit) {
    val (mark, color) = when (r.state) {
        ToolTestState.Waiting -> "·" to MaterialTheme.colorScheme.onSurfaceVariant
        ToolTestState.Running -> "…" to MaterialTheme.colorScheme.onSurfaceVariant
        ToolTestState.Passed -> "✓" to MaterialTheme.colorScheme.onSurface
        ToolTestState.Failed -> "✗" to MaterialTheme.colorScheme.error
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(mark, color = color, fontWeight = FontWeight.Bold, modifier = Modifier.width(24.dp))
            Text(r.case.tool, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onRun, enabled = canRun) { Text("Run") }
        }
        Text(
            "“${r.case.prompt}”",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 24.dp),
        )
        if (r.detail.isNotEmpty()) {
            Text(
                r.detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (r.state == ToolTestState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 24.dp, top = 2.dp),
            )
        }
    }
    HorizontalDivider()
}
