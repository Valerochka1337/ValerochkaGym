package com.valerochka1337.valerochkagym.ui.trainingproposal

import androidx.compose.runtime.*
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valerochka1337.valerochkagym.ui.components.GlowBackground

@Composable
fun TrainingProposalInboxScreen(
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: TrainingProposalViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  LaunchedEffect(viewModel) { viewModel.refresh() }
  GlowBackground {
    TrainingProposalInboxContent(
        state.items,
        state.loading,
        state.error,
        state.nextCursor != null,
        { viewModel.refresh() },
        { viewModel.refresh(true) },
        onOpen,
        onBack,
    )
  }
}

@Composable
fun TrainingProposalDetailScreen(
    id: String,
    onBack: () -> Unit,
    viewModel: TrainingProposalViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  LaunchedEffect(viewModel, id) { viewModel.open(id) }
  GlowBackground {
    TrainingProposalDetailContent(
        state.editor?.proposal,
        state.editor?.draft,
        state.saving,
        state.editor?.applied == true,
        state.error,
        state.exerciseChoices,
        state.gymChoices,
        viewModel::updateDraft,
        viewModel::approve,
        viewModel::reject,
        onBack,
        { viewModel.open(id) },
    )
  }
}
