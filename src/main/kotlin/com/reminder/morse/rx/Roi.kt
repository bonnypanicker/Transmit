package com.reminder.morse.rx

data class Roi(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left + 1
    val height: Int get() = bottom - top + 1
}
