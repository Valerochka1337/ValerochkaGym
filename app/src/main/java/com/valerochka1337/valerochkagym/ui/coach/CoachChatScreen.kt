package com.valerochka1337.valerochkagym.ui.coach

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valerochka1337.valerochkagym.service.WorkoutSessionService

@Composable
fun CoachChatScreen(
    onBack: () -> Unit,
    viewModel: CoachChatViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  // A restored active workout has durable Room state but no process-local conversation consumer.
  // Starting the foreground owner is safe only while the observed workout is still active.
  LaunchedEffect(state.readOnly) { if (!state.readOnly) WorkoutSessionService.start(context) }
  LaunchedEffect(state.messages) { viewModel.markAssistantMessagesRead() }
  CoachChatContent(
      state = state,
      onBack = onBack,
      onDraftChange = viewModel::changeDraft,
      onSend = viewModel::send,
      onConfirm = viewModel::confirm,
      onCancel = viewModel::cancel,
      onUndo = viewModel::undo,
      onDisableInitiative = viewModel::disableInitiative,
  )
}
