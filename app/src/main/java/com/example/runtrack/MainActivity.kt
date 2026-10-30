
package com.example.runtrack

// Importaciones para acceder a los sensores físicos del dispositivo.
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

// Importaciones para administrar el ciclo de vida y los hilos de ejecución.
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread

// Importaciones para mostrar la hora y trabajar con los elementos visuales.
import android.text.format.DateFormat
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

// Importaciones relacionadas con la configuración de la ventana de Android.
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

// Importaciones para dar formato a los números y calcular diferencias.
import java.util.Locale
import kotlin.math.abs


// Clase principal de la aplicación RunTrack.
// AppCompatActivity permite administrar la pantalla y sus componentes.
class MainActivity : AppCompatActivity() {


    // ==========================================================
    // 1. DECLARACIÓN Y CONFIGURACIÓN DE LOS SENSORES
    // ==========================================================

    // SensorManager es el administrador que proporciona Android
    // para consultar, activar y desactivar los sensores del teléfono.
    private lateinit var sensorManager: SensorManager

    // Variables que almacenan las referencias al acelerómetro
    // y al giroscopio disponibles en el dispositivo.
    // El símbolo ? indica que pueden tener un valor nulo si
    // el teléfono no cuenta con alguno de estos sensores.
    private var acelerometro: Sensor? = null
    private var giroscopio: Sensor? = null


    // ==========================================================
    // 2. CONFIGURACIÓN DEL HILO SECUNDARIO
    // ==========================================================

    // HandlerThread crea un hilo de ejecución independiente
    // del hilo principal de la aplicación.
    // Se utiliza para recibir los eventos de los sensores sin
    // realizar todo ese trabajo directamente en la interfaz.
    private lateinit var sensorThread: HandlerThread

    // Handler permite enviar y procesar los eventos dentro
    // del hilo secundario que acabamos de crear.
    private lateinit var sensorHandler: Handler


    // ==========================================================
    // 3. DECLARACIÓN DE LOS ELEMENTOS DE LA INTERFAZ
    // ==========================================================

    // TextView que muestra el estado general del monitoreo
    // y la disponibilidad de cada sensor.
    private lateinit var tvEstadoGeneral: TextView
    private lateinit var tvEstadoAcelerometro: TextView
    private lateinit var tvEstadoGiroscopio: TextView

    // TextView destinados a mostrar los valores del acelerómetro.
    // Cada uno representa uno de los tres ejes de movimiento.
    private lateinit var tvAccelX: TextView
    private lateinit var tvAccelY: TextView
    private lateinit var tvAccelZ: TextView

    // TextView destinados a mostrar los valores del giroscopio.
    // Estos representan la velocidad de rotación en cada eje.
    private lateinit var tvGyroX: TextView
    private lateinit var tvGyroY: TextView
    private lateinit var tvGyroZ: TextView

    // Elementos para mostrar información general de las lecturas:
    // cantidad de eventos aceptados y hora de actualización.
    private lateinit var tvLecturasProcesadas: TextView
    private lateinit var tvUltimaActualizacion: TextView

    // Botones que permiten controlar el funcionamiento
    // del monitoreo de los sensores.
    private lateinit var btnIniciar: Button
    private lateinit var btnDetener: Button


    // ==========================================================
    // 4. VARIABLES PARA CONTROLAR EL MONITOREO
    // ==========================================================

    // Esta variable indica si la aplicación está recibiendo
    // y procesando lecturas de los sensores.
    //
    // @Volatile permite que tanto el hilo principal como
    // el hilo secundario puedan consultar el estado actualizado
    // de esta variable.
    @Volatile
    private var monitoreoActivo = false

    // Contador que registra los eventos que fueron aceptados
    // después de pasar los filtros de tiempo y de cambio.
    private var lecturasProcesadas = 0


    // ==========================================================
    // 5. VARIABLES PARA EL FILTRADO DE EVENTOS
    // ==========================================================

    // Almacenan los últimos valores aceptados de cada sensor.
    // Se utilizan para comparar las nuevas lecturas y detectar
    // si existe un cambio suficientemente significativo.
    //
    // Inicialmente son null porque todavía no se ha recibido
    // ninguna lectura durante el monitoreo.
    private var valoresAnterioresAccel: FloatArray? = null
    private var valoresAnterioresGyro: FloatArray? = null

    // Almacenan el momento en que fue aceptada la última lectura
    // de cada sensor. Los valores se expresan en nanosegundos,
    // porque se utiliza event.timestamp.
    private var ultimoTiempoAccel = 0L
    private var ultimoTiempoGyro = 0L


    // Configuración de los filtros utilizados por RunTrack.

    // Intervalo mínimo de 50 milisegundos entre lecturas aceptadas.
    // 50,000,000 nanosegundos equivalen a 50 milisegundos.
    // Esto permite evitar el procesamiento de eventos demasiado
    // frecuentes para la actualización de nuestra aplicación.
    private val intervaloMinimo = 50_000_000L

    // Cambio mínimo requerido para aceptar una nueva lectura
    // del acelerómetro.
    private val umbralAccel = 0.03f

    // Cambio mínimo requerido para aceptar una nueva lectura
    // del giroscopio.
    private val umbralGyro = 0.01f


    // ==========================================================
    // 6. LISTENER: RECEPCIÓN DE LOS EVENTOS DE LOS SENSORES
    // ==========================================================

    // SensorEventListener es la interfaz que permite recibir
    // notificaciones cuando los sensores generan nuevas lecturas.
    //
    // onSensorChanged() se ejecuta cada vez que llega un evento.
    // onAccuracyChanged() informa cambios en la precisión.
    private val sensorListener = object : SensorEventListener {


        // Este método recibe los datos del acelerómetro
        // o del giroscopio cuando se produce un nuevo evento.
        override fun onSensorChanged(event: SensorEvent) {


            // Si el monitoreo está detenido, ignoramos el evento.
            // Esto evita procesar datos cuando el usuario ya
            // presionó el botón de detener.
            if (!monitoreoActivo) return


            // Copiamos los valores del evento en un arreglo nuevo.
            // copyOf() permite conservar los datos recibidos,
            // sin depender del arreglo interno del SensorEvent.
            val valores = event.values.copyOf()

            // Identificamos qué sensor generó el evento.
            // Su tipo permite distinguir entre acelerómetro y giroscopio.
            val tipoSensor = event.sensor.type


            // Variables auxiliares para aplicar el mismo procedimiento
            // de filtrado a los dos sensores.
            val tiempoAnterior: Long
            val valoresAnteriores: FloatArray?
            val umbral: Float


            // Identificamos el sensor que produjo la lectura.
            // Según el tipo, seleccionamos sus datos anteriores,
            // el tiempo de la última lectura y el umbral correspondiente.
            when (tipoSensor) {

                // Si el evento pertenece al acelerómetro,
                // utilizamos sus propios registros anteriores.
                Sensor.TYPE_ACCELEROMETER -> {
                    tiempoAnterior = ultimoTiempoAccel
                    valoresAnteriores = valoresAnterioresAccel
                    umbral = umbralAccel
                }

                // Si el evento pertenece al giroscopio,
                // utilizamos sus propios registros anteriores.
                Sensor.TYPE_GYROSCOPE -> {
                    tiempoAnterior = ultimoTiempoGyro
                    valoresAnteriores = valoresAnterioresGyro
                    umbral = umbralGyro
                }

                // Si se recibe un tipo de sensor diferente,
                // no se procesa y se termina este evento.
                else -> return
            }


            // --------------------------------------------------
            // FILTRO 1. CONTROL DEL INTERVALO DE TIEMPO
            // --------------------------------------------------

            // event.timestamp proporciona el momento exacto
            // en que se produjo el evento, expresado en nanosegundos.
            //
            // Restamos el tiempo de la última lectura aceptada.
            // Si todavía no han transcurrido 50 milisegundos,
            // descartamos el evento para evitar procesarlo
            // con demasiada frecuencia.
            if (event.timestamp - tiempoAnterior < intervaloMinimo) {
                return
            }


            // --------------------------------------------------
            // FILTRO 2. DETECCIÓN DE CAMBIOS SIGNIFICATIVOS
            // --------------------------------------------------

            // Comprobamos si existe un cambio suficiente
            // en alguno de los tres ejes del sensor.
            //
            // La primera lectura siempre se acepta porque
            // valoresAnteriores todavía es null.
            //
            // Después utilizamos indices.any para revisar los ejes.
            // Si al menos uno supera el umbral establecido,
            // se considera que existe un cambio significativo.
            val cambioSignificativo = valoresAnteriores == null ||
                    valores.indices.any { i ->
                        abs(valores[i] - valoresAnteriores[i]) >= umbral
                    }


            // Si ninguno de los ejes presenta un cambio que
            // alcance el umbral, descartamos esta lectura.
            if (!cambioSignificativo) return


            // --------------------------------------------------
            // ACTUALIZACIÓN DE LOS DATOS ANTERIORES
            // --------------------------------------------------

            // Cuando una lectura supera ambos filtros,
            // guardamos sus valores y su tiempo.
            //
            // De esta manera, la siguiente lectura podrá
            // compararse con la última que fue aceptada.
            when (tipoSensor) {

                // Actualizamos los datos del acelerómetro.
                Sensor.TYPE_ACCELEROMETER -> {
                    valoresAnterioresAccel = valores
                    ultimoTiempoAccel = event.timestamp
                }

                // Actualizamos los datos del giroscopio.
                Sensor.TYPE_GYROSCOPE -> {
                    valoresAnterioresGyro = valores
                    ultimoTiempoGyro = event.timestamp
                }
            }


            // --------------------------------------------------
            // CONTABILIZACIÓN DE LECTURAS
            // --------------------------------------------------

            // Incrementamos el contador únicamente cuando
            // el evento ha superado los dos filtros.
            lecturasProcesadas++

            // Guardamos el total en una variable local para
            // utilizar el valor correspondiente a este evento
            // cuando se actualice la interfaz.
            val totalLecturas = lecturasProcesadas


            // --------------------------------------------------
            // ACTUALIZACIÓN DE LA INTERFAZ
            // --------------------------------------------------

            // Los eventos se reciben en un hilo secundario.
            // runOnUiThread permite ejecutar las modificaciones
            // de los TextView en el hilo principal de Android.
            //
            // Verificamos nuevamente que el monitoreo siga activo,
            // por si el usuario lo detuvo mientras el evento
            // esperaba para actualizar la interfaz.
            runOnUiThread {

                if (monitoreoActivo) {

                    // Enviamos el tipo de sensor, sus valores
                    // y el contador al método encargado de
                    // actualizar los elementos visuales.
                    actualizarInterfaz(
                        tipoSensor,
                        valores,
                        totalLecturas
                    )
                }
            }
        }


        // Este método se ejecuta cuando cambia la precisión
        // reportada por un sensor.
        //
        // En nuestra aplicación no necesitamos realizar
        // ninguna operación cuando ocurre este evento.
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {

            // Se conserva la implementación requerida
            // por la interfaz SensorEventListener.
        }
    }


    // ==========================================================
    // 7. MÉTODO PRINCIPAL: INICIALIZACIÓN DE LA APLICACIÓN
    // ==========================================================

    // onCreate() se ejecuta cuando Android crea la actividad.
    // Aquí preparamos la pantalla, los sensores y los controles.
    override fun onCreate(savedInstanceState: Bundle?) {

        // Ejecutamos primero el procedimiento de creación
        // de la clase padre AppCompatActivity.
        super.onCreate(savedInstanceState)


        // Permite que la aplicación utilice el espacio de pantalla
        // detrás de las barras del sistema.
        enableEdgeToEdge()

        // Cargamos el diseño XML de la aplicación.
        // Este archivo contiene los TextView y Button
        // que utilizaremos para mostrar y controlar los sensores.
        setContentView(R.layout.activity_main)


        // --------------------------------------------------
        // AJUSTE DE LOS ELEMENTOS A LA PANTALLA
        // --------------------------------------------------

        // Obtenemos las dimensiones de las barras del sistema,
        // como la barra de estado y la barra de navegación.
        //
        // De esta manera evitamos que los componentes visuales
        // queden ocultos detrás de dichas barras.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->

            // Recuperamos las dimensiones de las áreas del sistema.
            val systemBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
            )

            // Aplicamos los espacios correspondientes al contenedor
            // principal para mantener visible el contenido.
            v.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                systemBars.bottom
            )

            // Devolvemos los datos de las áreas del sistema
            // para que Android pueda continuar administrándolos.
            insets
        }


        // --------------------------------------------------
        // CONEXIÓN DE LOS COMPONENTES VISUALES
        // --------------------------------------------------

        // Ejecutamos el método que relaciona cada variable
        // de Kotlin con su identificador correspondiente del XML.
        inicializarVistas()


        // --------------------------------------------------
        // OBTENCIÓN Y COMPROBACIÓN DE LOS SENSORES
        // --------------------------------------------------

        // Solicitamos a Android el servicio encargado
        // de administrar los sensores físicos del teléfono.
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        // Buscamos el acelerómetro utilizando su identificador.
        // Si está disponible, obtenemos su referencia.
        // Si no existe, el resultado será null.
        acelerometro = sensorManager.getDefaultSensor(
            Sensor.TYPE_ACCELEROMETER
        )

        // Realizamos la misma comprobación para el giroscopio.
        giroscopio = sensorManager.getDefaultSensor(
            Sensor.TYPE_GYROSCOPE
        )


        // --------------------------------------------------
        // CREACIÓN DEL HILO SECUNDARIO
        // --------------------------------------------------

        // Creamos un hilo independiente con el nombre
        // RunTrackSensorThread.
        sensorThread = HandlerThread("RunTrackSensorThread")

        // Iniciamos el hilo para que pueda recibir eventos.
        sensorThread.start()

        // Asociamos el Handler con el Looper del hilo creado.
        // Así, los eventos de los sensores se procesarán
        // dentro del hilo secundario y no directamente
        // en el hilo principal de la interfaz.
        sensorHandler = Handler(sensorThread.looper)


        // Mostramos en pantalla si los dos sensores
        // se encuentran disponibles en el dispositivo.
        comprobarSensores()


        // --------------------------------------------------
        // CONFIGURACIÓN DE LOS BOTONES
        // --------------------------------------------------

        // Cuando el usuario presiona Iniciar monitoreo,
        // se ejecuta el método que activa los sensores.
        btnIniciar.setOnClickListener {
            iniciarMonitoreo()
        }

        // Cuando el usuario presiona Detener monitoreo,
        // se ejecuta el método que libera los listeners.
        btnDetener.setOnClickListener {
            detenerMonitoreo()
        }
    }


    // ==========================================================
    // 8. VINCULACIÓN DE LA INTERFAZ CON EL CÓDIGO KOTLIN
    // ==========================================================

    // Este método obtiene los componentes definidos en XML
    // y los relaciona con las variables declaradas anteriormente.
    private fun inicializarVistas() {

        // Estado general de la aplicación.
        tvEstadoGeneral = findViewById(R.id.tvEstadoGeneral)

        // Disponibilidad y estado de cada sensor.
        tvEstadoAcelerometro = findViewById(R.id.tvEstadoAcelerometro)
        tvEstadoGiroscopio = findViewById(R.id.tvEstadoGiroscopio)

        // Valores del acelerómetro.
        tvAccelX = findViewById(R.id.tvAccelX)
        tvAccelY = findViewById(R.id.tvAccelY)
        tvAccelZ = findViewById(R.id.tvAccelZ)

        // Valores del giroscopio.
        tvGyroX = findViewById(R.id.tvGyroX)
        tvGyroY = findViewById(R.id.tvGyroY)
        tvGyroZ = findViewById(R.id.tvGyroZ)

        // Información estadística del monitoreo.
        tvLecturasProcesadas = findViewById(R.id.tvLecturasProcesadas)
        tvUltimaActualizacion = findViewById(R.id.tvUltimaActualizacion)

        // Botones de control.
        btnIniciar = findViewById(R.id.btnIniciar)
        btnDetener = findViewById(R.id.btnDetener)
    }


    // ==========================================================
    // 9. VERIFICACIÓN DE LA DISPONIBILIDAD DE LOS SENSORES
    // ==========================================================

    // Comprueba si el acelerómetro y el giroscopio
    // fueron encontrados durante la inicialización.
    private fun comprobarSensores() {

        // Si la referencia del sensor no es null,
        // significa que está disponible en el dispositivo.
        val tieneAcelerometro = acelerometro != null
        val tieneGiroscopio = giroscopio != null


        // Mostramos el resultado de la comprobación
        // correspondiente al acelerómetro.
        tvEstadoAcelerometro.text = if (tieneAcelerometro) {
            "Estado: Disponible"
        } else {
            "Estado: No disponible"
        }

        // Mostramos el resultado de la comprobación
        // correspondiente al giroscopio.
        tvEstadoGiroscopio.text = if (tieneGiroscopio) {
            "Estado: Disponible"
        } else {
            "Estado: No disponible"
        }


        // El botón de inicio solamente se habilita
        // cuando ambos sensores se encuentran disponibles.
        btnIniciar.isEnabled = tieneAcelerometro && tieneGiroscopio

        // Actualizamos el estado general de la aplicación.
        if (btnIniciar.isEnabled) {
            tvEstadoGeneral.text = "Estado: Detenido"
        } else {
            tvEstadoGeneral.text = "Estado: Sensores incompletos"
        }
    }


    // ==========================================================
    // 10. INICIO DEL MONITOREO
    // ==========================================================

    // Este método se ejecuta cuando el usuario presiona
    // el botón Iniciar monitoreo.
    private fun iniciarMonitoreo() {

        // Guardamos las referencias de los dos sensores
        // en variables locales para comprobar su disponibilidad.
        val sensorAccel = acelerometro
        val sensorGyro = giroscopio


        // Antes de iniciar, verificamos que los dos sensores existan.
        // Si alguno no está disponible, mostramos un mensaje
        // y terminamos el método mediante return.
        if (sensorAccel == null || sensorGyro == null) {

            Toast.makeText(
                this,
                "No se encuentran disponibles ambos sensores",
                Toast.LENGTH_SHORT
            ).show()

            return
        }


        // --------------------------------------------------
        // REINICIO DE LOS VALORES DEL FILTRO
        // --------------------------------------------------

        // Eliminamos los valores anteriores para que,
        // al comenzar un nuevo monitoreo, la primera lectura
        // de cada sensor se acepte como referencia inicial.
        valoresAnterioresAccel = null
        valoresAnterioresGyro = null

        // Reiniciamos los tiempos de las últimas lecturas aceptadas.
        ultimoTiempoAccel = 0L
        ultimoTiempoGyro = 0L

        // Reiniciamos el contador de lecturas procesadas.
        lecturasProcesadas = 0

        // Actualizamos la información visual del contador y la hora.
        tvLecturasProcesadas.text = "Lecturas procesadas: 0"
        tvUltimaActualizacion.text = "Última actualización: --"


        // Indicamos que el monitoreo ya puede comenzar.
        monitoreoActivo = true


        // --------------------------------------------------
        // REGISTRO DEL ACELERÓMETRO
        // --------------------------------------------------

        // registerListener() conecta el listener con el sensor.
        //
        // SENSOR_DELAY_GAME solicita una frecuencia de eventos
        // adecuada para aplicaciones que necesitan detectar
        // movimientos y cambios con rapidez.
        //
        // sensorHandler indica que los eventos se recibirán
        // en nuestro hilo secundario.
        //
        // El método devuelve true cuando el registro se realiza
        // correctamente; de lo contrario devuelve false.
        val acelerometroRegistrado = sensorManager.registerListener(
            sensorListener,
            sensorAccel,
            SensorManager.SENSOR_DELAY_GAME,
            sensorHandler
        )


        // --------------------------------------------------
        // REGISTRO DEL GIROSCOPIO
        // --------------------------------------------------

        // Registramos el mismo listener para el giroscopio.
        // De esta manera, un solo listener puede identificar
        // cuál de los dos sensores generó cada evento.
        val giroscopioRegistrado = sensorManager.registerListener(
            sensorListener,
            sensorGyro,
            SensorManager.SENSOR_DELAY_GAME,
            sensorHandler
        )


        // --------------------------------------------------
        // COMPROBACIÓN DEL REGISTRO
        // --------------------------------------------------

        // Verificamos que los dos registros hayan funcionado.
        // Si alguno falla, detenemos ambos listeners para evitar
        // que la aplicación continúe monitoreando parcialmente.
        if (!acelerometroRegistrado || !giroscopioRegistrado) {

            // Desactivamos el estado de monitoreo.
            monitoreoActivo = false

            // Cancelamos todos los registros asociados al listener.
            sensorManager.unregisterListener(sensorListener)

            // Informamos al usuario que no fue posible activar
            // correctamente los dos sensores.
            Toast.makeText(
                this,
                "No fue posible activar ambos sensores",
                Toast.LENGTH_LONG
            ).show()

            // Volvemos a mostrar la disponibilidad de los sensores.
            comprobarSensores()

            return
        }


        // --------------------------------------------------
        // ACTUALIZACIÓN DEL ESTADO DE LA APLICACIÓN
        // --------------------------------------------------

        // Indicamos que los sensores ya están funcionando.
        tvEstadoGeneral.text = "Estado: Monitoreando"

        tvEstadoAcelerometro.text = "Estado: Activo"
        tvEstadoGiroscopio.text = "Estado: Activo"

        // Deshabilitamos el botón de inicio para evitar
        // registrar nuevamente los mismos listeners.
        btnIniciar.isEnabled = false

        // Habilitamos el botón que permite detener el monitoreo.
        btnDetener.isEnabled = true
    }


    // ==========================================================
    // 11. DETENCIÓN DEL MONITOREO
    // ==========================================================

    // Este método se ejecuta cuando el usuario presiona
    // el botón Detener monitoreo.
    private fun detenerMonitoreo() {

        // Cambiamos primero el estado para impedir que
        // se sigan procesando nuevos eventos.
        monitoreoActivo = false

        // unregisterListener() cancela la recepción de eventos
        // del acelerómetro y del giroscopio para este listener.
        //
        // Esto ayuda a disminuir el consumo de batería
        // y evita procesar lecturas cuando ya no se necesitan.
        sensorManager.unregisterListener(sensorListener)


        // Actualizamos los estados mostrados en pantalla.
        tvEstadoGeneral.text = "Estado: Detenido"

        // Los sensores siguen disponibles físicamente,
        // pero ya no están enviando lecturas a la aplicación.
        tvEstadoAcelerometro.text = "Estado: Disponible"
        tvEstadoGiroscopio.text = "Estado: Disponible"


        // Permitimos iniciar nuevamente el monitoreo
        // únicamente si los dos sensores están disponibles.
        btnIniciar.isEnabled = acelerometro != null && giroscopio != null

        // Deshabilitamos el botón detener porque
        // ya no existe un monitoreo activo.
        btnDetener.isEnabled = false
    }


    // ==========================================================
    // 12. ACTUALIZACIÓN DE LOS DATOS EN LA INTERFAZ
    // ==========================================================

    // Recibe el tipo de sensor, los valores aceptados
    // y el total de lecturas procesadas.
    //
    // Su función es mostrar la información correspondiente
    // en los TextView de activity_main.xml.
    private fun actualizarInterfaz(
        tipoSensor: Int,
        valores: FloatArray,
        totalLecturas: Int
    ) {

        // Función auxiliar para presentar cada valor con
        // solamente dos posiciones decimales.
        //
        // Locale.getDefault() utiliza la configuración regional
        // del dispositivo para dar formato a los números.
        fun formato(valor: Float): String {
            return String.format(Locale.getDefault(), "%.2f", valor)
        }


        // Identificamos el sensor para actualizar solamente
        // los campos que le corresponden.
        when (tipoSensor) {

            // Si la lectura pertenece al acelerómetro,
            // mostramos sus tres componentes de aceleración.
            Sensor.TYPE_ACCELEROMETER -> {

                tvAccelX.text = "Eje X: ${formato(valores[0])} m/s²"
                tvAccelY.text = "Eje Y: ${formato(valores[1])} m/s²"
                tvAccelZ.text = "Eje Z: ${formato(valores[2])} m/s²"
            }


            // Si la lectura pertenece al giroscopio,
            // mostramos sus tres componentes de velocidad angular.
            Sensor.TYPE_GYROSCOPE -> {

                tvGyroX.text = "Eje X: ${formato(valores[0])} rad/s"
                tvGyroY.text = "Eje Y: ${formato(valores[1])} rad/s"
                tvGyroZ.text = "Eje Z: ${formato(valores[2])} rad/s"
            }
        }


        // Mostramos la cantidad total de eventos aceptados
        // por los filtros durante el monitoreo actual.
        tvLecturasProcesadas.text = "Lecturas procesadas: $totalLecturas"


        // Obtenemos la hora actual del dispositivo
        // y la presentamos en formato de 24 horas.
        //
        // Esto permite identificar cuándo se realizó
        // la última actualización visible.
        tvUltimaActualizacion.text =
            "Última actualización: ${DateFormat.format("HH:mm:ss", System.currentTimeMillis())}"
    }


    // ==========================================================
    // 13. CONTROL DEL CICLO DE VIDA DE LA APLICACIÓN
    // ==========================================================

    // onPause() se ejecuta cuando la actividad deja de estar
    // en primer plano, por ejemplo, cuando el usuario sale
    // de RunTrack o cambia a otra aplicación.
    override fun onPause() {

        // Si el monitoreo estaba activo, lo detenemos
        // y liberamos los listeners.
        if (monitoreoActivo) {
            detenerMonitoreo()
        }

        // Continuamos con el procedimiento de pausa
        // correspondiente a la actividad padre.
        super.onPause()
    }


    // onDestroy() se ejecuta cuando Android destruye
    // definitivamente esta instancia de la actividad.
    override fun onDestroy() {

        // Comprobamos que el hilo secundario haya sido inicializado.
        // Esto evita intentar detener un hilo que no existe.
        if (::sensorThread.isInitialized) {

            // Solicitamos que el hilo termine de manera segura,
            // procesando los mensajes pendientes antes de finalizar.
            sensorThread.quitSafely()
        }

        // Ejecutamos finalmente el procedimiento de destrucción
        // correspondiente a AppCompatActivity.
        super.onDestroy()
    }
}
