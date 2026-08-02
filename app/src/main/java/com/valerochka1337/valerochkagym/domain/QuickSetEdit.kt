package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity
import kotlin.math.roundToInt

/**
 * Разобранная быстрая правка подхода из уведомления. Заполненные поля перезаписывают значения
 * подхода, null-поля оставляют как есть — ввод «8» правит только повторы, не обнуляя вес.
 */
data class QuickSetEdit(
    val weightKg: Double? = null,
    val reps: Int? = null,
    val durationSec: Int? = null,
    val speedKmh: Double? = null,
    val inclinePct: Double? = null,
) {
    fun applyTo(set: WorkoutSetEntity): WorkoutSetEntity = set.copy(
        weightKg = weightKg ?: set.weightKg,
        reps = reps ?: set.reps,
        durationSec = durationSec ?: set.durationSec,
        speedKmh = speedKmh ?: set.speedKmh,
        inclinePct = inclinePct ?: set.inclinePct,
    )
}

/**
 * Разбирает строку из инлайн-поля уведомления («Изменить» во время отдыха) по типу упражнения.
 * null — не удалось понять ввод; вызывающий в этом случае просто ничего не меняет.
 *
 * Формат намеренно свободный, потому что печатают одной рукой между подходами: числа вытаскиваются
 * регуляркой, а всё между ними считается разделителем. Поэтому «60x8», «60х8» (кириллическая «х» —
 * раскладка русская), «60*8», «60 8», «60 кг × 8» и «60,5x8» разбираются одинаково.
 *
 * | Тип | Ввод | Смысл |
 * |---|---|---|
 * | STRENGTH | `60x8` | вес × повторы |
 * | STRENGTH | `8` | только повторы |
 * | STRENGTH | `62.5` или `60x` | только вес (дробное или «висящий» разделитель) |
 * | TIMED | `45` | секунды |
 * | TIMED | `1:30` | минуты:секунды |
 * | CARDIO | `10x5` | скорость × наклон |
 * | CARDIO | `10` | только скорость |
 */
fun parseQuickSetEdit(raw: String, type: ExerciseType): QuickSetEdit? {
    val normalized = raw.trim().lowercase().replace(',', '.')
    val numbers = NUMBER.findAll(normalized).map { it.value.toDouble() }.toList()
    if (numbers.isEmpty()) return null

    return when (type) {
        ExerciseType.STRENGTH -> when {
            numbers.size >= 2 -> QuickSetEdit(
                weightKg = numbers[0],
                reps = numbers[1].roundToInt(),
            )
            // Одно число: повторы — только если это целое без «висящего» разделителя. Иначе
            // «62.5» и «60x» означают вес, а не 62 или 60 повторов.
            numbers[0] == numbers[0].roundToInt().toDouble() && !normalized.hasTrailingSeparator() ->
                QuickSetEdit(reps = numbers[0].roundToInt())

            else -> QuickSetEdit(weightKg = numbers[0])
        }

        ExerciseType.TIMED -> when {
            numbers.size >= 2 && ':' in normalized ->
                QuickSetEdit(durationSec = numbers[0].roundToInt() * 60 + numbers[1].roundToInt())

            else -> QuickSetEdit(durationSec = numbers[0].roundToInt())
        }

        ExerciseType.CARDIO -> when {
            numbers.size >= 2 -> QuickSetEdit(speedKmh = numbers[0], inclinePct = numbers[1])
            else -> QuickSetEdit(speedKmh = numbers[0])
        }
    }
}

/** true, если после последнего числа осталось что-то ещё («60x», «60 кг»). */
private fun String.hasTrailingSeparator(): Boolean {
    val last = NUMBER.findAll(this).lastOrNull() ?: return false
    return last.range.last < lastIndex
}

private val NUMBER = Regex("""\d+(?:\.\d+)?""")
