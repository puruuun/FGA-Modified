package io.github.fate_grand_automata.prefs.core

import android.content.SharedPreferences
import com.fredporciuncula.flow.preferences.FlowSharedPreferences
import com.fredporciuncula.flow.preferences.Serializer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.emptyFlow
import kotlin.reflect.KProperty

enum class PrefType {
    String,
    Int,
    Boolean,
    StringSet
}

class PrefMaker private constructor(
    private val _prefs: SharedPreferences?,
    private val schemaOnly: Boolean
) {
    constructor(prefs: SharedPreferences) : this(_prefs = prefs, schemaOnly = false)

    val prefs: SharedPreferences
        get() = requireNotNull(_prefs)

    val flowPrefs: FlowSharedPreferences by lazy {
        FlowSharedPreferences(prefs)
    }

    private val _types = mutableMapOf<String, PrefType>()
    val types: Map<String, PrefType> get() = _types

    internal companion object {
        /** Creates a maker that only records declared key types without touching SharedPreferences. */
        fun schemaOnly(): PrefMaker = PrefMaker(_prefs = null, schemaOnly = true)
    }

    fun int(key: String, default: Int = 0): Pref<Int> {
        _types[key] = PrefType.Int
        return if (schemaOnly) SchemaPref(key, default) else PrefImpl(flowPrefs.getInt(key, default))
    }

    fun bool(key: String, default: Boolean = false): Pref<Boolean> {
        _types[key] = PrefType.Boolean
        return if (schemaOnly) SchemaPref(key, default) else PrefImpl(flowPrefs.getBoolean(key, default))
    }

    fun string(key: String, default: String = ""): Pref<String> {
        _types[key] = PrefType.String
        return if (schemaOnly) SchemaPref(key, default) else PrefImpl(flowPrefs.getString(key, default))
    }

    fun <T : Any> serialized(key: String, serializer: Serializer<T>, default: T): Pref<T> {
        _types[key] = PrefType.String
        return if (schemaOnly) SchemaPref(key, default) else PrefImpl(flowPrefs.getObject(key, serializer, default))
    }

    private fun stringAsIntSerializer(default: Int) =
        object : Serializer<Int> {
            override fun deserialize(serialized: String) = serialized.toIntOrNull() ?: default
            override fun serialize(value: Int) = value.toString()
        }

    fun stringAsInt(key: String, default: Int = 0) =
        serialized(key, stringAsIntSerializer(default), default)

    inline fun <reified T : Enum<T>> enum(
        key: String,
        default: T
    ): Pref<T> {
        val serializer = object : Serializer<T> {
            override fun deserialize(serialized: String) =
                try {
                    enumValueOf(serialized)
                } catch (e: Exception) {
                    default
                }

            override fun serialize(value: T) = value.name
        }

        return serialized(key, serializer, default)
    }

    fun stringSet(key: String): Pref<Set<String>> {
        _types[key] = PrefType.StringSet
        return if (schemaOnly) SchemaPref(key, emptySet()) else PrefImpl(flowPrefs.getStringSet(key))
    }
}

private class SchemaPref<T>(
    override val key: String,
    override val defaultValue: T
) : Pref<T> {
    override fun getValue(thisRef: Any, property: KProperty<*>): T = defaultValue
    override fun setValue(thisRef: Any, property: KProperty<*>, value: T) = Unit
    override fun get(): T = defaultValue
    override fun set(value: T) = Unit
    override suspend fun setAndCommit(value: T): Boolean = false
    override fun isSet(): Boolean = false
    override fun isNotSet(): Boolean = true
    override fun delete() = Unit
    override suspend fun deleteAndCommit(): Boolean = false
    override fun resetToDefault() = Unit
    override fun asFlow(): Flow<T> = emptyFlow()
    override fun asCollector(): FlowCollector<T> = FlowCollector { }
    override fun asSyncCollector(throwOnFailure: Boolean): FlowCollector<T> = FlowCollector { }
}