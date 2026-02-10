package com.diabetes.calculator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.diabetes.calculator.ui.navigation.DiabetesNavGraph
import com.diabetes.calculator.ui.theme.DiabetesCalculatorTheme

/**
 * Activity principal de la aplicación.
 * Usa Jetpack Compose para toda la UI.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Obtener la instancia de la aplicación para acceder a los repositorios
        val app = application as DiabetesApp
        
        setContent {
            DiabetesCalculatorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DiabetesNavGraph(app = app)
                }
            }
        }
    }
}
