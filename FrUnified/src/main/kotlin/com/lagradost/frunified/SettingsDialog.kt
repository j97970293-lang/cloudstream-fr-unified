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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        val nuvioSwitch = Switch(context).apply {
            text = "Scrapeurs Nuvio (plugins du projet Nuvio)"
            isChecked = FrSettings.useNuvio
        }
        val nuvioAllSwitch = Switch(context).apply {
            text = "Tous les scrapeurs Nuvio (pas seulement les FR)"
            isChecked = FrSettings.nuvioAllLangs
        }
        val subsSwitch = Switch(context).apply {
            text = "Sous-titres externes (OpenSubtitles)"
            isChecked = FrSettings.useSubtitles
        }
        root.addView(title(context, "Général"))
        root.addView(localSwitch)
        root.addView(stremioSwitch)
        root.addView(nuvioSwitch)
        root.addView(nuvioAllSwitch)
        root.addView(subsSwitch)

        // --- Activation par source FR
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

        // --- Scrapeurs Nuvio
        root.addView(title(context, "Scrapeurs Nuvio"))
        root.addView(
            hint(
                context,
                "Cochés = utilisés comme serveurs. Ils apparaissent ici dès que " +
                    "les dépôts ci-dessous sont chargés (quelques secondes)."
            )
        )
        val nuvioContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(hint(context, "Chargement des scrapeurs Nuvio…"))
        root.addView(nuvioContainer)

        val nuvioScrapers = mutableListOf<NuvioClient.NuvioScraper>()
        GlobalScope.launch {
            val scrapers = runCatching { NuvioClient.scrapers() }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) {
                // retire le message « Chargement »
                if (nuvioContainer.parent is LinearLayout) {
                    val parent = nuvioContainer.parent as LinearLayout
                    val index = parent.indexOfChild(nuvioContainer)
                    if (index > 0) (parent.getChildAt(index - 1) as? TextView)?.text = ""
                }
                if (scrapers.isEmpty()) {
                    nuvioContainer.addView(
                        hint(context, "Aucun scrapeur trouvé : vérifiez l'URL des dépôts ci-dessous.")
                    )
                } else {
                    nuvioScrapers.addAll(scrapers)
                    scrapers.forEach { scraper ->
                        nuvioContainer.addView(
                            CheckBox(context).apply {
                                text = scraper.name +
                                    (if (scraper.isFrench) "" else "  ·  ${scraper.contentLanguage.joinToString("/")}") +
                                    "  ·  ${scraper.supportedTypes.joinToString("/")}"
                                isChecked = FrSettings.isNuvioEnabled(scraper.id)
                            }
                        )
                    }
                }
            }
        }

        root.addView(title(context, "Dépôts Nuvio"))
        root.addView(
            hint(
                context,
                "Une URL de manifest.json par ligne.\n" +
                    "Par défaut : Gowaru (FrenchStream, Movix, Vostfree, VoirAnime…) " +
                    "et Phisher (MoviesDrive, AllWish…).\n" +
                    "Autres dépôts : michat88, yoruix, D3adlyRocket…"
            )
        )
        val nuvioField = EditText(context).apply {
            setText(FrSettings.nuvioRepos.joinToString("\n"))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            setHorizontallyScrolling(false)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(nuvioField)

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
                FrSettings.useNuvio = nuvioSwitch.isChecked
                FrSettings.nuvioAllLangs = nuvioAllSwitch.isChecked
                FrSettings.useSubtitles = subsSwitch.isChecked

                boxes.forEachIndexed { index, box ->
                    detected.getOrNull(index)?.let { api ->
                        FrSettings.setSourceEnabled(api.name, box.isChecked)
                    }
                }

                // Cases Nuvio : récupérées par ordre d'ajout
                val nuvioBoxes = (0 until nuvioContainer.childCount)
                    .map { nuvioContainer.getChildAt(it) as? CheckBox }
                    .filterNotNull()
                nuvioBoxes.forEachIndexed { index, box ->
                    nuvioScrapers.getOrNull(index)?.let { scraper ->
                        FrSettings.setNuvioEnabled(scraper.id, box.isChecked)
                    }
                }

                FrSettings.nuvioRepos = nuvioField.text.toString()
                    .split("\n", ",")
                    .map { it.trim() }
                    .filter { it.startsWith("http") }

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
