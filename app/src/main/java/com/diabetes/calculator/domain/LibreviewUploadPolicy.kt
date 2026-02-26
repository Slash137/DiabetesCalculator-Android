package com.diabetes.calculator.domain

import com.diabetes.calculator.data.entity.EstadoDosis
import com.diabetes.calculator.data.entity.OrigenRegistro
import com.diabetes.calculator.data.entity.RegistroComida

object LibreviewUploadPolicy {
    fun isNovoPenNfcRegistro(registro: RegistroComida): Boolean {
        val dcid = registro.nightscoutSyncDcid?.trim().orEmpty()
        if (dcid.startsWith("nfc-")) return true
        val notes = registro.notas?.trim().orEmpty()
        return notes.contains("[NovoPen NFC]", ignoreCase = true)
    }

    fun isLocalRegistroEligible(registro: RegistroComida): Boolean {
        return OrigenRegistro.fromValue(registro.origenRegistro) == OrigenRegistro.LOCAL
    }

    fun shouldUploadCarbs(registro: RegistroComida): Boolean {
        if (!isLocalRegistroEligible(registro)) return false
        return registro.hidratosTotales.isFinite() && registro.hidratosTotales > 0f
    }

    fun shouldRepairUploadCarbs(registro: RegistroComida): Boolean {
        if (!isLocalRegistroEligible(registro)) return false
        return registro.hidratosTotales.isFinite() && registro.hidratosTotales > 0f
    }

    fun shouldUploadAppliedInsulin(registro: RegistroComida): Boolean {
        if (!isLocalRegistroEligible(registro)) return false
        if (EstadoDosis.fromValue(registro.dosisEstado) != EstadoDosis.APLICADA) return false
        val units = registro.unidadesInsulina
        return units.isFinite() && units > 0f
    }

    fun shouldRepairUploadInsulin(registro: RegistroComida): Boolean {
        if (!isLocalRegistroEligible(registro)) return false
        if (EstadoDosis.fromValue(registro.dosisEstado) != EstadoDosis.APLICADA) return false
        val units = registro.unidadesInsulina
        return units.isFinite() && units > 0f
    }

    fun shouldManualCatchupUploadInsulin(registro: RegistroComida): Boolean {
        if (!isLocalRegistroEligible(registro)) return false
        if (EstadoDosis.fromValue(registro.dosisEstado) == EstadoDosis.OMITIDA) return false
        val units = registro.unidadesInsulina
        return units.isFinite() && units > 0f
    }

    fun shouldUploadNfcInsulin(registro: RegistroComida): Boolean {
        if (!shouldUploadAppliedInsulin(registro)) return false
        if (!isNovoPenNfcRegistro(registro)) return false
        return true
    }
}
