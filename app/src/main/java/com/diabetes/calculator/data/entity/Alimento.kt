package com.diabetes.calculator.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

object TipoMedicionAlimento {
    const val GRAMOS = "GRAMOS"
    const val ML = "ML"
    const val UNIDAD = "UNIDAD"

    val all = setOf(GRAMOS, ML, UNIDAD)
}

object EstadoFisicoAlimento {
    const val SOLIDO = "SOLIDO"
    const val SOLIDO_BLANDO = "SOLIDO_BLANDO"
    const val LIQUIDO = "LIQUIDO"

    val all = setOf(SOLIDO, SOLIDO_BLANDO, LIQUIDO)
}

object UnidadConsumoAlimento {
    const val GRAMOS = "g"
    const val ML = "ml"
    const val UNIDAD = "unidad"
}

data class ResultadoCalculoAlimento(
    val hidratos: Float,
    val cantidadBase: Float,
    val unidadBase: String
)

/**
 * Entidad que representa un alimento con su contenido en hidratos de carbono.
 *
 * @property id Identificador único del alimento
 * @property nombre Nombre descriptivo del alimento (ej: "Arroz blanco hervido")
 * @property hidratosPor100g Gramos de hidratos de carbono por cada 100g del alimento
 * @property fuente Origen de la información nutricional ("librito", "manual", "personal")
 * @property nota Nota opcional (ej: "peso cocido aprox.")
 * @property fotoUri URI persistida de una imagen del alimento/etiqueta
 */
@Serializable
@Entity(tableName = "alimentos")
data class Alimento(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val nombre: String,
    val hidratosPor100g: Float,
    val fuente: String,
    val nota: String? = null,
    val tipoMedicionPrincipal: String = TipoMedicionAlimento.GRAMOS,
    val estadoFisico: String = EstadoFisicoAlimento.SOLIDO,
    val hidratosPor100ml: Float? = null,
    val unidadNombre: String? = null,
    val gramosPorUnidad: Float? = null,
    val mlPorUnidad: Float? = null,
    val fotoUri: String? = null
)

fun Alimento.tipoMedicionNormalizado(): String {
    return if (TipoMedicionAlimento.all.contains(tipoMedicionPrincipal)) {
        tipoMedicionPrincipal
    } else {
        TipoMedicionAlimento.GRAMOS
    }
}

fun Alimento.estadoFisicoNormalizado(): String {
    return if (EstadoFisicoAlimento.all.contains(estadoFisico)) {
        estadoFisico
    } else {
        EstadoFisicoAlimento.SOLIDO
    }
}

fun Alimento.usaReferenciaPor100ml(): Boolean {
    return tipoMedicionNormalizado() == TipoMedicionAlimento.ML ||
        (tipoMedicionNormalizado() == TipoMedicionAlimento.UNIDAD &&
            estadoFisicoNormalizado() == EstadoFisicoAlimento.LIQUIDO)
}

fun Alimento.unidadEntradaPrincipal(): String {
    return when (tipoMedicionNormalizado()) {
        TipoMedicionAlimento.ML -> "ml"
        TipoMedicionAlimento.UNIDAD -> "ud"
        else -> "g"
    }
}

fun Alimento.requiereEquivalenciaUnidad(): Boolean {
    if (tipoMedicionNormalizado() != TipoMedicionAlimento.UNIDAD) return false
    return if (estadoFisicoNormalizado() == EstadoFisicoAlimento.LIQUIDO) {
        (mlPorUnidad ?: 0f) <= 0f
    } else {
        (gramosPorUnidad ?: 0f) <= 0f
    }
}

fun Alimento.tieneConfiguracionNutricionalValida(): Boolean {
    return when (tipoMedicionNormalizado()) {
        TipoMedicionAlimento.GRAMOS -> hidratosPor100g >= 0f
        TipoMedicionAlimento.ML -> (hidratosPor100ml ?: -1f) >= 0f
        TipoMedicionAlimento.UNIDAD -> {
            val unidadOk = !unidadNombre.isNullOrBlank()
            val equivalenciaOk = !requiereEquivalenciaUnidad()
            val hidratosOk = if (estadoFisicoNormalizado() == EstadoFisicoAlimento.LIQUIDO) {
                (hidratosPor100ml ?: -1f) >= 0f
            } else {
                hidratosPor100g >= 0f
            }
            unidadOk && equivalenciaOk && hidratosOk
        }

        else -> false
    }
}

fun Alimento.calcularDesdeCantidad(cantidad: Float): ResultadoCalculoAlimento? {
    if (cantidad < 0f) return null

    return when (tipoMedicionNormalizado()) {
        TipoMedicionAlimento.GRAMOS -> {
            if (hidratosPor100g < 0f) return null
            ResultadoCalculoAlimento(
                hidratos = (hidratosPor100g * cantidad) / 100f,
                cantidadBase = cantidad,
                unidadBase = UnidadConsumoAlimento.GRAMOS
            )
        }

        TipoMedicionAlimento.ML -> {
            val hidratos = hidratosPor100ml ?: return null
            if (hidratos < 0f) return null
            ResultadoCalculoAlimento(
                hidratos = (hidratos * cantidad) / 100f,
                cantidadBase = cantidad,
                unidadBase = UnidadConsumoAlimento.ML
            )
        }

        TipoMedicionAlimento.UNIDAD -> {
            if (estadoFisicoNormalizado() == EstadoFisicoAlimento.LIQUIDO) {
                val ml = mlPorUnidad?.takeIf { it > 0f } ?: return null
                val hidratos = hidratosPor100ml ?: return null
                val cantidadBase = cantidad * ml
                ResultadoCalculoAlimento(
                    hidratos = (hidratos * cantidadBase) / 100f,
                    cantidadBase = cantidadBase,
                    unidadBase = UnidadConsumoAlimento.ML
                )
            } else {
                val gramos = gramosPorUnidad?.takeIf { it > 0f } ?: return null
                val cantidadBase = cantidad * gramos
                ResultadoCalculoAlimento(
                    hidratos = (hidratosPor100g * cantidadBase) / 100f,
                    cantidadBase = cantidadBase,
                    unidadBase = UnidadConsumoAlimento.GRAMOS
                )
            }
        }

        else -> null
    }
}
