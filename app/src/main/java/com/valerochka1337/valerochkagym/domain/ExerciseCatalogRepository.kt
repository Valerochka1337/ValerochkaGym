package com.valerochka1337.valerochkagym.domain

import kotlinx.coroutines.flow.Flow

interface ExerciseCatalogRepository {
  fun observeCatalog(gymIds: Set<String>): Flow<ExerciseCatalogRepositoryState>
}

data class ExerciseCatalogRepositoryState(
    val snapshot: ExerciseCatalogSnapshot,
    val gymNames: List<String>,
)
