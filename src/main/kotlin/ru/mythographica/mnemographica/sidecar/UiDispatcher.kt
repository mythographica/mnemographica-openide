package ru.mythographica.mnemographica.sidecar

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

/**
 * EDT under any modality, for UI-only updates driven by the sidecar (trees, tabs, status bar, consoles, actions):
 * they must not wait until an unrelated modal dialog (e.g. a startup license dialog) closes.
 * Model changes and dialogs keep using plain [Dispatchers.EDT].
 */
val UiDispatcher: CoroutineContext = Dispatchers.EDT + ModalityState.any().asContextElement()
