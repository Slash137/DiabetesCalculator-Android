package com.diabetes.calculator.domain

import com.diabetes.calculator.data.entity.UsuarioProfile
import java.util.Calendar

enum class FranjaHoraria(val key: String) {
    MADRUGADA("madrugada"),
    MANANA("manana"),
    TARDE("tarde"),
    NOCHE("noche");

    companion object {
        fun fromStorage(value: String?): FranjaHoraria? {
            if (value.isNullOrBlank()) return null
            return entries.firstOrNull { it.key == value }
        }
    }
}

enum class NivelEstres(val key: String) {
    NINGUNO("ninguno"),
    LEVE("leve"),
    MODERADO("moderado"),
    ALTO("alto");

    companion object {
        fun fromStorage(value: String?): NivelEstres? {
            if (value.isNullOrBlank()) return null
            return entries.firstOrNull { it.key == value }
        }
    }
}

enum class NivelEnfermedad(val key: String) {
    NINGUNA("ninguna"),
    LEVE("leve"),
    MODERADA("moderada"),
    ALTA("alta");

    companion object {
        fun fromStorage(value: String?): NivelEnfermedad? {
            if (value.isNullOrBlank()) return null
            return entries.firstOrNull { it.key == value }
        }
    }
}

enum class FaseCicloHormonal(val key: String) {
    NO_APLICAR("no_aplicar"),
    MENSTRUACION("menstruacion"),
    FOLICULAR("folicular"),
    OVULACION("ovulacion"),
    LUTEA("lutea");

    companion object {
        fun fromStorage(value: String?): FaseCicloHormonal? {
            if (value.isNullOrBlank()) return null
            return entries.firstOrNull { it.key == value }
        }
    }
}

enum class NivelEjercicio(val key: String) {
    NINGUNO("ninguno"),
    SUAVE("suave"),
    MODERADO("moderado"),
    INTENSO("intenso");

    companion object {
        fun fromStorage(value: String?): NivelEjercicio? {
            if (value.isNullOrBlank()) return null
            return entries.firstOrNull { it.key == value }
        }
    }
}

data class SeleccionContextoInsulina(
    val franjaHoraria: FranjaHoraria,
    val nivelEstres: NivelEstres,
    val nivelEnfermedad: NivelEnfermedad,
    val faseCiclo: FaseCicloHormonal,
    val nivelEjercicio: NivelEjercicio
)

data class ResultadoContextoInsulina(
    val factorHora: Float,
    val factorEstres: Float,
    val factorEnfermedad: Float,
    val factorCiclo: Float,
    val factorEjercicio: Float,
    val factorTotalRaw: Float,
    val factorTotalAplicado: Float,
    val factorCapado: Boolean
)

data class ResultadoDosisContextual(
    val totalConCorreccion: Float,
    val totalSinCorreccion: Float
)

object FactoresContextoInsulina {
    const val FACTOR_MIN = 0.60f
    const val FACTOR_MAX = 1.40f

    fun franjaPorHora(hourOfDay: Int): FranjaHoraria {
        return when (hourOfDay) {
            in 0..5 -> FranjaHoraria.MADRUGADA
            in 6..11 -> FranjaHoraria.MANANA
            in 12..17 -> FranjaHoraria.TARDE
            else -> FranjaHoraria.NOCHE
        }
    }

    fun franjaPorTimestamp(timestampMillis: Long): FranjaHoraria {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = timestampMillis
        return franjaPorHora(calendar.get(Calendar.HOUR_OF_DAY))
    }

    fun defaultSelection(nowMillis: Long = System.currentTimeMillis()): SeleccionContextoInsulina {
        return SeleccionContextoInsulina(
            franjaHoraria = franjaPorTimestamp(nowMillis),
            nivelEstres = NivelEstres.NINGUNO,
            nivelEnfermedad = NivelEnfermedad.NINGUNA,
            faseCiclo = FaseCicloHormonal.NO_APLICAR,
            nivelEjercicio = NivelEjercicio.NINGUNO
        )
    }

    fun resolve(
        profile: UsuarioProfile,
        selection: SeleccionContextoInsulina
    ): ResultadoContextoInsulina {
        val factorHora = when (selection.franjaHoraria) {
            FranjaHoraria.MADRUGADA -> profile.factorHoraMadrugada
            FranjaHoraria.MANANA -> profile.factorHoraManana
            FranjaHoraria.TARDE -> profile.factorHoraTarde
            FranjaHoraria.NOCHE -> profile.factorHoraNoche
        }

        val factorEstres = when (selection.nivelEstres) {
            NivelEstres.NINGUNO -> 1f
            NivelEstres.LEVE -> profile.factorEstresLeve
            NivelEstres.MODERADO -> profile.factorEstresModerado
            NivelEstres.ALTO -> profile.factorEstresAlto
        }

        val factorEnfermedad = when (selection.nivelEnfermedad) {
            NivelEnfermedad.NINGUNA -> 1f
            NivelEnfermedad.LEVE -> profile.factorEnfermedadLeve
            NivelEnfermedad.MODERADA -> profile.factorEnfermedadModerada
            NivelEnfermedad.ALTA -> profile.factorEnfermedadAlta
        }

        val factorCiclo = if (!profile.cicloHormonalActivo) {
            1f
        } else {
            when (selection.faseCiclo) {
                FaseCicloHormonal.NO_APLICAR -> 1f
                FaseCicloHormonal.MENSTRUACION -> profile.factorCicloMenstruacion
                FaseCicloHormonal.FOLICULAR -> profile.factorCicloFolicular
                FaseCicloHormonal.OVULACION -> profile.factorCicloOvulacion
                FaseCicloHormonal.LUTEA -> profile.factorCicloLutea
            }
        }

        val factorEjercicio = when (selection.nivelEjercicio) {
            NivelEjercicio.NINGUNO -> 1f
            NivelEjercicio.SUAVE -> profile.factorEjercicioSuave
            NivelEjercicio.MODERADO -> profile.factorEjercicioModerado
            NivelEjercicio.INTENSO -> profile.factorEjercicioIntenso
        }

        val factorTotalRaw = factorHora * factorEstres * factorEnfermedad * factorCiclo * factorEjercicio
        val factorTotalAplicado = factorTotalRaw.coerceIn(FACTOR_MIN, FACTOR_MAX)

        return ResultadoContextoInsulina(
            factorHora = factorHora,
            factorEstres = factorEstres,
            factorEnfermedad = factorEnfermedad,
            factorCiclo = factorCiclo,
            factorEjercicio = factorEjercicio,
            factorTotalRaw = factorTotalRaw,
            factorTotalAplicado = factorTotalAplicado,
            factorCapado = factorTotalRaw != factorTotalAplicado
        )
    }

    fun applyFactorToDoses(
        unidadesComida: Float,
        unidadesCorreccion: Float,
        factorTotalAplicado: Float
    ): ResultadoDosisContextual {
        val totalSinCorreccion = roundToHalf((unidadesComida * factorTotalAplicado).coerceAtLeast(0f))
        val totalConCorreccion = roundToHalf(
            ((unidadesComida + unidadesCorreccion) * factorTotalAplicado).coerceAtLeast(0f)
        )
        return ResultadoDosisContextual(
            totalConCorreccion = totalConCorreccion,
            totalSinCorreccion = totalSinCorreccion
        )
    }

    fun roundToHalf(value: Float): Float {
        return kotlin.math.round(value * 2f) / 2f
    }

    fun franjaLabel(franja: FranjaHoraria): String = when (franja) {
        FranjaHoraria.MADRUGADA -> "Madrugada"
        FranjaHoraria.MANANA -> "Mañana"
        FranjaHoraria.TARDE -> "Tarde"
        FranjaHoraria.NOCHE -> "Noche"
    }

    fun estresLabel(nivel: NivelEstres): String = when (nivel) {
        NivelEstres.NINGUNO -> "Ninguno"
        NivelEstres.LEVE -> "Leve"
        NivelEstres.MODERADO -> "Moderado"
        NivelEstres.ALTO -> "Alto"
    }

    fun enfermedadLabel(nivel: NivelEnfermedad): String = when (nivel) {
        NivelEnfermedad.NINGUNA -> "Ninguna"
        NivelEnfermedad.LEVE -> "Leve"
        NivelEnfermedad.MODERADA -> "Moderada"
        NivelEnfermedad.ALTA -> "Alta"
    }

    fun cicloLabel(fase: FaseCicloHormonal): String = when (fase) {
        FaseCicloHormonal.NO_APLICAR -> "No aplicar"
        FaseCicloHormonal.MENSTRUACION -> "Menstruación"
        FaseCicloHormonal.FOLICULAR -> "Folicular"
        FaseCicloHormonal.OVULACION -> "Ovulación"
        FaseCicloHormonal.LUTEA -> "Lútea"
    }

    fun ejercicioLabel(nivel: NivelEjercicio): String = when (nivel) {
        NivelEjercicio.NINGUNO -> "Ninguno"
        NivelEjercicio.SUAVE -> "Suave"
        NivelEjercicio.MODERADO -> "Moderado"
        NivelEjercicio.INTENSO -> "Intenso"
    }
}
