package com.example.codeassistant

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.codeassistant.data.ApiMessage
import com.example.codeassistant.data.ClaudeClient
import com.example.codeassistant.data.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Role { USER, ASSISTANT }

data class ChatMessage(val id: Long, val role: Role, val text: String)

data class ChatState(
    val messages: List<ChatMessage> = emptyList(),
    val isStreaming: Boolean = false,
    val error: String? = null,
    val apiKey: String = "",
    val model: String = ClaudeClient.DEFAULT_MODEL,
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private val client = ClaudeClient()

    private val _state = MutableStateFlow(ChatState(apiKey = prefs.apiKey, model = prefs.model))
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private var job: Job? = null
    private var nextId = 0L

    fun saveSettings(apiKey: String, model: String) {
        prefs.apiKey = apiKey
        prefs.model = model
        _state.update { it.copy(apiKey = prefs.apiKey, model = prefs.model, error = null) }
    }

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || _state.value.isStreaming) return
        if (_state.value.apiKey.isBlank()) {
            _state.update { it.copy(error = "Сначала укажите API-ключ в настройках (⚙).") }
            return
        }

        val userMsg = ChatMessage(nextId++, Role.USER, prompt)
        val replyId = nextId++
        _state.update {
            it.copy(
                messages = it.messages + userMsg + ChatMessage(replyId, Role.ASSISTANT, ""),
                isStreaming = true,
                error = null,
            )
        }

        val history = _state.value.messages
            .filter { it.id != replyId }
            .map { ApiMessage(if (it.role == Role.USER) "user" else "assistant", it.text) }

        job = viewModelScope.launch {
            try {
                client.stream(_state.value.apiKey, _state.value.model, history).collect { chunk ->
                    appendToMessage(replyId, chunk)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Неизвестная ошибка") }
            } finally {
                _state.update { s ->
                    // Drop an empty assistant bubble if nothing arrived.
                    s.copy(
                        isStreaming = false,
                        messages = s.messages.filterNot { it.id == replyId && it.text.isEmpty() },
                    )
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    fun clear() {
        job?.cancel()
        _state.update { it.copy(messages = emptyList(), error = null, isStreaming = false) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    private fun appendToMessage(id: Long, chunk: String) {
        _state.update { s ->
            s.copy(messages = s.messages.map { if (it.id == id) it.copy(text = it.text + chunk) else it })
        }
    }
}
