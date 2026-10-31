package com.meupainel.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.notification.CarAppExtender
import androidx.car.app.notification.CarNotificationManager
import androidx.car.app.notification.CarPendingIntent
import androidx.car.app.validation.HostValidator
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private const val MAX_LINHAS = 6
private const val CANAL_ALERTA = "alerta_motor"
private val BR = Locale("pt", "BR")

class PainelCarService : CarAppService() {
    // App pessoal: aceita qualquer Android Auto.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = PainelSession()
}

class PainelSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        criarCanal()
        Obd.iniciar(carContext) { t, lim -> alertar(t, lim) }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) { Obd.parar() }
        })
        return if (BuildConfig.TELA_DESENHADA) InicioScreen(carContext) else InicioSimplesScreen(carContext)
    }

    override fun onNewIntent(intent: Intent) {
        // Se o Android Auto devolver um pedido de navegação para cá, repassa ao Waze/Maps do celular.
        if (intent.action == CarContext.ACTION_NAVIGATE) abrirNoCelular(intent.data)
    }

    private fun abrirNoCelular(geo: Uri?) {
        val q = geo?.getQueryParameter("q") ?: return
        val waze = Intent(Intent.ACTION_VIEW, Uri.parse("https://waze.com/ul?q=" + Uri.encode(q) + "&navigate=yes"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            carContext.startActivity(waze)
        } catch (e: Exception) {
            CarToast.makeText(carContext, "Abra o Waze para navegar", CarToast.LENGTH_LONG).show()
        }
    }

    private fun criarCanal() {
        val nm = carContext.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CANAL_ALERTA, "Alerta de temperatura", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    /** Alerta que aparece por cima do Waze quando o motor passa do limite. */
    private fun alertar(temp: Int, limite: Int) {
        val abrir = CarPendingIntent.getCarApp(
            carContext, 1,
            Intent(Intent.ACTION_VIEW).setComponent(ComponentName(carContext, PainelCarService::class.java)),
            0
        )
        val n = NotificationCompat.Builder(carContext, CANAL_ALERTA)
            .setSmallIcon(R.drawable.ic_alerta)
            .setContentTitle("Motor quente · $temp °C")
            .setContentText("Acima do limite de $limite °C. Pare em um local seguro assim que possível.")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .extend(
                CarAppExtender.Builder()
                    .setContentTitle("Motor quente · $temp °C")
                    .setContentText("Acima do limite de $limite °C. Pare em um local seguro assim que possível.")
                    .setSmallIcon(R.drawable.ic_alerta)
                    .setImportance(NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setContentIntent(abrir)
                    .addAction(R.drawable.ic_painel, "Abrir Meu Painel", abrir)
                    .build()
            )
        try {
            CarNotificationManager.from(carContext).notify(1001, n)
        } catch (e: SecurityException) {
            // Sem permissão de notificação: o mostrador continua mostrando o estado.
        }
    }
}

private fun icone(ctx: CarContext, res: Int) =
    CarIcon.Builder(IconCompat.createWithResource(ctx, res)).build()

private fun toast(ctx: CarContext, msg: String) =
    CarToast.makeText(ctx, msg, CarToast.LENGTH_SHORT).show()

private fun navegar(ctx: CarContext, d: Destino) {
    val uri = Uri.parse("geo:0,0?q=" + Uri.encode(d.endereco))
    try {
        ctx.startCarApp(Intent(CarContext.ACTION_NAVIGATE, uri))
    } catch (e: Exception) {
        toast(ctx, "Não foi possível abrir a navegação")
    }
}

private fun bateria(ctx: CarContext): String {
    val bm = ctx.getSystemService(BatteryManager::class.java) ?: return "--"
    val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    return if (bm.isCharging) "$pct% · carregando" else "$pct%"
}

private fun proximoLembrete(ctx: CarContext): Pair<String, String> {
    val fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    val hoje = LocalDate.now()
    val itens = Store.lembretes(ctx).mapNotNull { l ->
        val d = try { LocalDate.parse(l.data, fmt) } catch (e: Exception) { null }
        if (d == null) null else l to ChronoUnit.DAYS.between(hoje, d)
    }
    val prox = itens.minByOrNull { it.second } ?: return "Lembretes" to "nenhum"
    val dias = prox.second
    val quando = when {
        dias > 1 -> "em $dias dias"
        dias == 1L -> "amanhã"
        dias == 0L -> "hoje"
        else -> "venceu há ${-dias} dias"
    }
    return prox.first.nome to quando
}

/** Tela inicial desenhada livremente: mostrador de temperatura + cartões. */
class InicioScreen(ctx: CarContext) : Screen(ctx) {
    private val renderer = Renderer()
    private val handler = Handler(Looper.getMainLooper())
    private var surface: Surface? = null
    private var area = Rect()

    private val tick = object : Runnable {
        override fun run() {
            desenhar()
            handler.postDelayed(this, 2000)
        }
    }

    private val callback = object : SurfaceCallback {
        override fun onSurfaceAvailable(container: SurfaceContainer) {
            surface = container.surface
            if (area.isEmpty) area = Rect(0, 0, container.width, container.height)
            desenhar()
        }

        override fun onVisibleAreaChanged(visibleArea: Rect) {
            area = Rect(visibleArea)
            desenhar()
        }

        override fun onStableAreaChanged(stableArea: Rect) {}

        override fun onSurfaceDestroyed(container: SurfaceContainer) {
            surface = null
        }

        override fun onClick(x: Float, y: Float) {
            clique(renderer.tocar(x, y))
        }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(callback)
            }
            override fun onStart(owner: LifecycleOwner) { handler.post(tick) }
            override fun onStop(owner: LifecycleOwner) { handler.removeCallbacks(tick) }
        })
    }

    private fun estado(): PainelEstado {
        val agora = LocalDateTime.now()
        val h = agora.hour
        val saudacao = when {
            h < 12 -> "Bom dia"
            h < 18 -> "Boa tarde"
            else -> "Boa noite"
        }
        val midia = Midia.info(carContext)
        val destinos = Store.destinos(carContext)
        val (lNome, lQuando) = proximoLembrete(carContext)
        return PainelEstado(
            saudacao = saudacao,
            data = agora.format(DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM", BR)),
            temp = Obd.temperatura,
            obdStatus = Obd.status,
            obdOk = Obd.conectado,
            limite = Store.limite(carContext),
            musica = midia?.titulo ?: "Nada tocando",
            artista = midia?.let { listOf(it.artista, it.app).filter { s -> s.isNotBlank() }.joinToString(" · ") }
                ?: "Toque play para começar",
            tocando = midia?.tocando ?: false,
            destino1 = destinos.getOrNull(0)?.nome ?: "Destino 1",
            destino2 = destinos.getOrNull(1)?.nome ?: "Destino 2",
            lembreteNome = lNome,
            lembreteQuando = lQuando,
            bateria = bateria(carContext)
        )
    }

    private fun desenhar() {
        val s = surface ?: return
        if (!s.isValid) return
        try {
            val c = s.lockCanvas(null)
            try {
                renderer.desenhar(c, area, estado())
            } finally {
                s.unlockCanvasAndPost(c)
            }
        } catch (e: Exception) {
            // superfície trocou no meio do desenho; o próximo tick redesenha
        }
    }

    private fun clique(alvo: Alvo?) {
        val destinos = Store.destinos(carContext)
        when (alvo) {
            Alvo.ANTERIOR -> toast(carContext, Midia.anterior(carContext))
            Alvo.PLAY -> toast(carContext, Midia.playPausa(carContext))
            Alvo.PROXIMA -> toast(carContext, Midia.proxima(carContext))
            Alvo.DESTINO1 -> destinos.getOrNull(0)?.let { navegar(carContext, it) }
                ?: screenManager.push(DestinosScreen(carContext))
            Alvo.DESTINO2 -> destinos.getOrNull(1)?.let { navegar(carContext, it) }
                ?: screenManager.push(DestinosScreen(carContext))
            Alvo.MAIS_DESTINOS -> screenManager.push(DestinosScreen(carContext))
            Alvo.MUSICA -> screenManager.push(MusicaScreen(carContext))
            Alvo.PAINEL -> screenManager.push(PainelScreen(carContext))
            null -> return
        }
        handler.postDelayed({ desenhar() }, 800)
    }

    override fun onGetTemplate(): Template {
        // Na tela de navegação o Android Auto só aceita um botão com texto; os outros vão só com ícone.
        val acoes = ActionStrip.Builder()
            .addAction(Action.Builder().setIcon(icone(carContext, R.drawable.ic_destino))
                .setOnClickListener { screenManager.push(DestinosScreen(carContext)) }.build())
            .addAction(Action.Builder().setIcon(icone(carContext, R.drawable.ic_musica))
                .setOnClickListener { screenManager.push(MusicaScreen(carContext)) }.build())
            .addAction(Action.Builder().setIcon(icone(carContext, R.drawable.ic_painel))
                .setOnClickListener { screenManager.push(PainelScreen(carContext)) }.build())
            .build()
        return NavigationTemplate.Builder()
            .setActionStrip(acoes)
            .build()
    }
}

/** Tela inicial da versão simples: grade com a temperatura e os três atalhos. */
class InicioSimplesScreen(ctx: CarContext) : Screen(ctx) {
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            invalidate()
            handler.postDelayed(this, 5_000)
        }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { handler.postDelayed(tick, 5_000) }
            override fun onStop(owner: LifecycleOwner) { handler.removeCallbacks(tick) }
        })
    }

    override fun onGetTemplate(): Template {
        val t = Obd.temperatura
        fun item(titulo: String, texto: String?, res: Int, abrir: () -> Screen) =
            androidx.car.app.model.GridItem.Builder()
                .setTitle(titulo)
                .apply { if (texto != null) setText(texto) }
                .setImage(icone(carContext, res))
                .setOnClickListener { screenManager.push(abrir()) }
                .build()

        val itens = ItemList.Builder()
            .addItem(item(if (t != null) "$t °C" else "-- °C", "Motor", R.drawable.ic_alerta) { PainelScreen(carContext) })
            .addItem(item("Destinos", null, R.drawable.ic_destino) { DestinosScreen(carContext) })
            .addItem(item("Música", null, R.drawable.ic_musica) { MusicaScreen(carContext) })
            .addItem(item("Painel", null, R.drawable.ic_painel) { PainelScreen(carContext) })
            .build()

        return androidx.car.app.model.GridTemplate.Builder()
            .setTitle("Meu Painel")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(itens)
            .build()
    }
}

/** Destinos favoritos: um toque abre o app de navegação padrão já com a rota. */
class DestinosScreen(ctx: CarContext) : Screen(ctx) {
    override fun onGetTemplate(): Template {
        val lista = ItemList.Builder()
        Store.destinos(carContext).take(MAX_LINHAS).forEach { d ->
            lista.addItem(
                Row.Builder()
                    .setTitle(d.nome)
                    .addText(d.endereco)
                    .setImage(icone(carContext, R.drawable.ic_destino))
                    .setOnClickListener { navegar(carContext, d) }
                    .build()
            )
        }
        lista.setNoItemsMessage("Cadastre destinos no app Meu Painel do celular")

        return ListTemplate.Builder()
            .setTitle("Destinos")
            .setHeaderAction(Action.BACK)
            .setSingleList(lista.build())
            .build()
    }
}

/** Atalhos de playlists + play/pausa e próxima. */
class MusicaScreen(ctx: CarContext) : Screen(ctx) {
    private val handler = Handler(Looper.getMainLooper())

    private fun agir(acao: () -> String) {
        toast(carContext, acao())
        handler.postDelayed({ invalidate() }, 1000)
    }

    override fun onGetTemplate(): Template {
        val lista = ItemList.Builder()
        val agora = Midia.tocandoAgora(carContext)
        var vagas = MAX_LINHAS
        if (agora != null) {
            lista.addItem(Row.Builder().setTitle("Tocando agora").addText(agora).build())
            vagas--
        }
        Store.playlists(carContext).take(vagas).forEach { p ->
            lista.addItem(
                Row.Builder()
                    .setTitle(p.nome)
                    .setImage(icone(carContext, R.drawable.ic_musica))
                    .setOnClickListener { agir { Midia.tocar(carContext, p) } }
                    .build()
            )
        }
        lista.setNoItemsMessage("Cadastre playlists no app Meu Painel do celular")

        val acoes = ActionStrip.Builder()
            .addAction(Action.Builder().setTitle("Play/Pausa")
                .setOnClickListener { agir { Midia.playPausa(carContext) } }.build())
            .addAction(Action.Builder().setTitle("Próxima")
                .setOnClickListener { agir { Midia.proxima(carContext) } }.build())
            .build()

        return ListTemplate.Builder()
            .setTitle("Música")
            .setHeaderAction(Action.BACK)
            .setActionStrip(acoes)
            .setSingleList(lista.build())
            .build()
    }
}

/** Painel detalhado: temperatura, bateria do celular e lembretes. Atualiza a cada 5 s. */
class PainelScreen(ctx: CarContext) : Screen(ctx) {
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            invalidate()
            handler.postDelayed(this, 5_000)
        }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { handler.postDelayed(tick, 5_000) }
            override fun onStop(owner: LifecycleOwner) { handler.removeCallbacks(tick) }
        })
    }

    override fun onGetTemplate(): Template {
        val lista = ItemList.Builder()

        val t = Obd.temperatura
        val lim = Store.limite(carContext)
        lista.addItem(
            Row.Builder()
                .setTitle(if (t != null) "Temperatura do motor: $t °C" else "Temperatura do motor: --")
                .addText(if (t != null) "Alerta em $lim °C · ${Obd.status}" else Obd.status)
                .build()
        )
        lista.addItem(Row.Builder().setTitle("Bateria do celular").addText(bateria(carContext)).build())

        val fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        Store.lembretes(carContext).take(MAX_LINHAS - 2).forEach { l ->
            val data = try { LocalDate.parse(l.data, fmt) } catch (e: Exception) { null }
            val status = if (data == null) "Data inválida (use dd/mm/aaaa)" else {
                val dias = ChronoUnit.DAYS.between(LocalDate.now(), data)
                when {
                    dias > 1 -> "Faltam $dias dias (${l.data})"
                    dias == 1L -> "É amanhã!"
                    dias == 0L -> "É hoje!"
                    else -> "Venceu há ${-dias} dias"
                }
            }
            lista.addItem(Row.Builder().setTitle(l.nome).addText(status).build())
        }

        return ListTemplate.Builder()
            .setTitle("Painel")
            .setHeaderAction(Action.BACK)
            .setSingleList(lista.build())
            .build()
    }
}
