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
class Migration16To17Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        requireNotNull(AppDatabase::class.java.canonicalName),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate16To17_addsFoodMeasurementColumnsAndBackfills() {
        helper.createDatabase(TEST_DB, 16).apply {
            execSQL(
                """
                INSERT INTO alimentos (id, nombre, hidratosPor100g, fuente, nota)
                VALUES (1, 'Leche', 4.7, 'manual', 'legacy')
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
                    20.0,
                    2.0,
                    2.0,
                    1234567890,
                    'pending',
                    0,
                    'LOCAL'
                )
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO alimento_en_registro (
                    id,
                    registroId,
                    alimentoId,
                    gramosConsumidos,
                    hidratosCalculados
                ) VALUES (
                    1,
                    1,
                    1,
                    150.0,
                    7.05
                )
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO plantilla_comida (id, nombre, fechaCreacion)
                VALUES (1, 'Plantilla test', 1234567890)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO plantilla_item (id, plantillaId, alimentoId, gramos)
                VALUES (1, 1, 1, 120.0)
                """.trimIndent()
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 17, true, AppDatabase.MIGRATION_16_17)

        db.query(
            "SELECT tipoMedicionPrincipal, estadoFisico, hidratosPor100ml, unidadNombre, gramosPorUnidad, mlPorUnidad FROM alimentos WHERE id = 1"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("GRAMOS", cursor.getString(0))
            assertEquals("SOLIDO", cursor.getString(1))
            assertTrue(cursor.isNull(2))
            assertTrue(cursor.isNull(3))
            assertTrue(cursor.isNull(4))
            assertTrue(cursor.isNull(5))
        }

        db.query(
            "SELECT gramosConsumidos, cantidadConsumida, unidadConsumida FROM alimento_en_registro WHERE id = 1"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(150.0f, cursor.getFloat(0))
            assertEquals(150.0f, cursor.getFloat(1))
            assertEquals("g", cursor.getString(2))
        }

        db.query(
            "SELECT gramos, cantidad, unidad FROM plantilla_item WHERE id = 1"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(120.0f, cursor.getFloat(0))
            assertEquals(120.0f, cursor.getFloat(1))
            assertEquals("g", cursor.getString(2))
        }
    }

    companion object {
        private const val TEST_DB = "migration-test-16-17"
    }
}
