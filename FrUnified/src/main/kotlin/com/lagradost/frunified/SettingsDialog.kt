package com.lagradost.frunified

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/**
 * Écran de réglages du plugin (construit programmatiquement, sans ressources
 * XML, pour rester compatible avec toutes les versions de CloudStream).
 *
 * Accessible depuis : Paramètres → Extensions → FR Unifié → ⚙️
 */
object SettingsDialog {

    private fun Context.dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    private fun title(context: Context, text: String) = TextView(context).apply {
        this.text = text
        textSize = 15f
        setPadding(0, context.dp(16), 0, context.dp(4))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun hint(context: Context, text: String) = TextView(context).apply {
        this.text = text
        textSize = 12f
        alpha = 0.7f
        setPadding(0, 0, 0, context.dp(4))
    }

    fun show(context: Context) {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(20), context.dp(8), context.dp(20), context.dp(8))
        }

        // --- Interrupteurs généraux
        val localSwitch = Switch(context).apply {
            text = "Utiliser les extensions FR installées"
            isChecked = FrSettings.useLocalSources
        }
        val stremioSwitch = Switch(context).apply {
            text = "Utiliser les addons Stremio"
            isChecked = FrSettings.useStremio
        }
        val subsSwitch = Switch(context).apply {
            text = "Sous-titres externes (OpenSubtitles)"
            isChecked = FrSettings.useSubtitles
        }
        root.addView(title(context, "Général"))
        root.addView(localSwitch)
        root.addView(stremioSwitch)
        root.addView(subsSwitch)

        // --- Activation par source
        val detected = SourceHub.detectedSources()
        root.addView(title(context, "Serveurs / extensions FR détectés (${detected.size})"))
        val boxes = detected.map { api ->
            CheckBox(context).apply {
                text = api.name
                isChecked = FrSettings.isSourceEnabled(api.name)
            }.also { root.addView(it) }
        }
        if (detected.isEmpty()) {
            root.addView(
                hint(
                    context,
                    "Aucune extension française installée. Ajoutez par exemple French-Stream, " +
                        "Movix, Wiflix, FrenchAnime, Frembed ou FSTV : FR Unifié s'en servira " +
                        "automatiquement pour trouver les liens."
                )
            )
        }

        // --- Addons Stremio
        root.addView(title(context, "Addons Stremio"))
        root.addView(
            hint(
                context,
                "Une URL par ligne (manifest.json ou URL de base).\n" +
                    "Ex. : https://torrentio.strem.fun/manifest.json\n" +
                    "Fonctionne avec Torrentio, Comet, MediaFusion, un debrid perso, etc."
            )
        )
        val stremioField = EditText(context).apply {
            setText(FrSettings.stremioUrls.joinToString("\n"))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            setHorizontallyScrolling(false)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(stremioField)

        // --- Langues de sous-titres
        root.addView(title(context, "Langues de sous-titres"))
        root.addView(hint(context, "Codes séparés par des virgules (fre, fra, fr, eng…)"))
        val langField = EditText(context).apply {
            setText(FrSettings.subtitleLangs.joinToString(", "))
            inputType = InputType.TYPE_CLASS_TEXT
        }
        root.addView(langField)

        val scroll = ScrollView(context).apply { addView(root) }

        AlertDialog.Builder(context)
            .setTitle("FR Unifié — réglages")
            .setView(scroll)
            .setPositiveButton("Enregistrer") { _, _ ->
                FrSettings.useLocalSources = localSwitch.isChecked
                FrSettings.useStremio = stremioSwitch.isChecked
                FrSettings.useSubtitles = subsSwitch.isChecked

                boxes.forEachIndexed { index, box ->
                    detected.getOrNull(index)?.let { api ->
                        FrSettings.setSourceEnabled(api.name, box.isChecked)
                    }
                }

                FrSettings.stremioUrls = stremioField.text.toString()
                    .split("\n", ",", " ")
                    .map { it.trim() }
                    .filter { it.startsWith("http") || it.startsWith("stremio://") }

                FrSettings.subtitleLangs = langField.text.toString()
                    .split(",")
                    .map { it.trim().lowercase() }
                    .filter { it.isNotBlank() }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }
}
