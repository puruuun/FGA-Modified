package io.github.fate_grand_automata.util.portraits

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.net.URI

data class ServantPortrait(val key: String, val url: String)

data class ServantPortraitMetadata(
    val collectionNo: Int,
    val servantId: Int,
    val name: String,
    val portraits: List<ServantPortrait>
) {
    val folderName get() = "No.$collectionNo"

    companion object {
        fun parse(text: String, requestedNo: Int): ServantPortraitMetadata {
            val root = Json.parseToJsonElement(text) as? JsonObject ?: invalid()
            val number = (root["collectionNo"] as? JsonPrimitive)?.intOrNull
            val id = (root["id"] as? JsonPrimitive)?.intOrNull
            val name = (root["name"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            if (requestedNo <= 0 || number != requestedNo || id == null || id <= 0 || name == null) invalid()
            val assets = root["extraAssets"] as? JsonObject ?: invalid()
            val portraits = mutableListOf<ServantPortrait>()
            fun collect(element: JsonElement?, path: List<String>) {
                if (path.size > 8 || portraits.size > 64) invalid()
                when (element) {
                    null -> Unit
                    is JsonObject -> element.forEach { (key, value) -> collect(value, path + key) }
                    is JsonPrimitive -> {
                        if (!element.isString || !validPortraitUrl(element.content)) invalid()
                        val key = path.joinToString("_")
                        if (!Regex("[A-Za-z0-9_-]{1,160}").matches(key)) invalid()
                        portraits += ServantPortrait(key, element.content)
                    }
                    else -> invalid()
                }
            }
            collect(assets["faces"], listOf("faces"))
            collect(assets["facesChange"], listOf("facesChange"))
            if (portraits.isEmpty()) invalid()
            // A single picture can represent several stages; store each distinct picture once.
            return ServantPortraitMetadata(number!!, id, name, portraits.distinctBy { it.url })
        }

        internal fun validPortraitUrl(url: String): Boolean = runCatching {
            val uri = URI(url)
            uri.scheme == "https" && uri.host in setOf("static.atlasacademy.io", "assets.atlasacademy.io") &&
                uri.port == -1 && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                Regex("/(?:GameData/)?JP/Faces/f_[A-Za-z0-9_]+\\.png").matches(uri.path)
        }.getOrDefault(false)

        private fun invalid(): Nothing = throw PortraitImportException(PortraitImportError.Metadata)
    }
}

enum class PortraitImportError { Number, NotFound, Network, Metadata, Image, Storage }
class PortraitImportException(val reason: PortraitImportError) : Exception(reason.name)

/** In 720p image coordinates, measured against the JP support and friend screens. */
object SupportPortraitCrop {
    const val SOURCE_SIZE = 128
    const val RESIZED_SIZE = 158
    const val X = 3
    const val Y = 47
    const val WIDTH = 125
    const val HEIGHT = 44
}
