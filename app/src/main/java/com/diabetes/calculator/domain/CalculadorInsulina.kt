package com.diabetes.calculator.domain

import kotlin.math.round

/**
 * Objeto singleton que contiene la lógica de cálculo para insulina.
 * Implementa las fórmulas según los requisitos especificados.
 */
object CalculadorInsulina {
    
    /**
     * Calcula los gramos de hidratos de carbono totales.
     * Fórmula: hidratosTotales = (hidratosPor100g / 100) * gramosConsumidos
     *
     * @param hidratosPor100g Gramos de HC por cada 100g del alimento
     * @param gramosConsumidos Cantidad en gramos del alimento consumido
     * @return Hidratos de carbono totales en gramos
     */
    fun calcularHidratos(hidratosPor100g: Float, gramosConsumidos: Float): Float {
        if (gramosConsumidos <= 0 || hidratosPor100g < 0) return 0f
        return (hidratosPor100g / 100f) * gramosConsumidos
    }
    
    /**
     * Calcula el número de raciones de hidratos.
     * Fórmula: raciones = hidratosTotales / gramosPorRacion
     *
     * @param hidratosTotales Gramos totales de hidratos consumidos
     * @param gramosPorRacion Gramos de HC que equivalen a 1 ración (configurable por usuario)
     * @return Número de raciones
     */
    fun calcularRaciones(hidratosTotales: Float, gramosPorRacion: Float): Float {
        if (gramosPorRacion <= 0) return 0f
        return hidratosTotales / gramosPorRacion
    }
    
    /**
     * Calcula las unidades de insulina rápida necesarias.
     * El resultado se redondea al múltiplo de 0.5 más cercano.
     * Fórmula: insulina = raciones * ratioInsulina
     *
     * @param raciones Número de raciones de hidratos
     * @param ratioInsulina Unidades de insulina por cada ración
     * @return Unidades de insulina redondeadas a 0.5
     */
    fun calcularInsulina(raciones: Float, ratioInsulina: Float): Float {
        if (raciones <= 0 || ratioInsulina <= 0) return 0f
        val insulinaSinRedondear = raciones * ratioInsulina
        // Redondear a 0.5 más cercano: multiplicar por 2, redondear, dividir por 2
        return round(insulinaSinRedondear * 2) / 2f
    }
    
    /**
     * Calcula todos los valores de una sola vez.
     * Método de conveniencia para obtener hidratosTotal, raciones e insulina.
     *
     * @param hidratosPor100g Gramos de HC por cada 100g del alimento
     * @param gramosConsumidos Cantidad en gramos del alimento consumido
     * @param gramosPorRacion Gramos de HC por ración (del perfil de usuario)
     * @param ratioInsulina Ratio insulina/ración (del perfil de usuario)
     * @return Triple con (hidratosTotales, raciones, unidadesInsulina)
     */
    fun calcularTodo(
        hidratosPor100g: Float,
        gramosConsumidos: Float,
        gramosPorRacion: Float,
        ratioInsulina: Float
    ): Triple<Float, Float, Float> {
        val hidratos = calcularHidratos(hidratosPor100g, gramosConsumidos)
        val raciones = calcularRaciones(hidratos, gramosPorRacion)
        val insulina = calcularInsulina(raciones, ratioInsulina)
        return Triple(hidratos, raciones, insulina)
    }
}
