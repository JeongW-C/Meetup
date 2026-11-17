package com.example.webtoonviewer

import org.junit.Assert.assertEquals
import org.junit.Test

class NaturalOrderTest {
    @Test
    fun numbersSortByValue() {
        val names = listOf("10화.jpg", "2화.jpg", "1화.jpg", "001_b.png", "1_a.png", "Ep 3.webp", "ep 20.webp")
        assertEquals(
            listOf("1_a.png", "001_b.png", "1화.jpg", "2화.jpg", "10화.jpg", "Ep 3.webp", "ep 20.webp"),
            names.sortedWith(NaturalOrder),
        )
    }

    @Test
    fun hugeNumbersDoNotOverflow() {
        val names = listOf("img_99999999999999999999.png", "img_100000000000000000000.png")
        assertEquals(names, names.sortedWith(NaturalOrder))
    }
}
