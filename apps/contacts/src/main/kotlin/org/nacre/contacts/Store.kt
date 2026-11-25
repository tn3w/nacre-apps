package org.nacre.contacts

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow

class ContactsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
    }
}

object Store {
    private lateinit var preferences: SharedPreferences

    val sortByLast = MutableStateFlow(false)
    val lastFirst = MutableStateFlow(false)

    fun init(context: Context) {
        preferences = context.getSharedPreferences("contacts", Context.MODE_PRIVATE)
        sortByLast.value = preferences.getBoolean("sortByLast", false)
        lastFirst.value = preferences.getBoolean("lastFirst", false)
    }

    fun setSortByLast(value: Boolean) {
        sortByLast.value = value
        preferences.edit { putBoolean("sortByLast", value) }
    }

    fun setLastFirst(value: Boolean) {
        lastFirst.value = value
        preferences.edit { putBoolean("lastFirst", value) }
    }
}
