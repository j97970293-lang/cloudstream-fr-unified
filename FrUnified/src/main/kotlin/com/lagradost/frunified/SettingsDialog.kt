package com.lagradost.frunified

import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Écran de réglages du plugin — style « sections repliables » (cartes avec
 * barre de couleur, icône, titre, sous-titre, chevron et compteur à droite),
 * construit programmatiquement pour rester compatible avec toute version
 * de CloudStream.
 *
 * Sections : Scraping, Cloudflare, Clés API, Catalogues, Fournisseurs,
 * Addons Stremio, Sous-titres, Crédits.
 */
// ------------------------------------------------------------ helpers fichier

private fun Context.dp(value: Int): Int = TypedValue.applyDimension(
    TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
).toInt()

private fun roundedDp(color: Int, radiusDp: Int, ctx: Context): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = ctx.dp(radiusDp).toFloat()
    }

private fun TextView.label(context: Context, text: String, size: Float, color: Int, bold: Boolean = false) =
    apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

private fun EditText.box(context: Context, p: Palette): EditText = apply {
    background = roundedDp(p.field, 10, context)
    setTextColor(p.fieldText)
    setHintTextColor(p.sub)
    setPadding(context.dp(12), context.dp(8), context.dp(12), context.dp(8))
}

private fun CheckBox.row(context: Context, p: Palette, text: String, checked: Boolean): CheckBox =
    CheckBox(context).apply {
        this.text = text
        isChecked = checked
        setTextColor(p.title)
        textSize = 14f
        setPadding(0, context.dp(4), 0, context.dp(4))
    }


private class Section(
    context: Context,
    accent: Int,
    icon: String,
    titleText: String,
    subtitleText: String,
    private val p: Palette,
    summary: () -> String = { "" }
) : LinearLayout(context) {

    private val body = LinearLayout(context).apply { orientation = VERTICAL }
    private val chevron = TextView(context)
    private val summaryView = TextView(context)
    private val header = LinearLayout(context)

    init {
        orientation = VERTICAL
        background = roundedDp(p.card, 16, context)
        setPadding(context.dp(14), context.dp(10), context.dp(14), context.dp(8))

        // barre colorée (4dp x 26dp, arrondie)
        val bar = View(context).apply {
            background = roundedDp(accent, 3, context)
            layoutParams = LinearLayout.LayoutParams(context.dp(4), context.dp(26))
        }

        val iconView = TextView(context).apply {
            text = icon
            textSize = 16f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(context.dp(30), context.dp(30))
        }

        val titles = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(TextView(context).apply {
                text = titleText
                textSize = 15f
                setTextColor(p.title)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            if (subtitleText.isNotBlank()) {
                addView(TextView(context).apply {
                    text = subtitleText
                    textSize = 11f
                    setTextColor(p.sub)
                })
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        summaryView.apply {
            text = summary()
            textSize = 12f
            setTextColor(accent)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            visibility = if (text.isNotBlank()) View.VISIBLE else View.GONE
        }

        chevron.apply {
            text = "▾"
            textSize = 16f
            setTextColor(p.sub)
            gravity = Gravity.CENTER
            setPadding(context.dp(6), 0, 0, 0)
        }

        header.apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(bar)
            addView(iconView)
            addView(titles)
            addView(summaryView)
            addView(chevron)
            isClickable = true
            isFocusable = true
        }
        header.setOnClickListener { toggle() }
        addView(header)
        addView(body)
        body.visibility = View.GONE
        chevron.text = "▸"

    }

    /** Ouvre la section d'emblée (pour celles qu'on doit voir tout de suite). */
    fun expand() {
        body.visibility = View.VISIBLE
        chevron.text = "▾"
    }

    fun toggle() {
        val expanding = body.visibility != View.VISIBLE
        body.visibility = if (expanding) View.VISIBLE else View.GONE
        chevron.text = if (expanding) "▾" else "▸"
    }

    fun setSummary(text: String) {
        summaryView.text = text
        summaryView.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }

    fun addToBody(view: View) = body.addView(view)
}

// ------------------------------------------------------------ palette

private data class Palette(
    val card: Int, val title: Int, val sub: Int, val field: Int,
    val fieldText: Int, val divider: Int, val ok: Int, val err: Int
)

private fun palette(context: Context): Palette {
    val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    return if (night) Palette(
        card = Color.parseColor("#1D2530"),
        title = Color.parseColor("#ECF1F7"),
        sub = Color.parseColor("#93A2B5"),
        field = Color.parseColor("#28313F"),
        fieldText = Color.parseColor("#ECF1F7"),
        divider = Color.parseColor("#33404F"),
        ok = Color.parseColor("#4CD97B"),
        err = Color.parseColor("#FF6B6B")
    ) else Palette(
        card = Color.parseColor("#F2F4F8"),
        title = Color.parseColor("#1B2430"),
        sub = Color.parseColor("#5B6B7E"),
        field = Color.parseColor("#E3E8F0"),
        fieldText = Color.parseColor("#1B2430"),
        divider = Color.parseColor("#D3DAE4"),
        ok = Color.parseColor("#1E9E50"),
        err = Color.parseColor("#D64545")
    )
}

private val ACCENTS = listOf(
    "#7C5CFF", "#4D9FFF", "#F5A623", "#4CAF7D",
    "#E96AA0", "#1FB6C1", "#FF8A3D", "#9B6DFF",
    "#39D98A"
)

object SettingsDialog {

    // ------------------------------------------------------------ helpers

    // (helpers déplacés en tête de fichier)

    // ------------------------------------------------ section repliable

    // (Section déplacée en tête de fichier)

    // -------------------------------------------------------------- écran

    fun show(context: Context) {
        val p = palette(context)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(18), context.dp(6), context.dp(18), context.dp(10))
        }

        // --- entête type « Plugin Settings »
        val accentBar = View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(
                Color.parseColor("#7C5CFF"), Color.parseColor("#4D9FFF")
            )).apply { cornerRadius = context.dp(3).toFloat() }
            layoutParams = LinearLayout.LayoutParams(context.dp(56), context.dp(5))
        }
        root.addView(accentBar)
        root.addView(TextView(context).label(context, "Réglages FR Unifié", 24f, p.title, bold = true).apply {
            setPadding(0, context.dp(10), 0, 0)
        })
        root.addView(TextView(context).label(
            context, "Configurez sources, catalogues et serveurs", 13f, p.sub
        ).apply { setPadding(0, context.dp(2), 0, context.dp(12)) })

        // ================================================== 1. Scraping
        val sScraping = Section(context, Color.parseColor(ACCENTS[0]), "⚙️",
            "Réglages de scraping", "Sources de liens", p)
        val localSwitch = Switch(context).apply {
            text = "Extensions FR installées"
            isChecked = FrSettings.useLocalSources
            setTextColor(p.title)
        }
        val stremioSwitch = Switch(context).apply {
            text = "Addons Stremio (Torrentio, Comet…)"
            isChecked = FrSettings.useStremio
            setTextColor(p.title)
        }
        val subsSwitch = Switch(context).apply {
            text = "Sous-titres externes (OpenSubtitles)"
            isChecked = FrSettings.useSubtitles
            setTextColor(p.title)
        }
        sScraping.addToBody(localSwitch)
        sScraping.addToBody(stremioSwitch)
        sScraping.addToBody(subsSwitch)
        sScraping.expand()
        root.addView(sScraping)

        // ================================================== 3. Clés API
        val sApi = Section(context, Color.parseColor(ACCENTS[2]), "🔑",
            "Clés API", "TMDB + clés des fournisseurs", p)
        val tmdbField = EditText(context).apply {
            setText(FrSettings.tmdbApiKey)
            inputType = InputType.TYPE_CLASS_TEXT
        }.box(context, p)
        val tokensField = EditText(context).apply {
            setText(FrSettings.apiTokens.entries.joinToString("\n") { "${it.key}=${it.value}" })
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            setHorizontallyScrolling(false)
        }.box(context, p)
        sApi.addToBody(TextView(context).label(context,
            "Clé TMDB (catalogue + repli de recherche des serveurs)", 12f, p.sub))
        sApi.addToBody(tmdbField)
        sApi.addToBody(TextView(context).label(context,
            "Clés fournisseurs : une par ligne au format CLE=valeur.\n" +
                "Clés API supplémentaires, une par ligne (CLE=valeur)",
            12f, p.sub).apply { setPadding(0, context.dp(8), 0, context.dp(2)) })
        sApi.addToBody(tokensField)
        root.addView(sApi)

        // ================================================== 4. Catalogues
        val sCat = Section(context, Color.parseColor(ACCENTS[3]), "🚀",
            "Catalogues actifs", "Quelles recherches afficher", p)
        val tmdbCatSwitch = Switch(context).apply {
            text = "Catalogue TMDB (films et séries)"
            isChecked = FrSettings.useTmdbCatalog
            setTextColor(p.title)
        }
        val animeCatSwitch = Switch(context).apply {
            text = "Catalogue anime (AniList / Jikan)"
            isChecked = FrSettings.useAnimeCatalog
            setTextColor(p.title)
        }
        sCat.addToBody(tmdbCatSwitch)
        sCat.addToBody(animeCatSwitch)
        sCat.addToBody(TextView(context).label(context,
            "TMDB regroupe toutes les saisons dans UNE fiche. AniList/MAL créent " +
                "une fiche par saison : ce catalogue sert de complément pour les " +
                "animés que TMDB ne référence pas.", 11f, p.sub))

        // --- Catalogues Stremio ---
        val stremioCatSwitch = Switch(context).apply {
            text = "Catalogues des addons Stremio"
            isChecked = FrSettings.useStremioCatalog
            setTextColor(p.title)
        }
        val stremioFirstSwitch = Switch(context).apply {
            text = "Placer les catalogues Stremio en premier"
            isChecked = FrSettings.stremioCatalogFirst
            setTextColor(p.title)
        }
        val stremioCatInfo = TextView(context).label(context,
            FrSettings.stremioCatalogRows.size.let {
                if (it > 0) "$it rangée(s) détectée(s)."
                else "Aucune rangée détectée : renseignez vos addons (section 6) puis appuyez sur Détecter."
            }, 11f, p.sub)
        val detectBtn = Button(context).apply {
            text = "🔎  Détecter les catalogues des addons"
            textSize = 13f
        }
        detectBtn.setOnClickListener {
            detectBtn.isEnabled = false
            stremioCatInfo.setTextColor(p.sub)
            stremioCatInfo.text = "Détection en cours…"
            GlobalScope.launch {
                val rows = mutableListOf<String>()
                FrSettings.stremioUrls.forEach { addon ->
                    runCatching { StremioClient.catalogs(addon) }.getOrDefault(emptyList())
                        .forEach { c ->
                            rows += listOf(
                                c.addon, c.type, c.id,
                                c.name.replace("#", " "), c.extra.orEmpty()
                            ).joinToString("#")
                        }
                }
                FrSettings.stremioCatalogRows = rows
                withContext(Dispatchers.Main) {
                    stremioCatInfo.setTextColor(if (rows.isEmpty()) p.err else p.ok)
                    stremioCatInfo.text = if (rows.isEmpty())
                        "Aucun catalogue exploitable trouvé. Torrentio et Comet ne " +
                            "publient que des flux, pas de catalogue : essayez un addon " +
                            "de type catalogue (AIO Metadata, Cinemeta, TMDB Addon…)."
                    else "${rows.size} rangée(s) détectée(s). Cochez-les dans « Rangées " +
                        "de l'accueil », puis rouvrez l'accueil."
                    detectBtn.isEnabled = true
                }
            }
        }
        sCat.addToBody(stremioCatSwitch)
        sCat.addToBody(stremioFirstSwitch)
        sCat.addToBody(detectBtn)
        sCat.addToBody(stremioCatInfo)
        root.addView(sCat)

        // ================================================== 5b. Sources CloudStream
        val sCs = Section(context, Color.parseColor(ACCENTS[8]), "🧲",
            "Sources CloudStream", "Extensions FR installées", p)
        val csContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        sCs.addToBody(TextView(context).label(context,
            "Chargement des extensions…", 12f, p.sub))
        sCs.addToBody(csContainer)
        val srcNames = mutableListOf<String>()
        val srcCheckboxes = mutableListOf<CheckBox>()
        fun refreshSrcCount() {
            val on = srcCheckboxes.count { it.isChecked }
            val ko = SourceHub.quarantinedNames().size
            sCs.setSummary(
                "$on / ${srcNames.size} activées" + if (ko > 0) "  ·  $ko hors service" else ""
            )
        }
        // Case de dépannage : si une extension installée n'apparaît pas dans la
        // liste (elle ne se déclare pas « fr »), on affiche tout.
        val showAllBox = CheckBox(context).apply {
            text = "Afficher toutes les extensions installées (même non FR)"
            isChecked = FrSettings.showAllSources
            setTextColor(p.sub)
            textSize = 12f
            setOnCheckedChangeListener { _, v ->
                FrSettings.showAllSources = v
                Toast.makeText(
                    context,
                    "Rouvrez les réglages pour actualiser la liste.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        sCs.addToBody(showAllBox)

        GlobalScope.launch {
            val sources = if (FrSettings.showAllSources)
                SourceHub.allInstalledSources() else SourceHub.detectedSources()
            withContext(Dispatchers.Main) {
                if (csContainer.childCount > 0 && csContainer.getChildAt(0) is TextView) {
                    (csContainer.getChildAt(0) as TextView).text = ""
                }
                if (sources.isEmpty()) {
                    sCs.addToBody(TextView(context).label(context,
                        "Aucune extension française détectée : installez les addons " +
                            "(French-Stream, Movix, Wiflix, FrenchAnime, Frembed, FSTV, Karma…) " +
                            "dans CloudStream — FR Unifié s'en sert comme sources de liens.",
                        12f, p.sub))
                } else {
                    // Extensions écartées automatiquement (site périmé) : on le
                    // dit clairement, et on les remet en jeu à la fermeture.
                    val ko = SourceHub.quarantinedNames()
                    if (ko.isNotEmpty()) {
                        sCs.addToBody(TextView(context).label(context,
                            "⚠ Écartées automatiquement (le site ne renvoie plus de données " +
                                "valides) : " + ko.joinToString(", ") + ".\n" +
                                "Mettez-les à jour dans CloudStream, ou décochez-les. " +
                                "Elles seront réessayées automatiquement.",
                            11f, p.err))
                    }
                    srcNames.addAll(sources.map { it.name })
                    sources.forEach { api ->
                        val row = LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                        }
                        val box = CheckBox(context).apply {
                            text = api.name + "  ·  " + api.lang.ifBlank { "?" }
                            isChecked = FrSettings.isSourceEnabled(api.name)
                            setTextColor(p.title)
                            textSize = 14f
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                            setOnCheckedChangeListener { _, _ -> refreshSrcCount() }
                        }
                        val testBtn = Button(context).apply {
                            text = "Tester"
                            textSize = 12f
                            minHeight = 0
                            minimumHeight = 0
                            setPadding(context.dp(10), context.dp(4), context.dp(10), context.dp(4))
                        }
                        row.addView(box)
                        row.addView(testBtn)
                        csContainer.addView(row)
                        val result = TextView(context).apply {
                            textSize = 11f
                            setPadding(context.dp(20), 0, 0, context.dp(4))
                        }
                        csContainer.addView(result)
                        srcCheckboxes.add(box)
                        testBtn.setOnClickListener {
                            result.setTextColor(p.sub)
                            result.text = "Test en cours…"
                            testBtn.isEnabled = false
                            GlobalScope.launch {
                                val verdict = SourceHub.testSource(api.name)
                                withContext(Dispatchers.Main) {
                                    result.text = verdict
                                    result.setTextColor(if (verdict.startsWith("✓")) p.ok else p.err)
                                    testBtn.isEnabled = true
                                }
                            }
                        }
                    }
                    refreshSrcCount()

                    // Bouton : teste toutes les extensions et décoche celles
                    // qui ne renvoient aucun résultat exploitable.
                    val purgeBtn = Button(context).apply {
                        text = "🧹  Tester tout et désactiver ce qui ne marche pas"
                        textSize = 13f
                    }
                    val purgeInfo = TextView(context).label(context, "", 11f, p.sub)
                    csContainer.addView(purgeBtn)
                    csContainer.addView(purgeInfo)
                    purgeBtn.setOnClickListener {
                        purgeBtn.isEnabled = false
                        GlobalScope.launch {
                            var dead = 0
                            sources.forEachIndexed { i, api ->
                                withContext(Dispatchers.Main) {
                                    purgeInfo.setTextColor(p.sub)
                                    purgeInfo.text =
                                        "Test ${i + 1}/${sources.size} : ${api.name}…"
                                }
                                val verdict = runCatching { SourceHub.testSource(api.name) }
                                    .getOrDefault("✗")
                                if (!verdict.startsWith("✓")) {
                                    dead++
                                    FrSettings.setSourceEnabled(api.name, false)
                                    withContext(Dispatchers.Main) {
                                        srcCheckboxes.getOrNull(i)?.isChecked = false
                                    }
                                }
                            }
                            withContext(Dispatchers.Main) {
                                purgeInfo.setTextColor(if (dead > 0) p.err else p.ok)
                                purgeInfo.text = if (dead > 0)
                                    "$dead source(s) hors service désactivée(s). " +
                                        "Les autres restent actives."
                                else "Toutes les sources répondent correctement."
                                refreshSrcCount()
                                purgeBtn.isEnabled = true
                            }
                        }
                    }
                }
            }
        }
        sCs.expand()
        root.addView(sCs)

        // ================================================== 5c. Rangées d'accueil
        val sRows = Section(context, Color.parseColor(ACCENTS[1]), "🗂️",
            "Rangées de l'accueil", "Activer / désactiver chaque rangée", p)
        val rowBoxes = mutableListOf<CheckBox>()
        val baseRows = FrUnifiedProvider.BASE_PAGE.map { it.data to it.name }
        val stremioRowsCfg = FrSettings.stremioCatalogRows.mapNotNull { line ->
            val parts = line.split("#")
            if (parts.size < 4) null
            else ("stremio|${parts[0]}#${parts[1]}#${parts[2]}#${parts.getOrNull(4).orEmpty()}") to parts[3]
        }
        fun refreshRowCount() {
            val on = rowBoxes.count { it.isChecked }
            sRows.setSummary("$on / ${rowBoxes.size} rangées affichées")
        }
        fun addRowGroup(title: String, rows: List<Pair<String, String>>) {
            if (rows.isEmpty()) return
            sRows.addToBody(TextView(context).label(context, title, 12f, p.sub))
            rows.forEach { (key, label) ->
                val cb = CheckBox(context).apply {
                    text = label
                    isChecked = FrSettings.isRowEnabled(key)
                    setTextColor(p.title)
                    textSize = 13f
                    setOnCheckedChangeListener { _, v ->
                        FrSettings.setRowEnabled(key, v)
                        refreshRowCount()
                    }
                }
                rowBoxes.add(cb)
                sRows.addToBody(cb)
            }
        }
        addRowGroup("Catalogue d'origine (TMDB / AniList)", baseRows)
        addRowGroup("Catalogues Stremio détectés", stremioRowsCfg)
        if (stremioRowsCfg.isEmpty()) {
            sRows.addToBody(TextView(context).label(context,
                "Aucune rangée Stremio : ajoutez un addon de type catalogue " +
                    "(section 6b) puis appuyez sur Détecter.", 11f, p.sub))
        }
        refreshRowCount()
        root.addView(sRows)

        // ================================================== 6. Addons Stremio
        val sStremio = Section(context, Color.parseColor(ACCENTS[5]), "📺",
            "Addons Stremio", "Flux et catalogues", p,
            summary = { "${FrSettings.stremioUrls.size} addon(s)" })
        sStremio.addToBody(TextView(context).label(context,
            "Un addon Stremio peut fournir des FLUX (Torrentio, Comet, MediaFusion : " +
                "de quoi lire une vidéo) et/ou un CATALOGUE (des listes à parcourir). " +
                "Collez ici toutes vos URL, une par ligne — les deux types cohabitent.",
            11f, p.sub))
        val stremioField = EditText(context).apply {
            setText(FrSettings.stremioUrls.joinToString("\n"))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            setHorizontallyScrolling(false)
        }.box(context, p)
        sStremio.addToBody(stremioField)

        // --- Activation individuelle des addons de FLUX ---
        sStremio.addToBody(TextView(context).label(context,
            "Addons utilisés comme source de FLUX :", 12f, p.sub))
        val addonBoxes = mutableListOf<Pair<String, CheckBox>>()
        FrSettings.stremioUrls.forEach { url ->
            val short = StremioClient.base(url)
                .removePrefix("https://").removePrefix("http://").take(46)
            val cb = CheckBox(context).apply {
                text = short
                isChecked = FrSettings.isStreamAddonEnabled(url)
                setTextColor(p.title)
                textSize = 12f
                setOnCheckedChangeListener { _, v -> FrSettings.setStreamAddonEnabled(url, v) }
            }
            addonBoxes.add(url to cb)
            sStremio.addToBody(cb)
        }
        if (addonBoxes.isEmpty()) {
            sStremio.addToBody(TextView(context).label(context,
                "Aucun addon enregistré pour l'instant.", 11f, p.sub))
        } else {
            sStremio.addToBody(TextView(context).label(context,
                "Décocher un addon le retire des recherches de liens sans l'effacer. " +
                    "Un addon purement catalogue peut rester décoché ici.", 11f, p.sub))
        }
        root.addView(sStremio)

        // ================================================== 7. Sous-titres
        val sSubs = Section(context, Color.parseColor(ACCENTS[6]), "📜",
            "Sous-titres", "Langues conservées", p)
        val langField = EditText(context).apply {
            setText(FrSettings.subtitleLangs.joinToString(", "))
            inputType = InputType.TYPE_CLASS_TEXT
        }.box(context, p)
        sSubs.addToBody(TextView(context).label(context,
            "Codes séparés par des virgules (fre, fra, fr, eng, en…)", 12f, p.sub))
        sSubs.addToBody(langField)
        root.addView(sSubs)

        // ================================================== 8. Crédits
        val sCredits = Section(context, Color.parseColor(ACCENTS[7]), "🎖️",
            "Crédits et remerciements", "FR Unifié", p)
        sCredits.addToBody(TextView(context).label(context,
            "Un seul catalogue (TMDB + AniList) qui agrège les extensions françaises " +
                "installées et les addons Stremio.\n\n" +
                "Astuce : utilisez les boutons « Tester » pour diagnostiquer chaque source.",
            12f, p.sub))
        root.addView(sCredits)

        val scroll = ScrollView(context).apply { addView(root) }

        AlertDialog.Builder(context)
            .setTitle("FR Unifié")
            .setView(scroll)
            .setPositiveButton("Enregistrer") { _, _ ->
                FrSettings.useLocalSources = localSwitch.isChecked
                FrSettings.useStremio = stremioSwitch.isChecked
                FrSettings.useSubtitles = subsSwitch.isChecked

                FrSettings.useTmdbCatalog = tmdbCatSwitch.isChecked
                FrSettings.useAnimeCatalog = animeCatSwitch.isChecked
                FrSettings.useStremioCatalog = stremioCatSwitch.isChecked
                FrSettings.stremioCatalogFirst = stremioFirstSwitch.isChecked

                FrSettings.tmdbApiKey = tmdbField.text.toString()
                FrSettings.apiTokens = tokensField.text.toString()
                    .split("\n")
                    .map { it.trim() }
                    .filter { it.contains("=") }
                    .associate { line ->
                        val i = line.indexOf("=")
                        line.substring(0, i).trim().uppercase() to line.substring(i + 1).trim()
                    }

                srcCheckboxes.forEachIndexed { index, box ->
                    srcNames.getOrNull(index)?.let { FrSettings.setSourceEnabled(it, box.isChecked) }
                }
                // L'utilisateur a pu mettre ses extensions à jour entre-temps :
                // on remet toutes les sources en jeu.
                runCatching { SourceHub.clearQuarantine() }

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
