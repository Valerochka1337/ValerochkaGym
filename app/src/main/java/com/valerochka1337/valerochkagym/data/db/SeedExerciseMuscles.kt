package com.valerochka1337.valerochkagym.data.db

import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.data.db.entity.MuscleLoad

/**
 * Карты вовлечения мышц для встроенного каталога [seedExercises]: ключ — название упражнения
 * в нижнем регистре, значение — доли участия мышц (см. [MuscleLoad]).
 *
 * Числа собраны по ЭМГ-исследованиям и анатомическим таблицам (Coratella 2019/2020/2023,
 * Saeterbakken 2013, Youdas 2010, Martín-Fuentes 2020, ExRx, обзоры Schoenfeld) и приведены к
 * общей межупражненческой шкале: 100 — стимул целевой мышцы в тяжёлом силовом подходе,
 * 60..85 — сильная прямая нагрузка, 25..55 — умеренная или косвенная, 5..20 — в основном
 * стабилизация или выносливость. Шкала не нормализуется внутри упражнения: его максимум может
 * быть ниже 100. Поэтому, например, квадрицепс в беге не приравнивается к квадрицепсу в приседании.
 * Значения кратны 5: точность выше этой иллюзорна, а разброс между исследованиями заметно больше.
 *
 * Однотипные по механике движения намеренно имеют близкие числа (жим штанги и гантелей лёжа,
 * тяги в наклоне), а расхождения отражают измеренную разницу: например у жима гантелей трицепс
 * ниже, чем у штанги, а средняя дельта в сидячем жиме ниже, чем в стоячем.
 *
 * Ключ ищется по `name.trim().lowercase()` — так карта находится и для упражнения, приехавшего
 * импортом из Google Sheets с другим регистром. Незнакомое имя получает фоллбэк по группе
 * ([defaultMuscleLoads]).
 */
val seedExerciseMuscles: Map<String, List<MuscleLoad>> = mapOf(

    // CHEST
    "жим штанги лёжа" to listOf(
        MuscleLoad(Muscle.CHEST, 100),
        MuscleLoad(Muscle.TRICEPS, 65),
        MuscleLoad(Muscle.FRONT_DELTS, 60),
        MuscleLoad(Muscle.SIDE_DELTS, 15),
        MuscleLoad(Muscle.ABS, 10),
    ),
    "жим гантелей лёжа" to listOf(
        MuscleLoad(Muscle.CHEST, 100),
        MuscleLoad(Muscle.FRONT_DELTS, 60),
        MuscleLoad(Muscle.TRICEPS, 55),
        MuscleLoad(Muscle.SIDE_DELTS, 15),
        MuscleLoad(Muscle.ABS, 10),
    ),
    "жим штанги на наклонной скамье" to listOf(
        MuscleLoad(Muscle.CHEST, 100),
        MuscleLoad(Muscle.FRONT_DELTS, 75),
        MuscleLoad(Muscle.TRICEPS, 60),
        MuscleLoad(Muscle.SIDE_DELTS, 20),
        MuscleLoad(Muscle.ABS, 10),
    ),
    "жим гантелей на наклонной скамье" to listOf(
        MuscleLoad(Muscle.CHEST, 100),
        MuscleLoad(Muscle.FRONT_DELTS, 80),
        MuscleLoad(Muscle.TRICEPS, 55),
        MuscleLoad(Muscle.SIDE_DELTS, 20),
        MuscleLoad(Muscle.ABS, 10),
    ),
    "разводка гантелей лёжа" to listOf(
        MuscleLoad(Muscle.CHEST, 100),
        MuscleLoad(Muscle.FRONT_DELTS, 40),
        MuscleLoad(Muscle.BICEPS, 20),
        MuscleLoad(Muscle.ABS, 10),
    ),
    "сведение гантелей на наклонной скамье" to listOf(
        MuscleLoad(Muscle.CHEST, 100),
        MuscleLoad(Muscle.FRONT_DELTS, 50),
        MuscleLoad(Muscle.BICEPS, 20),
        MuscleLoad(Muscle.ABS, 10),
    ),
    "отжимания от пола" to listOf(
        MuscleLoad(Muscle.CHEST, 100),
        MuscleLoad(Muscle.TRICEPS, 70),
        MuscleLoad(Muscle.FRONT_DELTS, 55),
        MuscleLoad(Muscle.ABS, 30),
        MuscleLoad(Muscle.OBLIQUES, 20),
        MuscleLoad(Muscle.LOWER_BACK, 15),
    ),
    "сведение рук в кроссовере" to listOf(
        MuscleLoad(Muscle.CHEST, 100),
        MuscleLoad(Muscle.FRONT_DELTS, 45),
        MuscleLoad(Muscle.BICEPS, 15),
        MuscleLoad(Muscle.ABS, 15),
        MuscleLoad(Muscle.OBLIQUES, 10),
    ),
    "жим в тренажёре хаммер" to listOf(
        MuscleLoad(Muscle.CHEST, 100),
        MuscleLoad(Muscle.TRICEPS, 60),
        MuscleLoad(Muscle.FRONT_DELTS, 55),
        MuscleLoad(Muscle.SIDE_DELTS, 10),
    ),
    "пуловер с гантелью" to listOf(
        MuscleLoad(Muscle.CHEST, 100),
        MuscleLoad(Muscle.LATS, 80),
        MuscleLoad(Muscle.TRICEPS, 35),
        MuscleLoad(Muscle.ABS, 25),
        MuscleLoad(Muscle.UPPER_BACK, 25),
        MuscleLoad(Muscle.FOREARMS, 15),
    ),

    // BACK
    "подтягивания" to listOf(
        MuscleLoad(Muscle.LATS, 100),
        MuscleLoad(Muscle.UPPER_BACK, 70),
        MuscleLoad(Muscle.BICEPS, 70),
        MuscleLoad(Muscle.TRAPS, 40),
        MuscleLoad(Muscle.REAR_DELTS, 40),
        MuscleLoad(Muscle.FOREARMS, 30),
        MuscleLoad(Muscle.CHEST, 25),
    ),
    "подтягивания обратным хватом" to listOf(
        MuscleLoad(Muscle.LATS, 100),
        MuscleLoad(Muscle.BICEPS, 85),
        MuscleLoad(Muscle.UPPER_BACK, 60),
        MuscleLoad(Muscle.REAR_DELTS, 40),
        MuscleLoad(Muscle.CHEST, 35),
        MuscleLoad(Muscle.TRAPS, 30),
        MuscleLoad(Muscle.FOREARMS, 30),
    ),
    "тяга штанги в наклоне" to listOf(
        MuscleLoad(Muscle.LATS, 100),
        MuscleLoad(Muscle.UPPER_BACK, 85),
        MuscleLoad(Muscle.LOWER_BACK, 70),
        MuscleLoad(Muscle.BICEPS, 60),
        MuscleLoad(Muscle.REAR_DELTS, 55),
        MuscleLoad(Muscle.FOREARMS, 30),
        MuscleLoad(Muscle.ABS, 20),
    ),
    "тяга верхнего блока" to listOf(
        MuscleLoad(Muscle.LATS, 100),
        MuscleLoad(Muscle.BICEPS, 65),
        MuscleLoad(Muscle.UPPER_BACK, 60),
        MuscleLoad(Muscle.REAR_DELTS, 40),
        MuscleLoad(Muscle.TRAPS, 35),
        MuscleLoad(Muscle.FOREARMS, 25),
        MuscleLoad(Muscle.ABS, 15),
    ),
    "тяга нижнего блока" to listOf(
        MuscleLoad(Muscle.LATS, 100),
        MuscleLoad(Muscle.UPPER_BACK, 85),
        MuscleLoad(Muscle.BICEPS, 60),
        MuscleLoad(Muscle.REAR_DELTS, 60),
        MuscleLoad(Muscle.TRAPS, 40),
        MuscleLoad(Muscle.LOWER_BACK, 30),
        MuscleLoad(Muscle.FOREARMS, 25),
    ),
    "тяга гантели одной рукой" to listOf(
        MuscleLoad(Muscle.LATS, 100),
        MuscleLoad(Muscle.UPPER_BACK, 80),
        MuscleLoad(Muscle.BICEPS, 55),
        MuscleLoad(Muscle.REAR_DELTS, 50),
        MuscleLoad(Muscle.LOWER_BACK, 40),
        MuscleLoad(Muscle.OBLIQUES, 30),
        MuscleLoad(Muscle.FOREARMS, 30),
    ),
    "становая тяга" to listOf(
        MuscleLoad(Muscle.GLUTES, 100),
        MuscleLoad(Muscle.LOWER_BACK, 85),
        MuscleLoad(Muscle.QUADS, 85),
        MuscleLoad(Muscle.HAMSTRINGS, 65),
        MuscleLoad(Muscle.TRAPS, 40),
        MuscleLoad(Muscle.FOREARMS, 35),
        MuscleLoad(Muscle.ABS, 25),
    ),
    "тяга т-грифа" to listOf(
        MuscleLoad(Muscle.LATS, 100),
        MuscleLoad(Muscle.UPPER_BACK, 85),
        MuscleLoad(Muscle.LOWER_BACK, 65),
        MuscleLoad(Muscle.BICEPS, 60),
        MuscleLoad(Muscle.REAR_DELTS, 50),
        MuscleLoad(Muscle.FOREARMS, 30),
        MuscleLoad(Muscle.ABS, 20),
    ),
    "гиперэкстензия" to listOf(
        MuscleLoad(Muscle.LOWER_BACK, 100),
        MuscleLoad(Muscle.GLUTES, 75),
        MuscleLoad(Muscle.HAMSTRINGS, 70),
        MuscleLoad(Muscle.ADDUCTORS, 20),
    ),
    "шраги со штангой" to listOf(
        MuscleLoad(Muscle.TRAPS, 100),
        MuscleLoad(Muscle.UPPER_BACK, 40),
        MuscleLoad(Muscle.FOREARMS, 35),
        MuscleLoad(Muscle.LOWER_BACK, 20),
        MuscleLoad(Muscle.ABS, 10),
    ),

    // LEGS
    "приседания со штангой" to listOf(
        MuscleLoad(Muscle.QUADS, 100),
        MuscleLoad(Muscle.GLUTES, 85),
        MuscleLoad(Muscle.ADDUCTORS, 60),
        MuscleLoad(Muscle.LOWER_BACK, 40),
        MuscleLoad(Muscle.HAMSTRINGS, 30),
        MuscleLoad(Muscle.ABS, 25),
        MuscleLoad(Muscle.CALVES, 15),
    ),
    "приседания в смите" to listOf(
        MuscleLoad(Muscle.QUADS, 100),
        MuscleLoad(Muscle.GLUTES, 80),
        MuscleLoad(Muscle.ADDUCTORS, 50),
        MuscleLoad(Muscle.HAMSTRINGS, 25),
        MuscleLoad(Muscle.LOWER_BACK, 25),
        MuscleLoad(Muscle.ABS, 15),
    ),
    "гакк-приседания" to listOf(
        MuscleLoad(Muscle.QUADS, 100),
        MuscleLoad(Muscle.GLUTES, 65),
        MuscleLoad(Muscle.ADDUCTORS, 40),
        MuscleLoad(Muscle.HAMSTRINGS, 20),
        MuscleLoad(Muscle.LOWER_BACK, 15),
        MuscleLoad(Muscle.ABS, 10),
    ),
    "жим ногами" to listOf(
        MuscleLoad(Muscle.QUADS, 100),
        MuscleLoad(Muscle.GLUTES, 70),
        MuscleLoad(Muscle.ADDUCTORS, 40),
        MuscleLoad(Muscle.HAMSTRINGS, 20),
        MuscleLoad(Muscle.ABS, 10),
    ),
    "выпады с гантелями" to listOf(
        MuscleLoad(Muscle.QUADS, 100),
        MuscleLoad(Muscle.GLUTES, 85),
        MuscleLoad(Muscle.ADDUCTORS, 55),
        MuscleLoad(Muscle.HAMSTRINGS, 40),
        MuscleLoad(Muscle.ABS, 25),
        MuscleLoad(Muscle.LOWER_BACK, 20),
        MuscleLoad(Muscle.FOREARMS, 20),
    ),
    "болгарские выпады" to listOf(
        MuscleLoad(Muscle.QUADS, 100),
        MuscleLoad(Muscle.GLUTES, 85),
        MuscleLoad(Muscle.ADDUCTORS, 65),
        MuscleLoad(Muscle.HAMSTRINGS, 40),
        MuscleLoad(Muscle.LOWER_BACK, 25),
        MuscleLoad(Muscle.ABS, 25),
        MuscleLoad(Muscle.FOREARMS, 20),
    ),
    "румынская тяга" to listOf(
        MuscleLoad(Muscle.HAMSTRINGS, 100),
        MuscleLoad(Muscle.GLUTES, 85),
        MuscleLoad(Muscle.LOWER_BACK, 75),
        MuscleLoad(Muscle.ADDUCTORS, 40),
        MuscleLoad(Muscle.FOREARMS, 30),
        MuscleLoad(Muscle.UPPER_BACK, 25),
        MuscleLoad(Muscle.ABS, 20),
    ),
    "разгибание ног в тренажёре" to listOf(
        MuscleLoad(Muscle.QUADS, 100),
    ),
    "сгибание ног в тренажёре" to listOf(
        MuscleLoad(Muscle.HAMSTRINGS, 100),
        MuscleLoad(Muscle.CALVES, 30),
    ),
    "подъёмы на носки стоя" to listOf(
        MuscleLoad(Muscle.CALVES, 100),
        MuscleLoad(Muscle.QUADS, 10),
        MuscleLoad(Muscle.ABS, 10),
    ),
    "подъёмы на носки сидя" to listOf(
        MuscleLoad(Muscle.CALVES, 100),
    ),

    // SHOULDERS
    "жим штанги стоя" to listOf(
        MuscleLoad(Muscle.FRONT_DELTS, 100),
        MuscleLoad(Muscle.TRICEPS, 65),
        MuscleLoad(Muscle.SIDE_DELTS, 60),
        MuscleLoad(Muscle.TRAPS, 45),
        MuscleLoad(Muscle.ABS, 30),
        MuscleLoad(Muscle.LOWER_BACK, 30),
        MuscleLoad(Muscle.CHEST, 20),
    ),
    "жим гантелей сидя" to listOf(
        MuscleLoad(Muscle.FRONT_DELTS, 100),
        MuscleLoad(Muscle.SIDE_DELTS, 60),
        MuscleLoad(Muscle.TRICEPS, 50),
        MuscleLoad(Muscle.TRAPS, 40),
        MuscleLoad(Muscle.ABS, 15),
        MuscleLoad(Muscle.CHEST, 15),
    ),
    "жим арнольда" to listOf(
        MuscleLoad(Muscle.FRONT_DELTS, 100),
        MuscleLoad(Muscle.SIDE_DELTS, 70),
        MuscleLoad(Muscle.TRICEPS, 55),
        MuscleLoad(Muscle.TRAPS, 35),
        MuscleLoad(Muscle.REAR_DELTS, 20),
        MuscleLoad(Muscle.ABS, 15),
    ),
    "махи гантелями в стороны" to listOf(
        MuscleLoad(Muscle.SIDE_DELTS, 100),
        MuscleLoad(Muscle.FRONT_DELTS, 45),
        MuscleLoad(Muscle.TRAPS, 40),
        MuscleLoad(Muscle.REAR_DELTS, 20),
        MuscleLoad(Muscle.ABS, 10),
    ),
    "махи гантелями в наклоне" to listOf(
        MuscleLoad(Muscle.REAR_DELTS, 100),
        MuscleLoad(Muscle.UPPER_BACK, 70),
        MuscleLoad(Muscle.TRAPS, 45),
        MuscleLoad(Muscle.LOWER_BACK, 30),
        MuscleLoad(Muscle.SIDE_DELTS, 25),
        MuscleLoad(Muscle.FOREARMS, 15),
    ),
    "фронтальные махи гантелями" to listOf(
        MuscleLoad(Muscle.FRONT_DELTS, 100),
        MuscleLoad(Muscle.SIDE_DELTS, 40),
        MuscleLoad(Muscle.CHEST, 35),
        MuscleLoad(Muscle.TRAPS, 25),
        MuscleLoad(Muscle.ABS, 15),
    ),
    "тяга штанги к подбородку" to listOf(
        MuscleLoad(Muscle.SIDE_DELTS, 100),
        MuscleLoad(Muscle.TRAPS, 85),
        MuscleLoad(Muscle.FRONT_DELTS, 50),
        MuscleLoad(Muscle.UPPER_BACK, 45),
        MuscleLoad(Muscle.BICEPS, 30),
        MuscleLoad(Muscle.FOREARMS, 20),
        MuscleLoad(Muscle.ABS, 10),
    ),
    "разведение в тренажёре на заднюю дельту" to listOf(
        MuscleLoad(Muscle.REAR_DELTS, 100),
        MuscleLoad(Muscle.UPPER_BACK, 70),
        MuscleLoad(Muscle.TRAPS, 45),
        MuscleLoad(Muscle.SIDE_DELTS, 20),
    ),
    "жим гантелей стоя" to listOf(
        MuscleLoad(Muscle.FRONT_DELTS, 100),
        MuscleLoad(Muscle.SIDE_DELTS, 70),
        MuscleLoad(Muscle.TRICEPS, 50),
        MuscleLoad(Muscle.TRAPS, 40),
        MuscleLoad(Muscle.LOWER_BACK, 35),
        MuscleLoad(Muscle.ABS, 30),
        MuscleLoad(Muscle.CHEST, 15),
    ),

    // ARMS
    "подъём штанги на бицепс" to listOf(
        MuscleLoad(Muscle.BICEPS, 100),
        MuscleLoad(Muscle.FOREARMS, 55),
        MuscleLoad(Muscle.FRONT_DELTS, 25),
        MuscleLoad(Muscle.ABS, 15),
    ),
    "подъём штанги на бицепс обратным хватом" to listOf(
        MuscleLoad(Muscle.BICEPS, 100),
        MuscleLoad(Muscle.FOREARMS, 75),
        MuscleLoad(Muscle.FRONT_DELTS, 30),
        MuscleLoad(Muscle.ABS, 15),
    ),
    "подъём гантелей на бицепс сидя" to listOf(
        MuscleLoad(Muscle.BICEPS, 100),
        MuscleLoad(Muscle.FOREARMS, 50),
        MuscleLoad(Muscle.FRONT_DELTS, 20),
    ),
    "молотки с гантелями" to listOf(
        MuscleLoad(Muscle.BICEPS, 100),
        MuscleLoad(Muscle.FOREARMS, 65),
        MuscleLoad(Muscle.FRONT_DELTS, 20),
        MuscleLoad(Muscle.ABS, 15),
    ),
    "концентрированный подъём на бицепс" to listOf(
        MuscleLoad(Muscle.BICEPS, 100),
        MuscleLoad(Muscle.FOREARMS, 45),
    ),
    "французский жим лёжа" to listOf(
        MuscleLoad(Muscle.TRICEPS, 100),
        MuscleLoad(Muscle.FOREARMS, 15),
    ),
    "разгибание на блоке на трицепс" to listOf(
        MuscleLoad(Muscle.TRICEPS, 100),
        MuscleLoad(Muscle.FOREARMS, 20),
        MuscleLoad(Muscle.ABS, 15),
        MuscleLoad(Muscle.LATS, 15),
    ),
    "разгибание руки с гантелью из-за головы" to listOf(
        MuscleLoad(Muscle.TRICEPS, 100),
        MuscleLoad(Muscle.FRONT_DELTS, 20),
        MuscleLoad(Muscle.OBLIQUES, 20),
        MuscleLoad(Muscle.ABS, 15),
    ),
    "отжимания на брусьях" to listOf(
        MuscleLoad(Muscle.TRICEPS, 100),
        MuscleLoad(Muscle.CHEST, 100),
        MuscleLoad(Muscle.FRONT_DELTS, 60),
        MuscleLoad(Muscle.LATS, 25),
        MuscleLoad(Muscle.ABS, 20),
        MuscleLoad(Muscle.FOREARMS, 15),
    ),

    // CORE
    "скручивания" to listOf(
        MuscleLoad(Muscle.ABS, 90),
        MuscleLoad(Muscle.OBLIQUES, 25),
    ),
    "скручивания на блоке" to listOf(
        MuscleLoad(Muscle.ABS, 100),
        MuscleLoad(Muscle.OBLIQUES, 30),
        MuscleLoad(Muscle.LATS, 15),
    ),
    "подъём ног в висе" to listOf(
        MuscleLoad(Muscle.ABS, 80),
        MuscleLoad(Muscle.OBLIQUES, 45),
        MuscleLoad(Muscle.FOREARMS, 30),
        MuscleLoad(Muscle.QUADS, 20),
        MuscleLoad(Muscle.LATS, 20),
    ),
    "подъём ног лёжа" to listOf(
        MuscleLoad(Muscle.ABS, 75),
        MuscleLoad(Muscle.OBLIQUES, 30),
        MuscleLoad(Muscle.QUADS, 20),
        MuscleLoad(Muscle.ADDUCTORS, 10),
    ),
    "русские скручивания" to listOf(
        MuscleLoad(Muscle.OBLIQUES, 80),
        MuscleLoad(Muscle.ABS, 50),
        MuscleLoad(Muscle.LOWER_BACK, 20),
        MuscleLoad(Muscle.QUADS, 10),
    ),
    "велосипед" to listOf(
        MuscleLoad(Muscle.ABS, 70),
        MuscleLoad(Muscle.OBLIQUES, 65),
        MuscleLoad(Muscle.QUADS, 20),
    ),
    "складка" to listOf(
        MuscleLoad(Muscle.ABS, 75),
        MuscleLoad(Muscle.OBLIQUES, 35),
        MuscleLoad(Muscle.QUADS, 25),
        MuscleLoad(Muscle.ADDUCTORS, 10),
    ),
    "планка" to listOf(
        MuscleLoad(Muscle.ABS, 65),
        MuscleLoad(Muscle.OBLIQUES, 40),
        MuscleLoad(Muscle.GLUTES, 20),
        MuscleLoad(Muscle.FRONT_DELTS, 15),
        MuscleLoad(Muscle.QUADS, 15),
        MuscleLoad(Muscle.LOWER_BACK, 10),
    ),
    "боковая планка" to listOf(
        MuscleLoad(Muscle.OBLIQUES, 70),
        MuscleLoad(Muscle.ABS, 45),
        MuscleLoad(Muscle.GLUTES, 35),
        MuscleLoad(Muscle.LOWER_BACK, 20),
        MuscleLoad(Muscle.SIDE_DELTS, 15),
        MuscleLoad(Muscle.ADDUCTORS, 10),
    ),

    // CARDIO
    "беговая дорожка" to listOf(
        MuscleLoad(Muscle.QUADS, 20),
        MuscleLoad(Muscle.CALVES, 20),
        MuscleLoad(Muscle.GLUTES, 15),
        MuscleLoad(Muscle.HAMSTRINGS, 15),
        MuscleLoad(Muscle.LOWER_BACK, 5),
        MuscleLoad(Muscle.ABS, 5),
    ),
    "велотренажёр" to listOf(
        MuscleLoad(Muscle.QUADS, 25),
        MuscleLoad(Muscle.GLUTES, 15),
        MuscleLoad(Muscle.CALVES, 10),
        MuscleLoad(Muscle.HAMSTRINGS, 10),
        MuscleLoad(Muscle.ABS, 5),
    ),
    "эллиптический тренажёр" to listOf(
        MuscleLoad(Muscle.QUADS, 20),
        MuscleLoad(Muscle.GLUTES, 15),
        MuscleLoad(Muscle.HAMSTRINGS, 10),
        MuscleLoad(Muscle.CALVES, 10),
        MuscleLoad(Muscle.LOWER_BACK, 5),
        MuscleLoad(Muscle.ABS, 5),
    ),
    "гребной тренажёр" to listOf(
        MuscleLoad(Muscle.QUADS, 35),
        MuscleLoad(Muscle.LATS, 30),
        MuscleLoad(Muscle.UPPER_BACK, 30),
        MuscleLoad(Muscle.GLUTES, 25),
        MuscleLoad(Muscle.LOWER_BACK, 25),
        MuscleLoad(Muscle.HAMSTRINGS, 15),
        MuscleLoad(Muscle.BICEPS, 15),
    ),
    "степпер" to listOf(
        MuscleLoad(Muscle.QUADS, 30),
        MuscleLoad(Muscle.GLUTES, 25),
        MuscleLoad(Muscle.CALVES, 15),
        MuscleLoad(Muscle.HAMSTRINGS, 10),
        MuscleLoad(Muscle.LOWER_BACK, 5),
        MuscleLoad(Muscle.ABS, 5),
    ),

    // FULL_BODY
    "бёрпи" to listOf(
        MuscleLoad(Muscle.QUADS, 55),
        MuscleLoad(Muscle.GLUTES, 35),
        MuscleLoad(Muscle.CHEST, 30),
        MuscleLoad(Muscle.TRICEPS, 30),
        MuscleLoad(Muscle.FRONT_DELTS, 25),
        MuscleLoad(Muscle.CALVES, 20),
        MuscleLoad(Muscle.ABS, 20),
    ),
    "трастеры со штангой" to listOf(
        MuscleLoad(Muscle.QUADS, 100),
        MuscleLoad(Muscle.FRONT_DELTS, 80),
        MuscleLoad(Muscle.GLUTES, 70),
        MuscleLoad(Muscle.TRICEPS, 55),
        MuscleLoad(Muscle.LOWER_BACK, 35),
        MuscleLoad(Muscle.SIDE_DELTS, 30),
        MuscleLoad(Muscle.ABS, 30),
    ),
    "махи гирей" to listOf(
        MuscleLoad(Muscle.GLUTES, 85),
        MuscleLoad(Muscle.HAMSTRINGS, 70),
        MuscleLoad(Muscle.LOWER_BACK, 45),
        MuscleLoad(Muscle.FRONT_DELTS, 25),
        MuscleLoad(Muscle.ABS, 25),
        MuscleLoad(Muscle.FOREARMS, 25),
        MuscleLoad(Muscle.QUADS, 20),
    ),
    "турецкий подъём" to listOf(
        MuscleLoad(Muscle.ABS, 60),
        MuscleLoad(Muscle.OBLIQUES, 55),
        MuscleLoad(Muscle.FRONT_DELTS, 55),
        MuscleLoad(Muscle.GLUTES, 40),
        MuscleLoad(Muscle.QUADS, 35),
        MuscleLoad(Muscle.TRICEPS, 30),
        MuscleLoad(Muscle.TRAPS, 20),
    ),
    "прыжки на тумбу" to listOf(
        MuscleLoad(Muscle.QUADS, 55),
        MuscleLoad(Muscle.GLUTES, 50),
        MuscleLoad(Muscle.CALVES, 40),
        MuscleLoad(Muscle.HAMSTRINGS, 30),
        MuscleLoad(Muscle.ABS, 10),
        MuscleLoad(Muscle.LOWER_BACK, 10),
    ),
)
