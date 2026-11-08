package com.evanaronson.linguize.core.vocab

import java.text.Normalizer

/** One word of a text: characters `[start, end)` of it, exactly as written. */
data class Token(val text: String, val start: Int, val end: Int, val sentenceStart: Boolean)

/**
 * Lab, Vocabulary gaps: splitting the writer's text into words, and a small fallback that
 * folds common inflections toward a deck's lemmas until real morphology is available.
 *
 * The fallback only proposes candidates; a candidate counts only when the deck holds it, so
 * a wrong guess ("casas" to "casa") can't invent a word the deck doesn't have.
 */
object WordFolding {
    /**
     * Words are runs of letters. Catalan's "l·l" stays inside a word; apostrophes and hyphens
     * split ("l'home" is "l" and "home", "fer-ho" is "fer" and "ho").
     */
    fun tokens(text: String): List<Token> {
        val tokens = ArrayList<Token>()
        var start = -1
        var sentenceStart = true
        fun close(end: Int) {
            if (start >= 0) {
                tokens += Token(text.substring(start, end), start, end, sentenceStart)
                sentenceStart = false
                start = -1
            }
        }
        for (index in text.indices) {
            val char = text[index]
            val joins = char == '·' && start >= 0 && text.getOrNull(index + 1)?.isLetter() == true
            if (char.isLetter() || joins || (start >= 0 && Character.getType(char) == Character.NON_SPACING_MARK.toInt())) {
                if (start < 0) start = index
            } else {
                close(index)
                if (char in SENTENCE_END) sentenceStart = true
            }
        }
        close(text.length)
        return tokens
    }

    /** Lowercase, with accents removed: how a word is compared when nothing else matches. */
    fun stripAccents(word: String): String =
        Normalizer.normalize(word, Normalizer.Form.NFD).filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }

    /** Irregular forms of the most frequent words, so they count before morphology exists. */
    fun irregular(word: String, language: String): Set<String> = when (language) {
        "es" -> SPANISH_IRREGULAR[word]
        "ca" -> CATALAN_IRREGULAR[word]
        else -> null
    }.orEmpty()

    /**
     * Plural and gender folding, in the order to try them: "ciudades" to "ciudad", "veces"
     * to "vez", "buena" to "bueno", "cases" to "casa", "petites" to "petit".
     */
    fun nominal(word: String, language: String): List<String> {
        val out = LinkedHashSet<String>()
        fun add(candidate: String) { if (candidate.length >= 2 && candidate != word) out += candidate }
        when (language) {
            "es" -> {
                if (word.endsWith("ces")) add(word.dropLast(3) + "z")
                if (word.endsWith("es")) add(word.dropLast(2))
                if (word.endsWith("s")) add(word.dropLast(1))
                if (word.endsWith("as")) add(word.dropLast(2) + "o")
                if (word.endsWith("a")) add(word.dropLast(1) + "o")
                if (word.endsWith("ones")) add(word.dropLast(4) + "ón")
                if (word.endsWith("eses")) add(word.dropLast(4) + "és")
            }
            "ca" -> {
                if (word.endsWith("ques")) add(word.dropLast(4) + "ca")
                if (word.endsWith("gues")) add(word.dropLast(4) + "ga")
                if (word.endsWith("ges")) add(word.dropLast(3) + "ja")
                if (word.endsWith("ces")) add(word.dropLast(3) + "ça")
                if (word.endsWith("es")) { add(word.dropLast(2) + "a"); add(word.dropLast(2)) }
                if (word.endsWith("s")) add(word.dropLast(1))
                if (word.endsWith("ns")) add(word.dropLast(2))
                if (word.endsWith("ssos")) add(word.dropLast(4) + "s")
                if (word.endsWith("os")) add(word.dropLast(2))
                if (word.endsWith("a")) { add(word.dropLast(1)); add(word.dropLast(1) + "e") }
            }
            else -> if (word.endsWith("s")) add(word.dropLast(1))
        }
        return out.toList()
    }

    /**
     * Infinitives a regular verb form could come from: "hablamos" to "hablar", "comía" to
     * "comer" or "comir", "parlem" to "parlar". Only a deck verb can accept a guess.
     */
    fun verbal(word: String, language: String): List<String> {
        val endings = when (language) {
            "es" -> SPANISH_VERB_ENDINGS
            "ca" -> CATALAN_VERB_ENDINGS
            else -> return emptyList()
        }
        val infinitives = if (language == "es") listOf("ar", "er", "ir") else listOf("ar", "er", "re", "ir")
        val out = LinkedHashSet<String>()
        for (ending in endings) {
            if (word.length <= ending.length + 1 || !word.endsWith(ending)) continue
            val stem = word.dropLast(ending.length)
            for (infinitive in infinitives) out += stem + infinitive
            // Spelling changes before e and i: "busqué" (buscar), "llegué" (llegar), "empecé" (empezar).
            when {
                stem.endsWith("qu") -> out += stem.dropLast(2) + "car"
                stem.endsWith("gu") -> out += stem.dropLast(1) + "ar"
                stem.endsWith("c") && language == "es" -> out += stem.dropLast(1) + "zar"
                stem.endsWith("j") -> { out += stem.dropLast(1) + "gar"; out += stem.dropLast(1) + "ger" }
                stem.endsWith("ç") -> out += stem.dropLast(1) + "çar"
            }
        }
        // Clitics on infinitives and gerunds: "decirle", "dárselo", "fer-ho" already splits.
        if (language == "es") {
            for (clitic in SPANISH_CLITICS) {
                if (word.length > clitic.length + 2 && word.endsWith(clitic)) {
                    val base = stripAccents(word.dropLast(clitic.length))
                    if (base.endsWith("ar") || base.endsWith("er") || base.endsWith("ir")) out += base
                }
            }
        }
        return out.toList()
    }

    private const val SENTENCE_END = ".!?¡¿\n…:"

    private val SPANISH_CLITICS = listOf("selo", "sela", "selos", "selas", "melo", "mela", "telo", "tela", "nos", "les",
        "los", "las", "me", "te", "se", "le", "lo", "la", "os")

    private val SPANISH_VERB_ENDINGS = listOf(
        "aríamos", "eríamos", "iríamos", "ábamos", "íamos", "aremos", "eremos", "iremos", "asteis", "isteis",
        "aron", "ieron", "arán", "erán", "irán", "aría", "ería", "iría", "ando", "iendo", "ado", "ido", "ada", "ida",
        "amos", "emos", "imos", "áis", "éis", "ís", "aba", "abas", "aban", "ía", "ías", "ían", "aste", "iste",
        "ará", "erá", "irá", "aré", "eré", "iré", "ase", "iera", "ara", "ad", "ed", "id",
        "an", "en", "as", "es", "a", "e", "o", "é", "ó", "ió", "ados", "idos", "adas", "idas",
    )

    private val CATALAN_VERB_ENDINGS = listOf(
        "aríem", "eríem", "iríem", "àvem", "íem", "àreu", "éreu", "íreu", "arem", "erem", "irem", "areu", "ereu", "ireu",
        "aran", "eran", "iran", "aria", "eria", "iria", "ava", "aves", "aven", "ia", "ies", "ien",
        "ant", "ent", "int", "at", "ada", "ats", "ades", "it", "ida", "its", "ides", "ut", "uda", "uts", "udes",
        "em", "eu", "en", "es", "a", "o", "i", "ara", "ará", "aré", "eix", "eixo", "eixes", "eixen", "eixi", "im", "iu",
    )

    private fun table(vararg entries: Pair<String, String>): Map<String, Set<String>> =
        entries.flatMap { (lemma, forms) -> forms.split(' ').map { it to lemma } }
            .groupBy({ it.first }, { it.second })
            .mapValues { it.value.toSet() }

    private val SPANISH_IRREGULAR = table(
        "ser" to "soy eres es somos sois son era eras éramos eran fui fuiste fue fuimos fueron sea seas sean seamos sido siendo será serán sería seré",
        "ir" to "voy vas va vamos vais van iba ibas íbamos iban fui fuiste fue fuimos fueron vaya vayas vayamos vayan ido yendo iré irá irán",
        "estar" to "estoy estás está estamos estáis están estaba estabas estábamos estaban estuve estuvo estuvimos estuvieron esté estés estén estado estando",
        "haber" to "he has ha hemos habéis han había habías habíamos habían hubo haya hayas hayamos hayan habrá habría hay",
        "tener" to "tengo tienes tiene tenemos tenéis tienen tenía tenías teníamos tenían tuve tuviste tuvo tuvimos tuvieron tenga tengas tengamos tengan tendré tendrá tendría ten",
        "hacer" to "hago haces hace hacemos hacéis hacen hacía hice hiciste hizo hicimos hicieron haga hagas hagamos hagan hecho haré hará haría haz",
        "poder" to "puedo puedes puede podemos podéis pueden podía pude pudo pudimos pudieron pueda puedas podamos puedan podré podrá podría pudiendo",
        "decir" to "digo dices dice decimos decís dicen decía dije dijiste dijo dijimos dijeron diga digas digamos digan dicho diciendo diré dirá diría di",
        "querer" to "quiero quieres quiere queremos queréis quieren quería quise quiso quisimos quisieron quiera quieras queramos quieran querré querría",
        "saber" to "sé sabes sabe sabemos sabéis saben sabía supe supo supimos supieron sepa sepas sepamos sepan sabré sabrá sabría",
        "ver" to "veo ves ve vemos veis ven veía veías veíamos veían vi viste vio vimos vieron vea veas veamos vean visto viendo",
        "dar" to "doy das da damos dais dan daba di diste dio dimos dieron dé des demos den dado dando",
        "venir" to "vengo vienes viene venimos venís vienen venía vine viniste vino vinimos vinieron venga vengas vengamos vengan vendré vendrá viniendo",
        "poner" to "pongo pones pone ponemos ponéis ponen ponía puse pusiste puso pusimos pusieron ponga pongas pongamos pongan puesto pondré pondrá pon",
        "salir" to "salgo sales sale salimos salís salen salía salga salgas salgamos salgan saldré saldrá sal",
        "traer" to "traigo traes trae traemos traen traje trajo trajimos trajeron traiga traído trayendo",
        "oír" to "oigo oyes oye oímos oyen oía oí oyó oyeron oiga oído oyendo",
        "volver" to "vuelvo vuelves vuelve volvemos volvéis vuelven vuelva vuelto",
        "pensar" to "pienso piensas piensa pensamos piensan piense",
        "querer" to "quiero",
        "sentir" to "siento sientes siente sentimos sienten sintió sienta sintiendo",
        "dormir" to "duermo duermes duerme dormimos duermen durmió duerma durmiendo",
        "pedir" to "pido pides pide pedimos piden pidió pida pidiendo",
        "seguir" to "sigo sigues sigue seguimos siguen siguió siga siguiendo",
        "conocer" to "conozco",
        "parecer" to "parezco",
        "creer" to "creyó creyeron creyendo",
        "leer" to "leyó leyeron leyendo",
        "morir" to "muero mueres muere mueren murió muera muerto",
        "escribir" to "escrito",
        "abrir" to "abierto",
        "romper" to "roto",
        "empezar" to "empiezo empiezas empieza empiezan empiece",
        "encontrar" to "encuentro encuentras encuentra encuentran encuentre",
        "contar" to "cuento cuentas cuenta cuentan cuente",
        "costar" to "cuesta cuestan",
        "jugar" to "juego juegas juega juegan juegue",
        "entender" to "entiendo entiendes entiende entienden entienda",
        "perder" to "pierdo pierdes pierde pierden pierda",
        "recordar" to "recuerdo recuerdas recuerda recuerdan recuerde",
        "llover" to "llueve",
        "el" to "la los las",
        "un" to "una unos unas",
        "este" to "esta estos estas",
        "ese" to "esa esos esas",
        "aquel" to "aquella aquellos aquellas",
        "mi" to "mis",
        "tu" to "tus",
        "su" to "sus",
        "nuestro" to "nuestra nuestros nuestras",
        "vuestro" to "vuestra vuestros vuestras",
        "él" to "ellos",
        "ella" to "ellas",
        "nosotros" to "nosotras",
        "vosotros" to "vosotras",
        "alguno" to "algún alguna algunos algunas",
        "ninguno" to "ningún ninguna",
        "bueno" to "buen",
        "malo" to "mal",
        "grande" to "gran",
        "primero" to "primer",
        "tercero" to "tercer",
        "cualquiera" to "cualquier",
    )

    private val CATALAN_IRREGULAR = table(
        "ser" to "sóc soc ets és som sou són era eres érem éreu eren fui fou fórem foren sigui siguis siguem sigueu siguin estat sent serà seran seria seré",
        "estar" to "estic estàs està estem esteu estan estava estaves estàvem estaven estigui estiguis estiguem estiguin estat estaré estarà",
        "anar" to "vaig vas va anem aneu van anava anaves anàvem anaven vagi vagis anem vagin anat anant aniré anirà anirem aniria",
        "haver" to "he has ha hem heu han havia havies havíem havien hagi hagis hàgim hagin hagut haurà hauria hi",
        "tenir" to "tinc tens té tenim teniu tenen tenia tenies teníem tenien tingui tinguis tinguem tinguin tingut tindré tindrà tindria",
        "fer" to "faig fas fa fem feu fan feia feies fèiem feien faci facis fem facin fet fent faré farà farem faria",
        "poder" to "puc pots pot podem podeu poden podia pugui puguis puguem puguin pogut podré podrà podria",
        "dir" to "dic dius diu diem dieu diuen deia deies dèiem deien digui diguis diguem diguin dit dient diré dirà diria",
        "voler" to "vull vols vol volem voleu volen volia vulgui vulguis vulguem vulguin volgut voldré voldrà voldria",
        "saber" to "sé saps sap sabem sabeu saben sabia sàpiga sàpigues sapiguem sàpiguen sabut sabré sabrà sabria",
        "veure" to "veig veus veu veiem veieu veuen veia vegi vegis vegem vegin vist veient veuré veurà",
        "venir" to "vinc vens ve venim veniu venen venia vingui vinguis vinguem vinguin vingut vindré vindrà",
        "donar" to "dono dones dóna dona donem doneu donen",
        "posar" to "poso poses posa posem poseu posen",
        "sortir" to "surto surts surt sortim sortiu surten surti",
        "creure" to "crec creus creu creiem creieu creuen cregui cregut",
        "viure" to "visc vius viu vivim viviu viuen visqui viscut",
        "escriure" to "escric escrius escriu escrivim escriuen escrigui escrit",
        "beure" to "bec beus beu bevem beveu beuen begui begut",
        "conèixer" to "conec coneixes coneix coneixem coneixen conegui conegut",
        "prendre" to "prenc prens pren prenem preneu prenen prengui pres",
        "entendre" to "entenc entens entén entenem enteneu entenen entengui entès",
        "aprendre" to "aprenc aprens aprèn aprenem aprenen aprengui après",
        "treure" to "trec treus treu traiem traieu treuen tregui tret",
        "dur" to "duc duus duu dus du duem dueu duen dugui dut",
        "caure" to "caic caus cau caiem caieu cauen caigui caigut",
        "seure" to "sec seus seu seiem seieu seuen segui segut",
        "obrir" to "obert",
        "morir" to "mort",
        "néixer" to "nascut",
        "rebre" to "rebut",
        "perdre" to "perdut",
        "valer" to "val valen",
        "el" to "la els les l lo",
        "un" to "una uns unes",
        "aquest" to "aquesta aquests aquestes",
        "aquell" to "aquella aquells aquelles",
        "meu" to "meva meus meves mon ma mos mes",
        "teu" to "teva teus teves ton ta tos tes",
        "seu" to "seva seus seves son sa sos ses",
        "nostre" to "nostra nostres",
        "vostre" to "vostra vostres",
        "ell" to "ells",
        "ella" to "elles",
        "de" to "d",
        "em" to "m me",
        "et" to "t te",
        "es" to "s se",
        "en" to "n ne",
        "algun" to "alguna alguns algunes",
        "tot" to "tota tots totes",
        "molt" to "molta molts moltes",
        "poc" to "poca pocs poques",
        "altre" to "altra altres",
        "bo" to "bon bona bons bones",
        "gran" to "grans",
    )
}
