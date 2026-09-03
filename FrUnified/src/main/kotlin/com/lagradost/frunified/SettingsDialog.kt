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
            "Réglages de scraping", "Sources de liens, limite et priorités", p)
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
        val nuvioSwitch = Switch(context).apply {
            text = "Scrapeurs Nuvio (Gowaru, z7kx…)"
            isChecked = FrSettings.useNuvio
            setTextColor(p.title)
        }
        val nuvioAllSwitch = Switch(context).apply {
            text = "Tous les scrapeurs Nuvio (pas seulement FR)"
            isChecked = FrSettings.nuvioAllLangs
            setTextColor(p.title)
        }
        val subsSwitch = Switch(context).apply {
            text = "Sous-titres externes (OpenSubtitles)"
            isChecked = FrSettings.useSubtitles
            setTextColor(p.title)
        }
        val concurrencyField = EditText(context).apply {
            setText(FrSettings.nuvioConcurrency.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
        }.box(context, p)
        val maxField = EditText(context).apply {
            setText(FrSettings.nuvioMaxPerScraper.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
        }.box(context, p)
        val priorityField = EditText(context).apply {
            setText(FrSettings.nuvioPriorityPatterns.joinToString(", "))
            inputType = InputType.TYPE_CLASS_TEXT
        }.box(context, p)
        val orderField = EditText(context).apply {
            setText(FrSettings.nuvioOrder.joinToString("\n"))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            setHorizontallyScrolling(false)
        }.box(context, p)
        sScraping.addToBody(localSwitch)
        sScraping.addToBody(stremioSwitch)
        sScraping.addToBody(nuvioSwitch)
        sScraping.addToBody(nuvioAllSwitch)
        // Bandeau de diagnostic : indique immédiatement si le moteur JavaScript
        // tourne sur cet appareil (RegExp + messages Rhino) ou pourquoi il échoue.
        val engineBanner = TextView(context).label(context,
            "Moteur Rhino : test en cours…", 12f, p.sub)
        sScraping.addToBody(engineBanner)
        GlobalScope.launch {
            val status = runCatching { NuvioClient.engineStatus() }
                .getOrElse { t -> "✗ moteur : " + (t.message?.take(120) ?: t::class.simpleName.orEmpty()) }
            withContext(Dispatchers.Main) {
                engineBanner.text = status
                engineBanner.setTextColor(if (status.startsWith("✓")) p.ok else p.err)
            }
        }
        sScraping.addToBody(subsSwitch)
        sScraping.addToBody(TextView(context).label(context,
            "Sources lancées en parallèle (1–12)", 12f, p.sub).apply { setPadding(0, context.dp(10), 0, context.dp(2)) })
        sScraping.addToBody(concurrencyField)
        sScraping.addToBody(TextView(context).label(context,
            "Serveurs max par source (0 = illimité)", 12f, p.sub).apply { setPadding(0, context.dp(8), 0, context.dp(2)) })
        sScraping.addToBody(maxField)
        sScraping.addToBody(TextView(context).label(context,
            "Serveurs prioritaires en tête (motifs, séparés par des virgules)\n" +
                "Ex. : VF, FRENCH, VOSTFR, 1080, FHD, HD", 12f, p.sub).apply { setPadding(0, context.dp(8), 0, context.dp(2)) })
        sScraping.addToBody(priorityField)
        sScraping.addToBody(TextView(context).label(context,
            "Ordre personnalisé des sources : un ID par ligne", 12f, p.sub).apply { setPadding(0, context.dp(8), 0, context.dp(2)) })
        sScraping.addToBody(orderField)
        root.addView(sScraping)

        // ================================================== 2. Cloudflare
        val sCf = Section(context, Color.parseColor(ACCENTS[1]), "🌐",
            "Contournement Cloudflare", "User-Agent, référent et cookies (optionnel)", p)
        val uaField = EditText(context).apply {
            setText(FrSettings.nuvioUserAgent)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
        }.box(context, p)
        val refField = EditText(context).apply {
            setText(FrSettings.nuvioReferer)
            inputType = InputType.TYPE_CLASS_TEXT
        }.box(context, p)
        val cookiesField = EditText(context).apply {
            setText(FrSettings.nuvioCookies)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
        }.box(context, p)
        sCf.addToBody(TextView(context).label(context,
            "User-Agent envoyé aux sites (défaut : Chrome Android)", 12f, p.sub))
        sCf.addToBody(uaField)
        sCf.addToBody(TextView(context).label(context, "Referer par défaut", 12f, p.sub).apply {
            setPadding(0, context.dp(8), 0, context.dp(2))
        })
        sCf.addToBody(refField)
        sCf.addToBody(TextView(context).label(context,
            "Cookies (ex. : cf_clearance=…; …) — vide par défaut", 12f, p.sub).apply {
            setPadding(0, context.dp(8), 0, context.dp(2))
        })
        sCf.addToBody(cookiesField)
        root.addView(sCf)

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
                "Injectées dans process.env des scrapeurs Nuvio (ex. : NUVIO_MOVIX_API_KEY=xxx)",
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
        root.addView(sCat)

        // ================================================== 5. Fournisseurs
        val sProv = Section(context, Color.parseColor(ACCENTS[4]), "🎬",
            "Fournisseurs", "Serveurs Nuvio activés", p)
        val provContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        sProv.addToBody(TextView(context).label(context,
            "Chargement des serveurs Nuvio…", 12f, p.sub))
        sProv.addToBody(provContainer)

        val nuvioScrapers = mutableListOf<NuvioClient.NuvioScraper>()
        val provCheckboxes = mutableListOf<CheckBox>()
        val provResults = mutableMapOf<CheckBox, TextView>()
        fun refreshProvCount() {
            val on = provCheckboxes.count { it.isChecked }
            sProv.setSummary("$on / ${nuvioScrapers.size} activés")
        }

        GlobalScope.launch {
            val scrapers = runCatching { NuvioClient.scrapers() }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) {
                // retire le message « Chargement »
                if (provContainer.childCount > 0 && provContainer.getChildAt(0) is TextView) {
                    (provContainer.getChildAt(0) as TextView).text = ""
                }
                if (scrapers.isEmpty()) {
                    sProv.addToBody(TextView(context).label(context,
                        "Aucun serveur trouvé : vérifiez les dépôts ci-dessous.", 12f, p.sub))
                } else {
                    nuvioScrapers.addAll(scrapers)
                    scrapers.forEach { scraper ->
                        val row = LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                        }
                        val box = CheckBox(context).apply {
                            text = scraper.name +
                                (if (scraper.isFrench) "" else "  ·  ${scraper.contentLanguage.joinToString("/")}") +
                                "  ·  ${scraper.supportedTypes.joinToString("/")}"
                            isChecked = FrSettings.isNuvioEnabled(scraper.id)
                            setTextColor(p.title)
                            textSize = 14f
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                            setOnCheckedChangeListener { _, _ -> refreshProvCount() }
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
                        provContainer.addView(row)
                        val result = TextView(context).apply {
                            textSize = 11f
                            setPadding(context.dp(20), 0, 0, context.dp(4))
                        }
                        provContainer.addView(result)
                        provCheckboxes.add(box)
                        provResults[box] = result
                        testBtn.setOnClickListener {
                            result.setTextColor(p.sub)
                            result.text = "Test en cours…"
                            testBtn.isEnabled = false
                            GlobalScope.launch {
                                val verdict = NuvioClient.testProvider(scraper.id)
                                withContext(Dispatchers.Main) {
                                    result.text = verdict
                                    result.setTextColor(if (verdict.startsWith("✓")) p.ok else p.err)
                                    testBtn.isEnabled = true
                                }
                            }
                        }
                    }
                    refreshProvCount()
                }
            }
        }

        sProv.addToBody(TextView(context).label(context,
            "\nDépôts Nuvio (installables) : une URL de manifest.json par ligne.\n" +
                "Gowaru (FrenchStream, Movix, Vostfree…), z7kx (Senpaistreaming…), Phisher (MoviesDrive…)",
            12f, p.sub))
        val reposField = EditText(context).apply {
            setText(FrSettings.nuvioRepos.joinToString("\n"))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
            setHorizontallyScrolling(false)
        }.box(context, p)
        sProv.addToBody(reposField)
        sProv.addToBody(Button(context).apply {
            text = "↺ Réinstaller les dépôts par défaut (Gowaru + z7kx + Phisher)"
            setOnClickListener {
                reposField.setText(FrSettings.DEFAULT_NUVIO_REPOS.joinToString("\n"))
            }
        })
        root.addView(sProv)

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
            sCs.setSummary("$on / ${srcNames.size} activées")
        }
        GlobalScope.launch {
            val sources = SourceHub.detectedSources()
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
                }
            }
        }
        root.addView(sCs)

        // ================================================== 6. Addons Stremio
        val sStremio = Section(context, Color.parseColor(ACCENTS[5]), "📺",
            "Addons Stremio", "Torrentio, Comet, debrid perso…", p,
            summary = { "${FrSettings.stremioUrls.size} addon(s)" })
        val stremioField = EditText(context).apply {
            setText(FrSettings.stremioUrls.joinToString("\n"))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            setHorizontallyScrolling(false)
        }.box(context, p)
        sStremio.addToBody(stremioField)
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
                "installées, les addons Stremio et les scrapeurs Nuvio.\n\n" +
                "Moteur : Rhino 1.9.1 patché et relogé (com.frunified.rhino, invisible pour l\u2019app) · Dépôts Nuvio : Gowaru, z7kx, Phisher.\n" +
                "Astuce : utilisez les boutons « Tester » pour diagnostiquer chaque serveur.",
            12f, p.sub))
        root.addView(sCredits)

        val scroll = ScrollView(context).apply { addView(root) }

        AlertDialog.Builder(context)
            .setTitle("FR Unifié")
            .setView(scroll)
            .setPositiveButton("Enregistrer") { _, _ ->
                FrSettings.useLocalSources = localSwitch.isChecked
                FrSettings.useStremio = stremioSwitch.isChecked
                FrSettings.useNuvio = nuvioSwitch.isChecked
                FrSettings.nuvioAllLangs = nuvioAllSwitch.isChecked
                FrSettings.useSubtitles = subsSwitch.isChecked

                FrSettings.useTmdbCatalog = tmdbCatSwitch.isChecked
                FrSettings.useAnimeCatalog = animeCatSwitch.isChecked

                FrSettings.nuvioConcurrency = concurrencyField.text.toString().toIntOrNull() ?: 6
                FrSettings.nuvioMaxPerScraper = maxField.text.toString().toIntOrNull() ?: 12
                FrSettings.nuvioPriorityPatterns = priorityField.text.toString()
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                FrSettings.nuvioOrder = orderField.text.toString()
                    .split("\n")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }

                FrSettings.nuvioUserAgent = uaField.text.toString()
                FrSettings.nuvioReferer = refField.text.toString()
                FrSettings.nuvioCookies = cookiesField.text.toString()

                FrSettings.tmdbApiKey = tmdbField.text.toString()
                FrSettings.apiTokens = tokensField.text.toString()
                    .split("\n")
                    .map { it.trim() }
                    .filter { it.contains("=") }
                    .associate { line ->
                        val i = line.indexOf("=")
                        line.substring(0, i).trim().uppercase() to line.substring(i + 1).trim()
                    }

                provCheckboxes.forEachIndexed { index, box ->
                    nuvioScrapers.getOrNull(index)?.let { scraper ->
                        FrSettings.setNuvioEnabled(scraper.id, box.isChecked)
                    }
                }
                srcCheckboxes.forEachIndexed { index, box ->
                    srcNames.getOrNull(index)?.let { FrSettings.setSourceEnabled(it, box.isChecked) }
                }

                val oldRepos = FrSettings.nuvioRepos
                val newRepos = reposField.text.toString()
                    .split("\n", ",")
                    .map { it.trim() }
                    .filter { it.startsWith("http") }
                // mémoire les dépôts par défaut retirés (pour ne pas les réinjecter)
                runCatching {
                    val removed = oldRepos.filter { it in FrSettings.DEFAULT_NUVIO_REPOS && it !in newRepos }
                    FrSettings.nuvioRemovedDefaults = FrSettings.nuvioRemovedDefaults + removed
                }
                FrSettings.nuvioRepos = newRepos

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
