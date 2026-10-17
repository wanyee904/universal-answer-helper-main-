package com.example.answerhelper.util

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val NAME = "bili_helper"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun get(ctx: Context, key: String, def: String = ""): String =
        sp(ctx).getString(key, def) ?: def

    fun set(ctx: Context, key: String, value: String) {
        sp(ctx).edit().putString(key, value).apply()
    }

    fun getBool(ctx: Context, key: String, def: Boolean = false): Boolean =
        sp(ctx).getBoolean(key, def)

    fun setBool(ctx: Context, key: String, value: Boolean) {
        sp(ctx).edit().putBoolean(key, value).apply()
    }
}
