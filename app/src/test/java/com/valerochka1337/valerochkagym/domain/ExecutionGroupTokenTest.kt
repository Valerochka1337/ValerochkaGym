package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.ui.navigation.GymRoutes
import org.junit.Assert.assertEquals
import org.junit.Test

class ExecutionGroupTokenTest {
    @Test
    fun `routes always encode named and no variant groups explicitly`() {
        val named = "11111111-1111-1111-1111-111111111111"
        assertEquals("exercise_detail/7/none", GymRoutes.exerciseDetail(7))
        assertEquals("exercise_detail/7/$named", GymRoutes.exerciseDetail(7, named))
        assertEquals(null, ExecutionGroupToken.decode("none"))
        assertEquals(named, ExecutionGroupToken.decode(named))
    }
}
