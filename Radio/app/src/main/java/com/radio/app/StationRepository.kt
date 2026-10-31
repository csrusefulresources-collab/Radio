package com.radio.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Built-in stations (assets/stations.json) + stations the user added inside the app. */
object StationRepository {
    private const val PREFS = "radio_user_stations"
    private const val KEY = "stations"

    fun load(context: Context): List<Station> = loadAsset(context) + loadUser(context)

    fun addUser(context: Context, station: Station) {
        saveUser(context, loadUser(context) + station.copy(isUser = true))
    }

    fun removeUser(context: Context, station: Station) {
        saveUser(context, loadUser(context).filterNot {
            it.url == station.url && it.name == station.name && it.band == station.band
        })
    }

    private fun loadAsset(context: Context): List<Station> = try {
        val text = context.assets.open("stations.json").bufferedReader().use { it.readText() }
        parse(JSONArray(text), false)
    } catch (e: Exception) {
        emptyList()
    }

    private fun loadUser(context: Context): List<Station> = try {
        val text = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        if (text.isNullOrBlank()) emptyList() else parse(JSONArray(text), true)
    } catch (e: Exception) {
        emptyList()
    }

    private fun saveUser(context: Context, list: List<Station>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("name", it.name).put("frequency", it.frequency)
                .put("band", it.band.name).put("url", it.url))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    private fun parse(arr: JSONArray, isUser: Boolean): List<Station> {
        val result = ArrayList<Station>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val band = runCatching { Band.valueOf(o.optString("band").uppercase()) }.getOrNull() ?: continue
            val url = o.optString("url")
            if (url.isBlank()) continue
            result.add(Station(o.optString("name", url), o.optString("frequency", ""), band, url, isUser))
        }
        return result
    }
}
