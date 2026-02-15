package com.diabetes.calculator.data.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration15To16Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        requireNotNull(AppDatabase::class.java.canonicalName),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate15To16_addsNightscoutSyncSchemaWithDefaults() {
        helper.createDatabase(TEST_DB, 15).apply {
            execSQL(
                """
                INSERT INTO usuario_profile (
                    id,
                    nombre,
                    gramosPorRacion,
                    ratioInsulina,
                    aplicarCorreccionPorDefecto,
                    recordatorio2hActivo,
                    factorHoraMadrugada,
                    factorHoraManana,
                    factorHoraTarde,
                    factorHoraNoche,
                    factorEstresLeve,
                    factorEstresModerado,
                    factorEstresAlto,
                    factorEnfermedadLeve,
                    factorEnfermedadModerada,
                    factorEnfermedadAlta,
                    cicloHormonalActivo,
                    factorCicloMenstruacion,
                    factorCicloFolicular,
                    factorCicloOvulacion,
                    factorCicloLutea,
                    factorEjercicioSuave,
                    factorEjercicioModerado,
                    factorEjercicioIntenso,
                    fechaCreacion
                ) VALUES (
                    1,
                    'Test',
                    10.0,
                    1.0,
                    1,
                    0,
                    1.0,
                    1.0,
                    1.0,
                    1.0,
                    1.1,
                    1.2,
                    1.3,
                    1.1,
                    1.2,
                    1.3,
                    0,
                    0.95,
                    1.0,
                    1.05,
                    1.15,
                    0.9,
                    0.8,
                    0.7,
                    1234567890
                )
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO registro_comida (
                    id,
                    hidratosTotales,
                    racionesCalculadas,
                    unidadesInsulina,
                    fecha,
                    dosisEstado,
                    factorContextoCapado
                ) VALUES (
                    1,
                    30.0,
                    3.0,
                    3.0,
                    1234567890,
                    'pending',
                    0
                )
                """.trimIndent()
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 16, true, AppDatabase.MIGRATION_15_16)

        db.query("SELECT nightscoutSyncRegistrosActivo, nightscoutSyncBackfillDoneAt FROM usuario_profile WHERE id = 1")
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
                assertTrue(cursor.isNull(1))
            }

        db.query(
            "SELECT origenRegistro, nightscoutTreatmentId, unidadesInsulinaRemota, nightscoutReconciliadoAt, nightscoutSyncDcid FROM registro_comida WHERE id = 1"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("LOCAL", cursor.getString(0))
            assertTrue(cursor.isNull(1))
            assertTrue(cursor.isNull(2))
            assertTrue(cursor.isNull(3))
            assertTrue(cursor.isNull(4))
        }

        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='registro_nightscout_sync'")
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("registro_nightscout_sync", cursor.getString(0))
            }

        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='nightscout_treatment_tombstone'")
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("nightscout_treatment_tombstone", cursor.getString(0))
            }
    }

    companion object {
        private const val TEST_DB = "migration-test-15-16"
    }
}
