package com.johancarrion.instaladorpwa

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var store: PwaStore
    private lateinit var urlInput: EditText
    private lateinit var searchBtn: Button
    private lateinit var status: TextView
    private lateinit var previewBox: LinearLayout
    private lateinit var previewIcon: ImageView
    private lateinit var nameInput: EditText
    private lateinit var previewUrl: TextView
    private lateinit var listBox: LinearLayout

    private var current: PwaInfo? = null
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = PwaStore(this)
        buildUi()
        refreshList()

        if (intent?.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            val link = Regex("https?://\\S+").find(text)?.value ?: text.trim()
            if (link.isNotEmpty()) {
                urlInput.setText(link)
                search()
            }
        }
    }

    // ---------- UI (programática, sin librerías) ----------

    private fun buildUi() {
        val bg = Color.parseColor("#121212")
        val card = Color.parseColor("#1E1E1E")
        val muted = Color.parseColor("#9AA0A6")

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }

        content.addView(TextView(this).apply {
            text = "Instalador PWA"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        content.addView(TextView(this).apply {
            text = "Pega la dirección de una PWA y créale un acceso directo propio, sin barra de direcciones."
            textSize = 14f
            setTextColor(muted)
            setPadding(0, dp(6), 0, dp(18))
        })

        urlInput = EditText(this).apply {
            hint = "https://mi-pwa.com"
            setHintTextColor(muted)
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setSingleLine()
            setOnEditorActionListener { _, _, _ -> search(); true }
        }
        content.addView(urlInput, lp())

        searchBtn = Button(this).apply {
            text = "Buscar app"
            setOnClickListener { search() }
        }
        content.addView(searchBtn, lp(top = 8))

        status = TextView(this).apply {
            textSize = 14f
            setTextColor(muted)
            setPadding(0, dp(10), 0, dp(10))
        }
        content.addView(status)

        previewBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(card)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            visibility = View.GONE
        }
        previewIcon = ImageView(this)
        previewBox.addView(previewIcon, LinearLayout.LayoutParams(dp(84), dp(84)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        nameInput = EditText(this).apply {
            hint = "Nombre del acceso directo"
            setHintTextColor(muted)
            setTextColor(Color.WHITE)
            setSingleLine()
            gravity = Gravity.CENTER
        }
        previewBox.addView(nameInput, lp(top = 8))
        previewUrl = TextView(this).apply {
            textSize = 12f
            setTextColor(muted)
            gravity = Gravity.CENTER
        }
        previewBox.addView(previewUrl, lp())
        previewBox.addView(Button(this).apply {
            text = "Instalar en pantalla de inicio"
            setOnClickListener { install() }
        }, lp(top = 12))
        content.addView(previewBox, lp())

        content.addView(TextView(this).apply {
            text = "Instaladas"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, dp(28), 0, dp(8))
        })
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(listBox)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
            addView(content)
        })
    }

    // ---------- Lógica ----------

    private fun search() {
        val raw = urlInput.text.toString().trim()
        if (raw.isEmpty()) return
        status.text = "Buscando…"
        previewBox.visibility = View.GONE
        searchBtn.isEnabled = false
        io.execute {
            val result = runCatching { PwaFetcher.fetch(raw) }
            ui.post {
                searchBtn.isEnabled = true
                result.onSuccess { show(it) }
                    .onFailure { status.text = "No se pudo leer la página: ${it.message ?: it.javaClass.simpleName}" }
            }
        }
    }

    private fun show(info: PwaInfo) {
        current = info
        previewIcon.setImageBitmap(info.icon)
        nameInput.setText(info.name)
        previewUrl.text = info.startUrl
        previewBox.visibility = View.VISIBLE
        status.text = if (info.hasManifest) "PWA detectada." else "Esta página no tiene manifest; se instalará igual como app web."
    }

    private fun install() {
        val info = current ?: return
        val name = nameInput.text.toString().trim().ifEmpty { info.name }
        val sm = getSystemService(ShortcutManager::class.java)
        if (!sm.isRequestPinShortcutSupported) {
            status.text = "Tu launcher no permite fijar accesos directos."
            return
        }

        val id = PwaStore.idFor(info.startUrl)
        store.iconFile(id).also { it.parentFile?.mkdirs() }.outputStream().use {
            info.icon.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        store.save(SavedPwa(id, name, info.startUrl, info.themeColor))

        val launch = Intent(this, PwaActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(info.startUrl)
            putExtra(PwaActivity.EXTRA_NAME, name)
            putExtra(PwaActivity.EXTRA_ID, id)
            putExtra(PwaActivity.EXTRA_COLOR, info.themeColor)
        }
        val shortcut = ShortcutInfo.Builder(this, id)
            .setShortLabel(name)
            .setLongLabel(name)
            .setIcon(Icon.createWithAdaptiveBitmap(info.icon))
            .setIntent(launch)
            .build()

        runCatching { sm.enableShortcuts(listOf(id)) }
        sm.requestPinShortcut(shortcut, null)
        status.text = "Confirma el acceso directo en el diálogo del sistema."
        refreshList()
    }

    private fun refreshList() {
        listBox.removeAllViews()
        val items = store.all()
        if (items.isEmpty()) {
            listBox.addView(TextView(this).apply {
                text = "Aún no has instalado ninguna."
                setTextColor(Color.parseColor("#9AA0A6"))
            })
            return
        }
        for (p in items) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val img = ImageView(this)
            BitmapFactory.decodeFile(store.iconFile(p.id).path)?.let { img.setImageBitmap(it) }
            row.addView(img, LinearLayout.LayoutParams(dp(48), dp(48)))

            val texts = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(12), 0)
            }
            texts.addView(TextView(this).apply { text = p.name; setTextColor(Color.WHITE); textSize = 16f })
            texts.addView(TextView(this).apply {
                text = Uri.parse(p.url).host ?: p.url
                setTextColor(Color.parseColor("#9AA0A6"))
                textSize = 12f
            })
            row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            row.addView(Button(this).apply {
                text = "Quitar"
                setOnClickListener { confirmRemove(p) }
            })
            listBox.addView(row)
        }
    }

    private fun confirmRemove(p: SavedPwa) {
        AlertDialog.Builder(this)
            .setTitle("Quitar ${p.name}")
            .setMessage("Se elimina de la lista y el acceso directo queda desactivado. Si sigue en tu pantalla de inicio, mantenlo presionado para borrarlo.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Quitar") { _, _ ->
                runCatching {
                    getSystemService(ShortcutManager::class.java).disableShortcuts(listOf(p.id), "App eliminada")
                }
                store.remove(p.id)
                refreshList()
            }
            .show()
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
