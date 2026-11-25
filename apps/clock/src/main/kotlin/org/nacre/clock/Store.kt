package org.nacre.clock

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow

class ClockApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
    }
}

object Store {
    private lateinit var preferences: SharedPreferences

    val zones = MutableStateFlow(emptyList<String>())

    fun init(context: Context) {
        preferences = context.getSharedPreferences("clock", Context.MODE_PRIVATE)
        zones.value = preferences.getString("zones", "")!!.split(",").filter { it.isNotEmpty() }
    }

    fun addZone(zone: String) = saveZones(zones.value - zone + zone)

    fun removeZone(zone: String) = saveZones(zones.value - zone)

    private fun saveZones(list: List<String>) {
        zones.value = list
        preferences.edit().putString("zones", list.joinToString(",")).apply()
    }
}
