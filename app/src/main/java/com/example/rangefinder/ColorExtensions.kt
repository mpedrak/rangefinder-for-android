package com.example.rangefinder

import android.content.Context
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat

fun Context.color(@ColorRes colorRes: Int): Int = ContextCompat.getColor(this, colorRes)

