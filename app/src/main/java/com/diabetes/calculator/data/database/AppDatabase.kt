package com.diabetes.calculator.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.diabetes.calculator.BuildConfig
import com.diabetes.calculator.data.dao.AlimentoDao
import com.diabetes.calculator.data.dao.PlantillaDao
import com.diabetes.calculator.data.dao.PendingGlucoseDao
import com.diabetes.calculator.data.dao.RegistroComidaDao
import com.diabetes.calculator.data.dao.UsuarioProfileDao
import com.diabetes.calculator.data.entity.Alimento
import com.diabetes.calculator.data.entity.AlimentoEnRegistro
import com.diabetes.calculator.data.entity.PendingGlucose
import com.diabetes.calculator.data.entity.PlantillaComida
import com.diabetes.calculator.data.entity.PlantillaItem
import com.diabetes.calculator.data.entity.RegistroComida
import com.diabetes.calculator.data.entity.UsuarioProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Base de datos Room principal de la aplicacion.
 * Version 9 con fallback destructivo solo en debug.
 */
@Database(
    entities = [
        UsuarioProfile::class,
        Alimento::class,
        RegistroComida::class,
        AlimentoEnRegistro::class,
        PlantillaComida::class,
        PlantillaItem::class,
        PendingGlucose::class
    ],
    version = 9,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    
    abstract fun usuarioProfileDao(): UsuarioProfileDao
    abstract fun alimentoDao(): AlimentoDao
    abstract fun registroComidaDao(): RegistroComidaDao
    abstract fun plantillaDao(): PlantillaDao
    abstract fun pendingGlucoseDao(): PendingGlucoseDao
    
    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null
        
        /**
         * Obtiene la instancia singleton de la base de datos.
         * Incluye callback para poblar datos iniciales.
         */
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val builder = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "diabetes_calculator_db"
                )
                builder.addMigrations(
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9
                )
                if (BuildConfig.DEBUG) {
                    builder.fallbackToDestructiveMigration(dropAllTables = true)
                }
                val instance = builder.build()
                INSTANCE = instance
                instance
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE alimentos ADD COLUMN nota TEXT")
                database.execSQL("ALTER TABLE registro_comida ADD COLUMN glucosaAntesMgdl INTEGER")
                database.execSQL("ALTER TABLE registro_comida ADD COLUMN glucosaDespues2hMgdl INTEGER")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE registro_comida ADD COLUMN ratioInsulinaHc REAL")
                database.execSQL("""
                    UPDATE registro_comida
                    SET ratioInsulinaHc = CASE
                        WHEN hidratosTotales > 0 THEN unidadesInsulina / hidratosTotales
                        ELSE NULL
                    END
                """.trimIndent())
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE usuario_profile ADD COLUMN objetivoHidratosDia REAL")
                database.execSQL("ALTER TABLE usuario_profile ADD COLUMN objetivoRacionesDia REAL")
                database.execSQL("ALTER TABLE usuario_profile ADD COLUMN objetivoInsulinaDia REAL")
                database.execSQL("ALTER TABLE usuario_profile ADD COLUMN recordatorio2hActivo INTEGER NOT NULL DEFAULT 0")

                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS plantilla_comida (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        nombre TEXT NOT NULL,
                        fechaCreacion INTEGER NOT NULL
                    )
                """.trimIndent())

                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS plantilla_item (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        plantillaId INTEGER NOT NULL,
                        alimentoId INTEGER NOT NULL,
                        gramos REAL NOT NULL,
                        FOREIGN KEY(plantillaId) REFERENCES plantilla_comida(id) ON DELETE CASCADE,
                        FOREIGN KEY(alimentoId) REFERENCES alimentos(id) ON DELETE CASCADE
                    )
                """.trimIndent())

                database.execSQL("CREATE INDEX IF NOT EXISTS index_plantilla_item_plantillaId ON plantilla_item(plantillaId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_plantilla_item_alimentoId ON plantilla_item(alimentoId)")

                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS pending_glucose (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        registroId INTEGER NOT NULL,
                        tipo TEXT NOT NULL,
                        targetMillis INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        attempts INTEGER NOT NULL,
                        lastError TEXT
                    )
                """.trimIndent())

                database.execSQL("CREATE INDEX IF NOT EXISTS index_pending_glucose_registroId ON pending_glucose(registroId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_pending_glucose_tipo ON pending_glucose(tipo)")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE registro_comida ADD COLUMN dosisEstado TEXT NOT NULL DEFAULT 'pending'"
                )
                database.execSQL(
                    "ALTER TABLE registro_comida ADD COLUMN dosisConfirmadaAt INTEGER"
                )
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS alimento_en_registro_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        registroId INTEGER NOT NULL,
                        alimentoId INTEGER NOT NULL,
                        gramosConsumidos REAL NOT NULL,
                        hidratosCalculados REAL NOT NULL,
                        FOREIGN KEY(registroId) REFERENCES registro_comida(id) ON DELETE CASCADE,
                        FOREIGN KEY(alimentoId) REFERENCES alimentos(id) ON DELETE NO ACTION
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    INSERT INTO alimento_en_registro_new (id, registroId, alimentoId, gramosConsumidos, hidratosCalculados)
                    SELECT id, registroId, alimentoId, gramosConsumidos, hidratosCalculados
                    FROM alimento_en_registro
                    """.trimIndent()
                )
                database.execSQL("DROP TABLE alimento_en_registro")
                database.execSQL("ALTER TABLE alimento_en_registro_new RENAME TO alimento_en_registro")
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_alimento_en_registro_registroId ON alimento_en_registro(registroId)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_alimento_en_registro_alimentoId ON alimento_en_registro(alimentoId)"
                )
            }
        }
    }
    
    /**
     * Inserta los alimentos iniciales (seed data).
     */
    suspend fun populateDatabase() {
        val alimentoDao = alimentoDao()
        val alimentosIniciales = listOf(
            Alimento(nombre = "Arroz blanco hervido", hidratosPor100g = 25.0f, fuente = "librito", nota = "peso cocido aprox. (peso crudo)"),
            Alimento(nombre = "Arroz integral hervido", hidratosPor100g = 25.0f, fuente = "librito"),
            Alimento(nombre = "Arroz a la cubana", hidratosPor100g = 16.0f, fuente = "librito", nota = "peso cocido aprox. (peso crudo)"),
            Alimento(nombre = "Pan blanco", hidratosPor100g = 50.0f, fuente = "librito", nota = "barra 1/4"),
            Alimento(nombre = "Pan integral", hidratosPor100g = 42.0f, fuente = "librito"),
            Alimento(nombre = "Bastoncitos de pan", hidratosPor100g = 66.7f, fuente = "librito"),
            Alimento(nombre = "Tostada pan blanco", hidratosPor100g = 66.7f, fuente = "librito"),
            Alimento(nombre = "Pan de molde", hidratosPor100g = 48.0f, fuente = "librito", nota = "rebanada pequeña"),
            Alimento(nombre = "Pan de pueblo", hidratosPor100g = 50.0f, fuente = "librito"),
            Alimento(nombre = "Tortita arroz integral", hidratosPor100g = 86.7f, fuente = "librito"),
            Alimento(nombre = "Krisprolls", hidratosPor100g = 68.0f, fuente = "librito", nota = "dato de tabla biscote"),
            Alimento(nombre = "Cereales de desayuno", hidratosPor100g = 78.0f, fuente = "librito"),
            Alimento(nombre = "Copos de avena", hidratosPor100g = 60.0f, fuente = "librito"),
            Alimento(nombre = "Cereales Special K", hidratosPor100g = 66.7f, fuente = "librito", nota = "vaso 500 ml (1/4 altura)"),
            Alimento(nombre = "Cereales Choco Crispies", hidratosPor100g = 80.0f, fuente = "librito", nota = "vaso 500 ml (1/4 altura)"),
            Alimento(nombre = "Cereales Chocos/Coco Pops", hidratosPor100g = 80.0f, fuente = "librito", nota = "vaso 500 ml (1/4 altura)"),
            Alimento(nombre = "Muesli", hidratosPor100g = 66.7f, fuente = "librito", nota = "vaso 500 ml (1/4 altura)"),
            Alimento(nombre = "Galletas María", hidratosPor100g = 66.7f, fuente = "librito"),
            Alimento(nombre = "Galletas rellenas de chocolate", hidratosPor100g = 66.7f, fuente = "librito"),
            Alimento(nombre = "Milka Choco Biscuit", hidratosPor100g = 64.0f, fuente = "librito"),
            Alimento(nombre = "Biscote canapé", hidratosPor100g = 70.0f, fuente = "librito"),
            Alimento(nombre = "Biscote integral", hidratosPor100g = 65.6f, fuente = "librito"),
            Alimento(nombre = "Biscote mini tosta", hidratosPor100g = 75.0f, fuente = "librito", nota = "errata del librito: HC 1,5 g (no 7,5 g)"),
            Alimento(nombre = "Biscote chapada", hidratosPor100g = 68.3f, fuente = "librito"),
            Alimento(nombre = "Biscote tostada canapé", hidratosPor100g = 72.7f, fuente = "librito"),
            Alimento(nombre = "Biscote blanco", hidratosPor100g = 73.3f, fuente = "librito"),
            Alimento(nombre = "Pasta cocida (espagueti)", hidratosPor100g = 20.0f, fuente = "librito", nota = "peso cocido aprox. (peso crudo)"),
            Alimento(nombre = "Pasta integral cocida", hidratosPor100g = 23.0f, fuente = "librito"),
            Alimento(nombre = "Macarrones hervidos", hidratosPor100g = 20.0f, fuente = "librito", nota = "peso cocido aprox. (peso crudo)"),
            Alimento(nombre = "Macarrones con salsa de tomate", hidratosPor100g = 17.3f, fuente = "librito", nota = "peso cocido aprox. (peso crudo)"),
            Alimento(nombre = "Espaguetis con salsa de tomate", hidratosPor100g = 17.3f, fuente = "librito", nota = "peso cocido aprox. (peso crudo)"),
            Alimento(nombre = "Patata cocida", hidratosPor100g = 20.0f, fuente = "librito", nota = "peso cocido y crudo"),
            Alimento(nombre = "Patata asada", hidratosPor100g = 21.0f, fuente = "librito"),
            Alimento(nombre = "Puré de patata", hidratosPor100g = 14.0f, fuente = "librito"),
            Alimento(nombre = "Boniato cocido", hidratosPor100g = 20.0f, fuente = "librito"),
            Alimento(nombre = "Patatas fritas", hidratosPor100g = 33.3f, fuente = "librito"),
            Alimento(nombre = "Patatas chips", hidratosPor100g = 50.0f, fuente = "librito", nota = "bolsa pequeña"),
            Alimento(nombre = "Lentejas cocidas", hidratosPor100g = 20.0f, fuente = "librito", nota = "peso cocido aprox. (peso crudo)"),
            Alimento(nombre = "Garbanzos cocidos", hidratosPor100g = 20.0f, fuente = "librito", nota = "peso cocido aprox. (peso crudo)"),
            Alimento(nombre = "Judías blancas cocidas", hidratosPor100g = 20.0f, fuente = "librito", nota = "peso cocido aprox. (peso crudo)"),
            Alimento(nombre = "Guisantes hervidos", hidratosPor100g = 10.0f, fuente = "librito", nota = "peso cocido, congelado, crudo y en conserva"),
            Alimento(nombre = "Alcachofas", hidratosPor100g = 3.3f, fuente = "librito"),
            Alimento(nombre = "Coliflor", hidratosPor100g = 3.3f, fuente = "librito"),
            Alimento(nombre = "Ensalada verde", hidratosPor100g = 3.3f, fuente = "librito"),
            Alimento(nombre = "Ensalada mixta", hidratosPor100g = 6.7f, fuente = "librito"),
            Alimento(nombre = "Judía verde", hidratosPor100g = 5.0f, fuente = "librito"),
            Alimento(nombre = "Tomate", hidratosPor100g = 3.3f, fuente = "librito"),
            Alimento(nombre = "Manzana", hidratosPor100g = 10.0f, fuente = "librito", nota = "peso neto sin piel"),
            Alimento(nombre = "Plátano", hidratosPor100g = 20.0f, fuente = "librito", nota = "peso neto sin piel"),
            Alimento(nombre = "Naranja", hidratosPor100g = 10.0f, fuente = "librito", nota = "peso neto sin piel"),
            Alimento(nombre = "Pera", hidratosPor100g = 10.0f, fuente = "librito", nota = "peso neto sin piel"),
            Alimento(nombre = "Uvas", hidratosPor100g = 20.0f, fuente = "librito", nota = "peso neto sin piel"),
            Alimento(nombre = "Sandía", hidratosPor100g = 6.7f, fuente = "librito", nota = "peso neto sin piel"),
            Alimento(nombre = "Melón", hidratosPor100g = 5.0f, fuente = "librito", nota = "peso neto sin piel"),
            Alimento(nombre = "Fresas", hidratosPor100g = 6.7f, fuente = "librito", nota = "peso neto sin piel"),
            Alimento(nombre = "Kiwi", hidratosPor100g = 10.0f, fuente = "librito", nota = "peso neto sin piel"),
            Alimento(nombre = "Melocotón", hidratosPor100g = 7.7f, fuente = "librito", nota = "peso neto sin piel"),
            Alimento(nombre = "Piña", hidratosPor100g = 10.0f, fuente = "librito", nota = "peso neto sin piel"),
            Alimento(nombre = "Higos", hidratosPor100g = 12.5f, fuente = "librito", nota = "peso neto sin piel"),
            Alimento(nombre = "Mandarina", hidratosPor100g = 10.0f, fuente = "librito", nota = "peso neto sin piel"),
            Alimento(nombre = "Macedonia", hidratosPor100g = 20.0f, fuente = "librito"),
            Alimento(nombre = "Cerezas", hidratosPor100g = 13.0f, fuente = "librito"),
            Alimento(nombre = "Mango", hidratosPor100g = 15.0f, fuente = "librito"),
            Alimento(nombre = "Leche entera", hidratosPor100g = 4.7f, fuente = "librito"),
            Alimento(nombre = "Leche desnatada", hidratosPor100g = 5.0f, fuente = "librito"),
            Alimento(nombre = "Leche semidesnatada", hidratosPor100g = 4.6f, fuente = "librito"),
            Alimento(nombre = "Yogur natural", hidratosPor100g = 4.0f, fuente = "librito", nota = "entero sin azúcar"),
            Alimento(nombre = "Yogur de sabores", hidratosPor100g = 12.0f, fuente = "librito", nota = "entero"),
            Alimento(nombre = "Yogur natural azucarado", hidratosPor100g = 12.0f, fuente = "librito"),
            Alimento(nombre = "Yogur desnatado natural", hidratosPor100g = 4.4f, fuente = "librito"),
            Alimento(nombre = "Yogur desnatado con frutas", hidratosPor100g = 8.6f, fuente = "librito"),
            Alimento(nombre = "Queso fresco natural", hidratosPor100g = 3.3f, fuente = "librito"),
            Alimento(nombre = "Quesito en porciones", hidratosPor100g = 5.5f, fuente = "librito"),
            Alimento(nombre = "Queso manchego semi", hidratosPor100g = 1.5f, fuente = "librito"),
            Alimento(nombre = "Queso en lonchas", hidratosPor100g = 3.8f, fuente = "librito"),
            Alimento(nombre = "Flan de huevo", hidratosPor100g = 20.0f, fuente = "librito"),
            Alimento(nombre = "Natillas", hidratosPor100g = 20.0f, fuente = "librito"),
            Alimento(nombre = "Actimel", hidratosPor100g = 10.6f, fuente = "librito"),
            Alimento(nombre = "Petit suisse", hidratosPor100g = 18.2f, fuente = "librito"),
            Alimento(nombre = "Almendras", hidratosPor100g = 6.7f, fuente = "librito"),
            Alimento(nombre = "Avellanas", hidratosPor100g = 7.1f, fuente = "librito"),
            Alimento(nombre = "Nueces", hidratosPor100g = 3.3f, fuente = "librito"),
            Alimento(nombre = "Aceitunas rellenas de anchoas", hidratosPor100g = 0.1f, fuente = "librito"),
            Alimento(nombre = "Quicos (maíz frito)", hidratosPor100g = 66.7f, fuente = "librito"),
            Alimento(nombre = "Palomitas", hidratosPor100g = 66.7f, fuente = "librito"),
            Alimento(nombre = "Paella mixta", hidratosPor100g = 16.0f, fuente = "librito"),
            Alimento(nombre = "Tortilla de patatas casera", hidratosPor100g = 10.0f, fuente = "librito"),
            Alimento(nombre = "Croquetas", hidratosPor100g = 21.1f, fuente = "librito"),
            Alimento(nombre = "Empanadillas", hidratosPor100g = 25.0f, fuente = "librito"),
            Alimento(nombre = "Calamares romana", hidratosPor100g = 8.3f, fuente = "librito"),
            Alimento(nombre = "Pizza jamón y queso", hidratosPor100g = 25.0f, fuente = "librito", nota = "pizza congelada"),
            Alimento(nombre = "Hamburguesa fast food", hidratosPor100g = 17.9f, fuente = "librito"),
            Alimento(nombre = "Ketchup", hidratosPor100g = 25.0f, fuente = "librito"),
            Alimento(nombre = "Canelones con bechamel", hidratosPor100g = 10.8f, fuente = "librito", nota = "3 unidades"),
            Alimento(nombre = "Raviolis cocidos", hidratosPor100g = 26.0f, fuente = "librito"),
            Alimento(nombre = "Sushi roll", hidratosPor100g = 28.0f, fuente = "librito", nota = "pieza pequeña/grande"),
            Alimento(nombre = "Doner kebab", hidratosPor100g = 17.1f, fuente = "librito", nota = "pan grueso pequeño"),
            Alimento(nombre = "Durum kebab", hidratosPor100g = 10.0f, fuente = "librito", nota = "masa fina"),
            Alimento(nombre = "Salmorejo", hidratosPor100g = 9.4f, fuente = "librito", nota = "peso cocido aprox."),
            Alimento(nombre = "Chiretas rebozadas", hidratosPor100g = 21.1f, fuente = "librito", nota = "peso cocido aprox."),
            Alimento(nombre = "Fabada asturiana", hidratosPor100g = 10.1f, fuente = "librito", nota = "peso cocido aprox."),
            Alimento(nombre = "Borrida de ratjada", hidratosPor100g = 9.4f, fuente = "librito", nota = "peso cocido aprox."),
            Alimento(nombre = "Sancocho", hidratosPor100g = 10.6f, fuente = "librito", nota = "peso cocido aprox."),
            Alimento(nombre = "Quesada pasiega", hidratosPor100g = 42.5f, fuente = "librito", nota = "peso cocido aprox."),
            Alimento(nombre = "Corbatas de Unquera", hidratosPor100g = 54.0f, fuente = "librito", nota = "peso cocido aprox."),
            Alimento(nombre = "Morteruelo", hidratosPor100g = 13.4f, fuente = "librito", nota = "peso cocido aprox."),
            Alimento(nombre = "Sobaillos", hidratosPor100g = 55.8f, fuente = "librito", nota = "peso cocido aprox."),
            Alimento(nombre = "Crema catalana", hidratosPor100g = 15.8f, fuente = "librito", nota = "peso cocido aprox."),
            Alimento(nombre = "Migas extremeñas", hidratosPor100g = 35.7f, fuente = "librito", nota = "peso cocido aprox."),
            Alimento(nombre = "Empanada gallega", hidratosPor100g = 38.9f, fuente = "librito", nota = "peso cocido aprox."),
            Alimento(nombre = "Patatas a la riojana", hidratosPor100g = 13.2f, fuente = "librito", nota = "peso cocido aprox."),
            Alimento(nombre = "Cocido madrileño", hidratosPor100g = 15.9f, fuente = "librito", nota = "peso cocido aprox."),
            Alimento(nombre = "Michirones", hidratosPor100g = 18.2f, fuente = "librito", nota = "peso cocido aprox."),
            Alimento(nombre = "Txangurri", hidratosPor100g = 38.4f, fuente = "librito", nota = "peso cocido aprox."),
            Alimento(nombre = "Marmitako", hidratosPor100g = 8.2f, fuente = "librito", nota = "peso cocido aprox."),
            Alimento(nombre = "Fideuá", hidratosPor100g = 15.0f, fuente = "librito", nota = "peso cocido aprox."),
            Alimento(nombre = "Couscous", hidratosPor100g = 16.7f, fuente = "librito", nota = "peso cocido aprox."),
            Alimento(nombre = "Copa chocolate y nata", hidratosPor100g = 17.4f, fuente = "librito"),
            Alimento(nombre = "Gelatina", hidratosPor100g = 15.2f, fuente = "librito"),
            Alimento(nombre = "Ensaimada", hidratosPor100g = 40.0f, fuente = "librito"),
            Alimento(nombre = "Cruasán", hidratosPor100g = 57.4f, fuente = "librito"),
            Alimento(nombre = "Donut", hidratosPor100g = 43.1f, fuente = "librito"),
            Alimento(nombre = "Magdalena", hidratosPor100g = 40.0f, fuente = "librito"),
            Alimento(nombre = "Helado (vainilla)", hidratosPor100g = 21.9f, fuente = "librito"),
            Alimento(nombre = "Pastel de chocolate", hidratosPor100g = 42.9f, fuente = "librito"),
            Alimento(nombre = "Tarta de Santiago", hidratosPor100g = 46.7f, fuente = "librito"),
            Alimento(nombre = "Tarta de manzana", hidratosPor100g = 37.4f, fuente = "librito"),
            Alimento(nombre = "Chocolate con leche", hidratosPor100g = 50.0f, fuente = "librito"),
            Alimento(nombre = "Mermelada (con azúcar)", hidratosPor100g = 68.0f, fuente = "librito"),
            Alimento(nombre = "Mermelada (con edulcorantes)", hidratosPor100g = 6.0f, fuente = "librito"),
            Alimento(nombre = "Refresco cola", hidratosPor100g = 11.0f, fuente = "librito"),
            Alimento(nombre = "Zumo de naranja", hidratosPor100g = 10.0f, fuente = "librito"),
            Alimento(nombre = "Cerveza", hidratosPor100g = 4.5f, fuente = "librito", nota = "con y sin alcohol"),
            Alimento(nombre = "Cava brut", hidratosPor100g = 1.5f, fuente = "librito"),
            Alimento(nombre = "Vino tinto", hidratosPor100g = 0.2f, fuente = "librito", nota = "vino blanco/rosado ~15% más HC"),
            Alimento(nombre = "Carajillo", hidratosPor100g = 0.0f, fuente = "librito"),
            Alimento(nombre = "Coñac", hidratosPor100g = 0.0f, fuente = "librito"),
            Alimento(nombre = "Licor de frutas", hidratosPor100g = 35.0f, fuente = "librito"),
            Alimento(nombre = "Tofu", hidratosPor100g = 3.3f, fuente = "librito"),
            Alimento(nombre = "Seitán", hidratosPor100g = 2.8f, fuente = "librito"),
            Alimento(nombre = "Hamburguesa vegetal", hidratosPor100g = 15.5f, fuente = "librito"),
        )
        
        if (alimentoDao.getCount() == 0) {
            alimentoDao.insertAll(alimentosIniciales)
            return
        }
        
        alimentosIniciales.forEach { alimento ->
            val updated = alimentoDao.updateByNombre(
                nombre = alimento.nombre,
                hidratos = alimento.hidratosPor100g,
                fuente = alimento.fuente,
                nota = alimento.nota
            )
            if (updated == 0) {
                alimentoDao.insert(alimento)
            }
        }
    }
}
