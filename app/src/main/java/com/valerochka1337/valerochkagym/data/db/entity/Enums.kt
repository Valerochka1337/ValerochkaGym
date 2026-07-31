package com.valerochka1337.valerochkagym.data.db.entity

enum class MuscleGroup {
    CHEST,
    BACK,
    LEGS,
    SHOULDERS,
    ARMS,
    CORE,
    CARDIO,
    FULL_BODY,
}

enum class ExerciseType {
    STRENGTH,
    TIMED,
    CARDIO,
}

enum class UploadStatus {
    PENDING,
    UPLOADED,
    FAILED,
}
