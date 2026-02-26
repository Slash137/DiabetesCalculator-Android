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
class Migration19To20Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        requireNotNull(AppDatabase::class.java.canonicalName),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate19To20_addsLibreviewSchemaAndDefaults() {
        helper.createDatabase(TEST_DB, 19).apply {
            execSQL(
                """
                INSERT INTO usuario_profile (
                    id,
                    nombre,
                    gramosPorRacion,
                    ratioInsulina,
                    aplicarCorreccionPorDefecto,
                    recordatorio2hActivo,
                    nightscoutSyncRegistrosActivo,
                    nightscoutLinkOffsetMinutes,
                    nightscoutLinkOffsetUnits,
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
                    1,
                    15,
                    0.5,
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
                    factorContextoCapado,
                    origenRegistro
                ) VALUES (
                    1,
                    30.0,
                    3.0,
                    3.0,
                    1234567890,
                    'pending',
                    0,
                    'LOCAL'
                )
                """.trimIndent()
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 20, true, AppDatabase.MIGRATION_19_20)

        db.query(
            """
            SELECT libreviewSyncActivo, libreviewRegionOverride, libreviewBackfillDoneAt
            FROM usuario_profile
            WHERE id = 1
            """.trimIndent()
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
            assertTrue(cursor.isNull(1))
            assertTrue(cursor.isNull(2))
        }

        db.query(
            """
            SELECT
                libreviewCarbsRecordNumber,
                libreviewInsulinRecordNumber,
                libreviewCarbsPayloadHash,
                libreviewInsulinPayloadHash,
                libreviewReconciliadoAt
            FROM registro_comida
            WHERE id = 1
            """.trimIndent()
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
            assertTrue(cursor.isNull(1))
            assertTrue(cursor.isNull(2))
            assertTrue(cursor.isNull(3))
            assertTrue(cursor.isNull(4))
        }

        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='registro_libreview_sync'")
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("registro_libreview_sync", cursor.getString(0))
            }
    }

    companion object {
        private const val TEST_DB = "migration-test-19-20"
    }
}
