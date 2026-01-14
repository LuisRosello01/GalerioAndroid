package com.example.galerio.data.remote.adapter

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import java.lang.reflect.Type
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Adaptador de Gson para convertir fechas ISO 8601 a timestamps Long (milisegundos)
 *
 * La API devuelve fechas en formato: "2024-06-09T14:05:32.348000"
 * Este adaptador las convierte a Long (timestamp en milisegundos) para compatibilidad
 * con el resto de la app que usa timestamps.
 */
class DateTimeAdapter : JsonDeserializer<Long>, JsonSerializer<Long> {

    companion object {
        // Formato ISO 8601 con microsegundos
        private val ISO_FORMATTER = DateTimeFormatter.ISO_DATE_TIME
    }

    /**
     * Deserializa una fecha ISO 8601 string a Long (timestamp en milisegundos)
     * Soporta múltiples formatos:
     * - String ISO 8601: "2024-06-09T14:05:32.348000"
     * - MongoDB Extended JSON: {"$date": "2025-11-20T11:19:30.000Z"}
     * - Timestamp numérico: 1732101570000
     */
    override fun deserialize(
        json: JsonElement?,
        typeOfT: Type?,
        context: JsonDeserializationContext?
    ): Long {
        if (json == null || json.isJsonNull) {
            return 0L
        }

        return try {
            // Caso 1: MongoDB Extended JSON - {"$date": "..."}
            if (json.isJsonObject) {
                val jsonObject = json.asJsonObject
                if (jsonObject.has("\$date")) {
                    val dateElement = jsonObject.get("\$date")
                    // El valor de $date puede ser un string ISO o un timestamp numérico
                    return if (dateElement.isJsonPrimitive) {
                        val primitive = dateElement.asJsonPrimitive
                        if (primitive.isNumber) {
                            primitive.asLong
                        } else {
                            parseIsoDateString(primitive.asString)
                        }
                    } else {
                        0L
                    }
                }
            }

            // Caso 2: Es un número primitivo (timestamp)
            if (json.isJsonPrimitive && json.asJsonPrimitive.isNumber) {
                return json.asLong
            }

            // Caso 3: String ISO 8601 o timestamp como string
            val dateString = json.asString

            // Si ya es un número (timestamp), devolverlo directamente
            if (dateString.matches(Regex("^\\d+$"))) {
                dateString.toLong()
            } else {
                parseIsoDateString(dateString)
            }
        } catch (e: Exception) {
            throw JsonParseException("Failed to parse date: $json", e)
        }
    }

    /**
     * Parsea una fecha ISO 8601 string a timestamp en milisegundos
     */
    private fun parseIsoDateString(dateString: String): Long {
        // Limpiar el formato de fecha para hacerlo compatible con ISO 8601
        // La API envía "2024-06-09T14:05:32.348000" (6 dígitos de microsegundos)
        // Pero ISO 8601 espera hasta 9 dígitos (nanosegundos) o 3 (milisegundos)
        val cleanedDateString = if (dateString.contains('.')) {
            val parts = dateString.split('.')
            if (parts.size == 2) {
                val datePart = parts[0]
                val fractionPart = parts[1].trimEnd('Z')

                // Truncar a 3 dígitos (milisegundos) para compatibilidad
                val millisPart = fractionPart.take(3)
                "${datePart}.${millisPart}Z"
            } else {
                dateString
            }
        } else {
            // Si no tiene fracción de segundo, agregar Z si no la tiene
            if (!dateString.endsWith('Z')) "${dateString}Z" else dateString
        }

        // Parsear como fecha ISO 8601
        val instant = try {
            Instant.parse(cleanedDateString)
        } catch (e: Exception) {
            // Intentar con ZonedDateTime como fallback
            ZonedDateTime.parse(cleanedDateString, ISO_FORMATTER).toInstant()
        }

        return instant.toEpochMilli()
    }

    /**
     * Serializa un Long (timestamp en milisegundos) a string ISO 8601
     */
    override fun serialize(
        src: Long?,
        typeOfSrc: Type?,
        context: JsonSerializationContext?
    ): JsonElement {
        if (src == null || src == 0L) {
            return JsonPrimitive("")
        }

        val instant = Instant.ofEpochMilli(src)
        return JsonPrimitive(instant.toString())
    }
}

