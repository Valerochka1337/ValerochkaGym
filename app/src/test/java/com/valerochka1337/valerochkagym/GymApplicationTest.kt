package com.valerochka1337.valerochkagym

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class GymApplicationTest {
    @Test
    fun `application keeps Hilt worker factory and enqueues weekly recovery at startup`() {
        val source = Files.readString(Path.of("src/main/java/com/valerochka1337/valerochkagym/GymApplication.kt"))
        assertTrue(source.contains("setWorkerFactory(workerFactory)"))
        assertTrue(source.contains("weeklyScheduleRecoveryScheduler.get().enqueue()"))
    }
}
