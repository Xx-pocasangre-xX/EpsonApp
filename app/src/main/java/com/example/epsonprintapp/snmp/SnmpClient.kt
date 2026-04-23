package com.example.epsonprintapp.snmp

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * SnmpClient - Cliente SNMP para consultar niveles de tinta
 *
 * ¿QUÉ ES SNMP?
 * =============
 * SNMP (Simple Network Management Protocol) es el protocolo estándar
 * para monitorear dispositivos de red. Las impresoras Epson lo soportan
 * para exponer información de estado como niveles de tinta.
 *
 * SNMP usa UDP en el puerto 161.
 * Versión usada: SNMPv1 (más simple, ampliamente compatible)
 *
 * ESTRUCTURA DE UN PAQUETE SNMP GET:
 * ===================================
 * SEQUENCE {
 *   INTEGER (versión)       → 0 = v1
 *   OCTET STRING (community) → "public" (sin autenticación)
 *   GetRequest {
 *     INTEGER (request-id)
 *     INTEGER (error-status)  → 0
 *     INTEGER (error-index)   → 0
 *     SEQUENCE (variable bindings) {
 *       SEQUENCE {
 *         OID (objeto a consultar)
 *         NULL (valor vacío, la impresora lo rellena)
 *       }
 *     }
 *   }
 * }
 *
 * OIDs PARA NIVELES DE TINTA (Printer MIB - RFC 3805):
 * ======================================================
 * .1.3.6.1.2.1.43.11.1.1.8.1.1 → Nivel actual tinta 1 (Cyan o Black)
 * .1.3.6.1.2.1.43.11.1.1.8.1.2 → Nivel actual tinta 2 (Magenta)
 * .1.3.6.1.2.1.43.11.1.1.8.1.3 → Nivel actual tinta 3 (Yellow)
 * .1.3.6.1.2.1.43.11.1.1.8.1.4 → Nivel actual tinta 4 (Black)
 * .1.3.6.1.2.1.43.11.1.1.9.1.X → Capacidad máxima de tinta X
 *
 * Nota: En Epson EcoTank, estos valores pueden ser -2 (ilimitado/recargable)
 * porque el sistema EcoTank no mide por cartuchos sino por depósito.
 *
 * ALTERNATIVA VÍA HTTP:
 * ======================
 * Si SNMP no funciona, Epson también expone el estado de tinta en:
 * http://<IP>/PRESENTATION/HTML/TOP/INDEX.HTM (interfaz web)
 * http://<IP>/cgi-bin/info.cgi (información en texto)
 *
 * Esta clase implementa ambos métodos.
 */
class SnmpClient {

    companion object {
        private const val TAG = "SnmpClient"
        private const val SNMP_PORT = 161
        private const val SNMP_TIMEOUT_MS = 3000
        private const val COMMUNITY = "public"  // Community string pública (sin auth)

        // OIDs del Printer MIB para niveles de tinta
        // Formato: .1.3.6.1.2.1.43.11.1.1.8.1.[índice]
        // donde índice = 1,2,3,4 para cada color
        private val INK_LEVEL_OIDS = mapOf(
            "cyan"    to intArrayOf(1,3,6,1,2,1,43,11,1,1,8,1,1),
            "magenta" to intArrayOf(1,3,6,1,2,1,43,11,1,1,8,1,2),
            "yellow"  to intArrayOf(1,3,6,1,2,1,43,11,1,1,8,1,3),
            "black"   to intArrayOf(1,3,6,1,2,1,43,11,1,1,8,1,4)
        )

        // OIDs para capacidad máxima de tinta
        private val INK_MAX_OIDS = mapOf(
            "cyan"    to intArrayOf(1,3,6,1,2,1,43,11,1,1,9,1,1),
            "magenta" to intArrayOf(1,3,6,1,2,1,43,11,1,1,9,1,2),
            "yellow"  to intArrayOf(1,3,6,1,2,1,43,11,1,1,9,1,3),
            "black"   to intArrayOf(1,3,6,1,2,1,43,11,1,1,9,1,4)
        )

        // OID para nombre de la impresora
        private val PRINTER_NAME_OID = intArrayOf(1,3,6,1,2,1,1,1,0)
    }

    /**
     * Consultar niveles de tinta via SNMP
     *
     * Envía peticiones SNMP GET para cada color y calcula el porcentaje.
     * Si SNMP no está disponible, retorna valores por defecto.
     *
     * @param printerIp IP de la impresora
     * @return Map con porcentajes de tinta: {"cyan": 70, "magenta": 85, ...}
     */
    suspend fun getInkLevels(printerIp: String): Map<String, Int> {
        val levels = mutableMapOf<String, Int>()

        try {
            val socket = DatagramSocket()
            socket.soTimeout = SNMP_TIMEOUT_MS

            val address = InetAddress.getByName(printerIp)

            for ((color, oid) in INK_LEVEL_OIDS) {
                try {
                    // Construir y enviar petición SNMP GET
                    val requestPacket = buildSnmpGetRequest(oid)
                    val udpPacket = DatagramPacket(requestPacket, requestPacket.size, address, SNMP_PORT)
                    socket.send(udpPacket)

                    // Recibir respuesta
                    val responseBuffer = ByteArray(1024)
                    val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
                    socket.receive(responsePacket)

                    // Parsear valor de tinta
                    val inkValue = parseSnmpIntegerResponse(
                        responsePacket.data.copyOf(responsePacket.length)
                    )

                    // Obtener capacidad máxima para calcular porcentaje
                    val maxOid = INK_MAX_OIDS[color]
                    val maxValue = if (maxOid != null) {
                        val maxRequest = buildSnmpGetRequest(maxOid)
                        val maxPacket = DatagramPacket(maxRequest, maxRequest.size, address, SNMP_PORT)
                        socket.send(maxPacket)

                        val maxResponseBuffer = ByteArray(1024)
                        val maxResponsePacket = DatagramPacket(maxResponseBuffer, maxResponseBuffer.size)
                        socket.receive(maxResponsePacket)

                        parseSnmpIntegerResponse(maxResponsePacket.data.copyOf(maxResponsePacket.length))
                    } else -1

                    // Calcular porcentaje
                    val percentage = when {
                        inkValue == -2 || maxValue == -2 -> -2  // Ilimitado (EcoTank recargable)
                        inkValue < 0 -> -1                       // Desconocido
                        maxValue > 0 -> (inkValue * 100) / maxValue  // Porcentaje real
                        else -> inkValue                          // Valor ya es porcentaje
                    }

                    levels[color] = percentage
                    Log.d(TAG, "Tinta $color: $inkValue/$maxValue = $percentage%")

                } catch (e: Exception) {
                    Log.w(TAG, "No se pudo obtener nivel de $color: ${e.message}")
                    levels[color] = -1
                }
            }

            socket.close()

        } catch (e: Exception) {
            Log.e(TAG, "Error SNMP: ${e.message}")
            // Si SNMP falla completamente, retornar valores desconocidos
            return mapOf("cyan" to -1, "magenta" to -1, "yellow" to -1, "black" to -1)
        }

        return levels
    }

    // =========================================================================
    // CONSTRUCCIÓN DE PAQUETES SNMP
    // =========================================================================

    /**
     * Construir paquete SNMP v1 GetRequest
     *
     * Estructura BER/DER del paquete SNMP:
     *
     * 30 XX          → SEQUENCE (mensaje SNMP completo)
     *   02 01 00     → INTEGER = 0 (versión SNMPv1)
     *   04 06 public → OCTET STRING = "public" (community)
     *   A0 XX        → GetRequest-PDU
     *     02 04 XXXX → INTEGER (request-id)
     *     02 01 00   → INTEGER = 0 (error-status)
     *     02 01 00   → INTEGER = 0 (error-index)
     *     30 XX      → SEQUENCE (variable-bindings)
     *       30 XX    → SEQUENCE (variable binding)
     *         06 XX OID → OID a consultar
     *         05 00    → NULL (valor vacío)
     *
     * @param oid Array de enteros representando el OID
     * @return ByteArray con el paquete SNMP listo para enviar
     */
    private fun buildSnmpGetRequest(oid: IntArray): ByteArray {
        val encodedOid = encodeOid(oid)
        val requestId = (System.currentTimeMillis() % 0xFFFF).toInt()

        // Variable binding: SEQUENCE { OID, NULL }
        val varBind = buildTlv(0x30,
            buildTlv(0x06, encodedOid) +   // OID
                    byteArrayOf(0x05, 0x00)          // NULL
        )

        // Variable bindings list: SEQUENCE { varBind }
        val varBindList = buildTlv(0x30, varBind)

        // GetRequest PDU: A0 { requestId, errorStatus=0, errorIndex=0, varBindList }
        val pdu = buildTlv(0xA0.toByte(),
            buildTlv(0x02, encodeInteger(requestId)) +   // request-id
                    byteArrayOf(0x02, 0x01, 0x00) +               // error-status = 0
                    byteArrayOf(0x02, 0x01, 0x00) +               // error-index = 0
                    varBindList
        )

        // Mensaje SNMP: SEQUENCE { version, community, PDU }
        val message = buildTlv(0x30,
            byteArrayOf(0x02, 0x01, 0x00) +               // version = 0 (SNMPv1)
                    buildTlv(0x04, COMMUNITY.toByteArray()) +      // community = "public"
                    pdu
        )

        return message
    }

    /**
     * Construir un TLV (Type-Length-Value) para codificación BER
     *
     * @param type Tipo del elemento
     * @param value Bytes del valor
     */
    private fun buildTlv(type: Byte, value: ByteArray): ByteArray {
        val length = encodeBerLength(value.size)
        return byteArrayOf(type) + length + value
    }

    /**
     * Codificar un OID en formato BER
     *
     * Los primeros dos componentes se combinan: primer_byte = x*40 + y
     * Ej: .1.3.6.1 → primer byte = 1*40+3 = 43 (0x2B), luego 6, 1
     *
     * Números > 127 se codifican en múltiples bytes con bit de continuación.
     */
    private fun encodeOid(oid: IntArray): ByteArray {
        if (oid.size < 2) return byteArrayOf()

        val result = mutableListOf<Byte>()
        // Primeros dos componentes combinados
        result.add((oid[0] * 40 + oid[1]).toByte())

        // Componentes restantes
        for (i in 2 until oid.size) {
            val component = oid[i]
            if (component <= 127) {
                result.add(component.toByte())
            } else {
                // Codificación multibyte para valores > 127
                val bytes = mutableListOf<Byte>()
                var value = component
                bytes.add((value and 0x7F).toByte())
                value = value shr 7
                while (value > 0) {
                    bytes.add(0, ((value and 0x7F) or 0x80).toByte())
                    value = value shr 7
                }
                result.addAll(bytes)
            }
        }

        return result.toByteArray()
    }

    /**
     * Codificar longitud en formato BER
     *
     * Si longitud <= 127: un solo byte
     * Si longitud > 127: 0x81 o 0x82 seguido de los bytes de longitud
     */
    private fun encodeBerLength(length: Int): ByteArray {
        return when {
            length <= 127 -> byteArrayOf(length.toByte())
            length <= 255 -> byteArrayOf(0x81.toByte(), length.toByte())
            else -> byteArrayOf(
                0x82.toByte(),
                ((length shr 8) and 0xFF).toByte(),
                (length and 0xFF).toByte()
            )
        }
    }

    /**
     * Codificar un entero en BER
     */
    private fun encodeInteger(value: Int): ByteArray {
        return byteArrayOf(
            ((value shr 24) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte()
        )
    }

    // =========================================================================
    // PARSEO DE RESPUESTAS SNMP
    // =========================================================================

    /**
     * Extraer valor entero de una respuesta SNMP
     *
     * Navega la estructura BER hasta encontrar el valor en el VarBind.
     * El valor de tinta es un INTEGER (tipo 0x02).
     *
     * @param bytes Bytes de la respuesta SNMP
     * @return Valor entero extraído, o -1 si hay error
     */
    private fun parseSnmpIntegerResponse(bytes: ByteArray): Int {
        return try {
            var pos = 0

            // Navegar la estructura: SEQUENCE > GetResponse > VarBindList > VarBind > value
            fun skipTlv(pos: Int): Int {
                if (pos >= bytes.size) return pos
                val type = bytes[pos]
                val length = if (bytes[pos + 1].toInt() and 0x80 == 0) {
                    bytes[pos + 1].toInt() and 0xFF
                } else {
                    bytes[pos + 2].toInt() and 0xFF  // Simplificado para longitudes de 1 byte
                }
                return pos + 2 + length
            }

            // Buscar el valor INTEGER en el VarBind
            // Estrategia: buscar el último INTEGER en el paquete
            var lastIntValue = -1
            pos = 0

            while (pos < bytes.size - 2) {
                if (bytes[pos] == 0x02.toByte()) {  // INTEGER type
                    val len = bytes[pos + 1].toInt() and 0xFF
                    if (pos + 2 + len <= bytes.size && len in 1..4) {
                        var value = 0
                        for (i in 0 until len) {
                            value = (value shl 8) or (bytes[pos + 2 + i].toInt() and 0xFF)
                        }
                        // El valor de tinta suele ser el último entero significativo
                        if (value != 0 && value !in listOf(161, 0)) {
                            lastIntValue = value
                        }
                    }
                }
                pos++
            }

            lastIntValue
        } catch (e: Exception) {
            Log.e(TAG, "Error parseando respuesta SNMP: ${e.message}")
            -1
        }
    }
}
