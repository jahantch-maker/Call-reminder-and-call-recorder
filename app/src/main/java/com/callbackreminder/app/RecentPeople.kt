package com.callbackreminder.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** The last few people you set a call or text reminder for, newest first ("Used recently"). */
object RecentPeople {
    const val MAX = 5

    data class Person(val name: String, val number: String)

    private fun p(c: Context) = c.getSharedPreferences("recent_people", Context.MODE_PRIVATE)

    /** The same number written as "0300 1234567" or "+92 300 1234567" counts as one person. */
    fun key(number: String): String = number.filter { it.isDigit() }.takeLast(10)

    fun load(c: Context): List<Person> {
        val raw = p(c).getString("list", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Person(o.optString("name"), o.optString("number"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun record(c: Context, name: String, number: String) {
        if (number.isBlank()) return
        val k = key(number)
        val list = (listOf(Person(name.trim(), number.trim())) + load(c).filter { key(it.number) != k }).take(MAX)
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("name", it.name).put("number", it.number)) }
        p(c).edit().putString("list", arr.toString()).apply()
    }
}
