package com.diabetes.calculator.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Utilidades para formateo y manejo de fechas.
 */
object DateUtils {
    
    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale("es", "ES"))
    private val timeFormat = SimpleDateFormat("HH:mm", Locale("es", "ES"))
    private val dateTimeFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("es", "ES"))
    
    /**
     * Formatea un timestamp a fecha legible (dd/MM/yyyy).
     */
    fun formatDate(timestamp: Long): String {
        return dateFormat.format(Date(timestamp))
    }
    
    /**
     * Formatea un timestamp a hora legible (HH:mm).
     */
    fun formatTime(timestamp: Long): String {
        return timeFormat.format(Date(timestamp))
    }
    
    /**
     * Formatea un timestamp a fecha y hora (dd/MM/yyyy HH:mm).
     */
    fun formatDateTime(timestamp: Long): String {
        return dateTimeFormat.format(Date(timestamp))
    }
    
    /**
     * Obtiene el timestamp del inicio del día actual.
     */
    fun getStartOfToday(): Long {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    
    /**
     * Obtiene el timestamp del final del día actual.
     */
    fun getEndOfToday(): Long {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis
    }
    
    /**
     * Verifica si un timestamp es de hoy.
     */
    fun isToday(timestamp: Long): Boolean {
        val startOfToday = getStartOfToday()
        val endOfToday = getEndOfToday()
        return timestamp in startOfToday..endOfToday
    }

    /**
     * Obtiene el timestamp del inicio del día para un timestamp dado.
     */
    fun getStartOfDay(timestamp: Long): Long {
        return Calendar.getInstance().apply {
            timeInMillis = timestamp
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    /**
     * Obtiene etiqueta relativa para un día (Hoy, Ayer o fecha).
     */
    fun getRelativeDayLabel(timestamp: Long): String {
        val startOfToday = getStartOfToday()
        val startOfYesterday = startOfToday - 24 * 60 * 60 * 1000
        val dayStart = getStartOfDay(timestamp)

        return when {
            dayStart >= startOfToday -> "Hoy"
            dayStart >= startOfYesterday -> "Ayer"
            else -> formatDate(timestamp)
        }
    }
    
    /**
     * Obtiene texto relativo para la fecha (Hoy, Ayer, o fecha).
     */
    fun getRelativeDate(timestamp: Long): String {
        val startOfToday = getStartOfToday()
        val startOfYesterday = startOfToday - 24 * 60 * 60 * 1000
        
        return when {
            timestamp >= startOfToday -> "Hoy, ${formatTime(timestamp)}"
            timestamp >= startOfYesterday -> "Ayer, ${formatTime(timestamp)}"
            else -> formatDateTime(timestamp)
        }
    }
}
